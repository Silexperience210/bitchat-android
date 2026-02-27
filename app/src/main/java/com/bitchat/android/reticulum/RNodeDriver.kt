package com.bitchat.android.reticulum

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.bitchat.android.transport.lora.LoRaRadio
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Driver pour RNode (firmware Reticulum)
 * 
 * RNode utilise le protocole KISS TNC sur USB Serial
 * - Baud rate: 115200
 * - Protocole: KISS (Keep It Simple, Stupid)
 * - Framing: FEND (0xC0) délimiteurs
 * 
 * KISS Protocol:
 * - FEND (0xC0) = Frame End
 * - FESC (0xDB) = Frame Escape
 * - TFEND (0xDC) = Transposed FEND
 * - TFESC (0xDD) = Transposed FESC
 * 
 * Documentation: https://github.com/markqvist/RNode_Firmware
 */
class RNodeDriver(
    private val context: Context,
    private val usbDevice: UsbDevice
) : LoRaRadio {
    
    companion object {
        const val TAG = "RNodeDriver"
        
        // KISS constants
        const val FEND: Byte = 0xC0.toByte()
        const val FESC: Byte = 0xDB.toByte()
        const val TFEND: Byte = 0xDC.toByte()
        const val TFESC: Byte = 0xDD.toByte()
        
        // KISS commands
        const val CMD_DATA: Byte = 0x00
        const val CMD_TXDELAY: Byte = 0x01
        const val CMD_PERSISTENCE: Byte = 0x02
        const val CMD_SLOTTIME: Byte = 0x03
        const val CMD_TXTAIL: Byte = 0x04
        const val CMD_FULLDUPLEX: Byte = 0x05
        const val CMD_SETHARDWARE: Byte = 0x06
        const val CMD_RETURN: Byte = 0xFF.toByte()
        
        // RNode specific (CMD_SETHARDWARE subcommands)
        const val RNODE_CMD_FREQ: Byte = 0x01
        const val RNODE_CMD_BW: Byte = 0x02
        const val RNODE_CMD_SF: Byte = 0x03
        const val RNODE_CMD_CR: Byte = 0x04
        const val RNODE_CMD_TXPOWER: Byte = 0x05
        const val RNODE_CMD_STATE: Byte = 0x06
        const val RNODE_CMD_READY: Byte = 0x07
        const val RNODE_CMD_RX: Byte = 0x08
        const val RNODE_CMD_TX: Byte = 0x09
        const val RNODE_CMD_RSSI: Byte = 0x0A
        const val RNODE_CMD_SNR: Byte = 0x0B
        
        const val MAX_PACKET_SIZE = 255
    }
    
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var receiveCallback: ((payload: ByteArray, rssi: Int, snr: Float) -> Unit)? = null
    private var isRunning = false
    private var currentConfig: LoRaRadio.RadioConfig? = null
    
    // Buffer for incoming data
    private val rxBuffer = java.nio.ByteBuffer.allocate(1024)
    private var inEscape = false
    
    // Last known RSSI/SNR
    private var lastRssi = -120
    private var lastSnr = 0f
    
    override fun configure(config: LoRaRadio.RadioConfig) {
        currentConfig = config
        openSerialPort()
        
        // Configure RNode with KISS commands
        configureRNode(config)
    }
    
    override fun startReceive(callback: (payload: ByteArray, rssi: Int, snr: Float) -> Unit) {
        this.receiveCallback = callback
        isRunning = true
        
        // Start I/O manager
        ioManager = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                processKissData(data)
            }
            
            override fun onRunError(e: Exception) {
                // Handle error
            }
        })
        
        executor.submit(ioManager)
        
        // Enter RX mode
        sendKissCommand(RNODE_CMD_RX)
    }
    
    override fun transmit(data: ByteArray) {
        require(data.size <= MAX_PACKET_SIZE) { "Payload too large for KISS" }
        
        // Build KISS frame: FEND CMD_DATA [escaped data] FEND
        val frame = buildKissFrame(CMD_DATA, data)
        serialPort?.write(frame, 1000)
    }
    
    override fun isChannelFree(frequency: Long, threshold: Int): Boolean {
        // Request RSSI from RNode
        sendKissCommand(RNODE_CMD_RSSI)
        Thread.sleep(50)  // Wait for response
        return lastRssi < threshold
    }
    
    override fun stop() {
        isRunning = false
        ioManager?.stop()
        executor.shutdown()
        serialPort?.close()
    }
    
    override fun getMetrics(): LoRaRadio.RadioMetrics {
        return LoRaRadio.RadioMetrics(
            lastRssi = lastRssi,
            lastSnr = lastSnr,
            rtt = 500L
        )
    }
    
    private fun openSerialPort() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        
        if (!usbManager.hasPermission(usbDevice)) {
            throw IllegalStateException("USB permission not granted")
        }
        
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = availableDrivers.find { it.device == usbDevice }
            ?: throw IllegalStateException("No driver for device")
        
        val connection = usbManager.openDevice(usbDevice)
            ?: throw IllegalStateException("Failed to open device")
        
        serialPort = driver.ports.firstOrNull()
            ?: throw IllegalStateException("No serial port")
        
        serialPort?.open(connection)
        serialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }
    
    private fun configureRNode(config: LoRaRadio.RadioConfig) {
        // Wait for RNode to be ready
        Thread.sleep(500)
        
        // Set frequency
        sendKissConfig(RNODE_CMD_FREQ, config.frequency.toInt())
        
        // Set bandwidth (in kHz)
        sendKissConfig(RNODE_CMD_BW, config.bandwidth / 1000)
        
        // Set spreading factor
        sendKissConfig(RNODE_CMD_SF, config.spreadingFactor)
        
        // Set coding rate
        sendKissConfig(RNODE_CMD_CR, config.codingRate)
        
        // Set TX power
        sendKissConfig(RNODE_CMD_TXPOWER, config.txPower)
        
        // Set state to ready
        sendKissCommand(RNODE_CMD_READY)
    }
    
    private fun sendKissCommand(command: Byte) {
        val frame = byteArrayOf(FEND, (CMD_SETHARDWARE.toInt() or (command.toInt() and 0x0F)).toByte(), FEND)
        serialPort?.write(frame, 1000)
    }
    
    private fun sendKissConfig(command: Byte, value: Int) {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        val data = byteArrayOf(command) + buffer
        val frame = buildKissFrame(CMD_SETHARDWARE, data)
        serialPort?.write(frame, 1000)
    }
    
    private fun buildKissFrame(command: Byte, data: ByteArray): ByteArray {
        val escaped = escapeKiss(data)
        return byteArrayOf(FEND, command) + escaped + byteArrayOf(FEND)
    }
    
    private fun escapeKiss(data: ByteArray): ByteArray {
        val result = mutableListOf<Byte>()
        for (b in data) {
            when (b) {
                FEND -> {
                    result.add(FESC)
                    result.add(TFEND)
                }
                FESC -> {
                    result.add(FESC)
                    result.add(TFESC)
                }
                else -> result.add(b)
            }
        }
        return result.toByteArray()
    }
    
    private fun unescapeKiss(data: ByteArray): ByteArray {
        val result = mutableListOf<Byte>()
        var inEscape = false
        for (b in data) {
            when {
                inEscape -> {
                    when (b) {
                        TFEND -> result.add(FEND)
                        TFESC -> result.add(FESC)
                        else -> result.add(b)
                    }
                    inEscape = false
                }
                b == FESC -> inEscape = true
                else -> result.add(b)
            }
        }
        return result.toByteArray()
    }
    
    private fun processKissData(data: ByteArray) {
        for (b in data) {
            when {
                b == FEND -> {
                    // End of frame, process buffer
                    val frameLength = rxBuffer.position()
                    if (frameLength > 0) {
                        val frame = ByteArray(frameLength)
                        rxBuffer.flip()
                        rxBuffer.get(frame)
                        processKissFrame(frame)
                    }
                    rxBuffer.clear()
                    inEscape = false
                }
                b == FESC -> inEscape = true
                inEscape -> {
                    val unescaped = when (b) {
                        TFEND -> FEND
                        TFESC -> FESC
                        else -> b
                    }
                    rxBuffer.put(unescaped)
                    inEscape = false
                }
                else -> rxBuffer.put(b)
            }
        }
    }
    
    private fun processKissFrame(frame: ByteArray) {
        if (frame.isEmpty()) return
        
        val command = frame[0].toInt() and 0x0F
        val data = if (frame.size > 1) frame.copyOfRange(1, frame.size) else byteArrayOf()
        
        when (command) {
            CMD_DATA.toInt() -> {
                // Data packet received
                receiveCallback?.invoke(data, lastRssi, lastSnr)
            }
            RNODE_CMD_RSSI.toInt() -> {
                // RSSI update
                if (data.size >= 1) {
                    lastRssi = data[0].toInt()
                }
            }
            RNODE_CMD_SNR.toInt() -> {
                // SNR update
                if (data.size >= 1) {
                    lastSnr = data[0].toInt() / 4f
                }
            }
        }
    }
}
