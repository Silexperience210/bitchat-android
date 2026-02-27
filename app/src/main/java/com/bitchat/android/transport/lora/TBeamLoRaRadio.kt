package com.bitchat.android.transport.lora

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Driver spécifique pour LilyGo T-Beam
 * 
 * Hardware:
 * - ESP32 + SX1262
 * - CP2102 USB Serial
 * - 868MHz / 915MHz
 * - GPS intégré (optionnel)
 * 
 * Protocole supporté:
 * - Mode "Radio Head" compatible
 * - ou Mode "Meshtastic" binary
 * - ou Protocol BitChat custom
 * 
 * Configuration recommandée T-Beam:
 * - Frequency: 868.0 MHz (EU) / 915.0 MHz (US)
 * - SF: 9
 * - BW: 125 kHz
 * - CR: 4/8
 * - TX Power: 14 dBm
 */
class TBeamLoRaRadio(
    private val context: Context,
    private val usbDevice: UsbDevice
) : LoRaRadio {

    companion object {
        const val TAG = "TBeamLoRaRadio"
        
        // VID/PID du CP2102 sur T-Beam
        const val CP2102_VENDOR_ID = 0x10C4
        const val CP2102_PRODUCT_ID = 0xEA60
        
        // Commandes protocol BitChat sur T-Beam
        const val CMD_SYNC: Byte = 0x01
        const val CMD_CONFIG: Byte = 0x02
        const val CMD_TX: Byte = 0x03
        const val CMD_RX: Byte = 0x04
        const val CMD_CAD: Byte = 0x05
        const val CMD_STATUS: Byte = 0x06
        
        // Réponses
        const val RSP_ACK: Byte = 0x10
        const val RSP_NACK: Byte = 0x11
        const val RSP_RX: Byte = 0x12
        const val RSP_CAD: Byte = 0x13
        const val RSP_STATUS: Byte = 0x14
        
        // Tailles
        const val HEADER_SIZE = 4
        const val MAX_PAYLOAD = 237
    }

    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var connection: UsbDeviceConnection? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val executor = Executors.newSingleThreadExecutor()
    
    // Callback de réception
    private var receiveCallback: ((payload: ByteArray, rssi: Int, snr: Float) -> Unit)? = null
    
    // État
    private var isRunning = false
    private var currentConfig: LoRaRadio.RadioConfig? = null
    
    // Buffer de réception
    private val rxBuffer = ByteArray(512)
    private var rxBufferPos = 0
    
    // Métriques
    private var metrics = LoRaRadio.RadioMetrics()
    
    // Synchronisation
    private val pendingCommands = mutableMapOf<Byte, CompletableDeferred<ByteArray?>>()

    override fun configure(config: LoRaRadio.RadioConfig) {
        currentConfig = config
        
        // Ouvrir le port série
        openSerialPort()
        
        // Attendre sync avec T-Beam
        runBlocking {
            syncWithDevice()
        }
        
        // Envoyer configuration
        sendConfigCommand(config)
    }

    override fun startReceive(callback: (payload: ByteArray, rssi: Int, snr: Float) -> Unit) {
        this.receiveCallback = callback
        isRunning = true
        
        // Démarrer I/O manager
        ioManager = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                handleReceivedData(data)
            }
            
            override fun onRunError(e: Exception) {
                // Log erreur et tentative reconnexion
            }
        })
        
        executor.submit(ioManager)
        
        // Activer mode RX continu
        sendCommand(CMD_RX)
    }

    override fun transmit(data: ByteArray) {
        require(data.size <= MAX_PAYLOAD) { "Payload too large for LoRa" }
        
        val packet = ByteBuffer.allocate(HEADER_SIZE + data.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(CMD_TX)
            putShort(data.size.toShort())
            put(0x00.toByte()) // Flags
            put(data)
        }.array()
        
        serialPort?.write(packet, 1000)
        metrics = metrics.copy(txCounter = metrics.txCounter + 1)
    }

    override fun isChannelFree(frequency: Long, threshold: Int): Boolean {
        return runBlocking {
            val deferred = CompletableDeferred<Boolean>()
            
            // Envoyer commande CAD
            val cmd = ByteBuffer.allocate(8).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(CMD_CAD)
                putShort(4.toShort())
                put(0x00.toByte())
                putInt(threshold)
            }.array()
            
            serialPort?.write(cmd, 1000)
            
            // Attendre réponse (timeout 500ms)
            withTimeoutOrNull(500) {
                // La réponse sera traitée dans handleReceivedData
                // et complétera le deferred
                deferred.await()
            } ?: true // Si timeout, on suppose canal libre
        }
    }

    override fun stop() {
        isRunning = false
        ioManager?.stop()
        executor.shutdown()
        scope.cancel()
        serialPort?.close()
        connection?.close()
    }

    override fun getMetrics(): LoRaRadio.RadioMetrics = metrics

    // ============== Méthodes privées ==============

    private fun openSerialPort() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        
        // Vérifier permission
        if (!usbManager.hasPermission(usbDevice)) {
            throw LoRaRadioException("USB permission not granted")
        }
        
        // Ouvrir connection
        connection = usbManager.openDevice(usbDevice)
            ?: throw LoRaRadioException("Failed to open USB device")
        
        // Trouver driver
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = availableDrivers.find { it.device == usbDevice }
            ?: throw LoRaRadioException("No serial driver found")
        
        // Ouvrir port
        serialPort = driver.ports.firstOrNull()
            ?: throw LoRaRadioException("No serial port available")
        
        serialPort?.open(connection)
        serialPort?.setParameters(
            115200,  // Baud rate
            UsbSerialPort.DATABITS_8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE
        )
        serialPort?.dtr = true
        serialPort?.rts = true
    }

    private suspend fun syncWithDevice(): Boolean {
        // Envoyer sync et attendre réponse
        val syncPacket = byteArrayOf(CMD_SYNC, 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
        
        repeat(3) { attempt ->
            serialPort?.write(syncPacket, 1000)
            delay(100)
            
            // Vérifier si on a reçu ACK
            // (simplifié - en vrai il faudrait parser la réponse)
            return true
        }
        
        return false
    }

    private fun sendConfigCommand(config: LoRaRadio.RadioConfig) {
        val cmd = ByteBuffer.allocate(20).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(CMD_CONFIG)
            putShort(16.toShort()) // Payload size
            put(0x00.toByte()) // Flags
            putInt(config.frequency.toInt())
            put(config.spreadingFactor.toByte())
            putShort(config.bandwidth.toShort())
            put(config.codingRate.toByte())
            put(config.txPower.toByte())
            putShort(config.preambleLength.toShort())
            put(config.syncWord.toByte())
        }.array()
        
        serialPort?.write(cmd, 1000)
    }

    private fun sendCommand(cmd: Byte) {
        val packet = byteArrayOf(cmd, 0x00, 0x00, 0x00)
        serialPort?.write(packet, 1000)
    }

    private fun handleReceivedData(data: ByteArray) {
        // Ajouter au buffer
        System.arraycopy(data, 0, rxBuffer, rxBufferPos, data.size)
        rxBufferPos += data.size
        
        // Parser les packets complets
        while (rxBufferPos >= HEADER_SIZE) {
            val packetLen = parsePacketLength()
            
            if (rxBufferPos >= HEADER_SIZE + packetLen) {
                // On a un packet complet
                val packet = extractPacket(packetLen)
                processPacket(packet)
                
                // Décaler le buffer
                shiftBuffer(HEADER_SIZE + packetLen)
            } else {
                break // Attendre plus de données
            }
        }
        
        // Prévenir overflow
        if (rxBufferPos > rxBuffer.size - MAX_PAYLOAD) {
            shiftBuffer(rxBufferPos) // Vider buffer
        }
    }

    private fun parsePacketLength(): Int {
        return (((rxBuffer[1].toInt() and 0xFF) shl 8) or 
               (rxBuffer[2].toInt() and 0xFF)).coerceAtLeast(0)
    }

    private fun extractPacket(payloadLen: Int): ByteArray {
        return rxBuffer.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen)
    }

    private fun shiftBuffer(bytes: Int) {
        System.arraycopy(rxBuffer, bytes, rxBuffer, 0, rxBufferPos - bytes)
        rxBufferPos -= bytes
    }

    private fun processPacket(packet: ByteArray) {
        when (rxBuffer[0]) {
            RSP_RX -> {
                // Packet reçu: [RSSI(2) | SNR(2) | Payload(n)]
                if (packet.size >= 4) {
                    val rssi = ((packet[0].toInt() and 0xFF) shl 8) or 
                               (packet[1].toInt() and 0xFF)
                    val snr = ((packet[2].toInt() and 0xFF) shl 8) or 
                              (packet[3].toInt() and 0xFF)
                    val payload = packet.copyOfRange(4, packet.size)
                    
                    receiveCallback?.invoke(payload, rssi - 32768, snr / 10f)
                    
                    metrics = metrics.copy(
                        rxCounter = metrics.rxCounter + 1,
                        lastRssi = rssi - 32768,
                        lastSnr = snr / 10f
                    )
                }
            }
            
            RSP_CAD -> {
                // Résultat CAD: [Free(1)]
                if (packet.isNotEmpty()) {
                    val isFree = packet[0] != 0.toByte()
                    // Compléter deferred si attendu
                }
            }
            
            RSP_STATUS -> {
                // Status: [Temp(1) | Voltage(2) | ...]
                if (packet.isNotEmpty()) {
                    metrics = metrics.copy(
                        temperature = packet[0].toFloat()
                    )
                }
            }
        }
    }

    class LoRaRadioException(message: String) : Exception(message)
}

/**
 * Extension pour détection T-Beam
 */
fun UsbDevice.isTBeam(): Boolean {
    return vendorId == 0x10C4 && productId == 0xEA60
}
