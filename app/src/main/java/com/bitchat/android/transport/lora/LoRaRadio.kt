package com.bitchat.android.transport.lora

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbEndpoint

/**
 * Interface abstraite pour les modules radio LoRa
 * Permet de supporter différents hardwares:
 * - USB Serial (CH340, CP2102, FTDI)
 * - SPI direct (sur Raspberry Pi ou appareils rootés)
 * - UART interne (sur certains SoC)
 */
interface LoRaRadio {
    
    /**
     * Configure les paramètres radio
     */
    fun configure(config: RadioConfig)
    
    /**
     * Démarre la réception en mode continu
     * @param callback Appelé pour chaque packet reçu (payload, rssi, snr)
     */
    fun startReceive(callback: (payload: ByteArray, rssi: Int, snr: Float) -> Unit)
    
    /**
     * Transmet des données
     */
    fun transmit(data: ByteArray)
    
    /**
     * Vérifie si le canal est libre (CAD - Channel Activity Detection)
     */
    fun isChannelFree(frequency: Long, threshold: Int): Boolean
    
    /**
     * Arrête la radio
     */
    fun stop()
    
    /**
     * Retourne les métriques de la radio
     */
    fun getMetrics(): RadioMetrics
    
    data class RadioConfig(
        val frequency: Long,
        val spreadingFactor: Int,
        val bandwidth: Int,
        val codingRate: Int,
        val txPower: Int,
        val preambleLength: Int = 16,
        val syncWord: Byte = 0x2B  // 0x2B = Meshtastic compatible, 0xBC = BitChat
    )
    
    data class RadioMetrics(
        val temperature: Float = 0f,
        val txCounter: Long = 0,
        val rxCounter: Long = 0,
        val lastRssi: Int = 0,
        val lastSnr: Float = 0f,
        val rtt: Long = 1000L
    )
}

/**
 * Implementation pour modules USB Serial (CH340, CP2102, FTDI)
 * 
 * Protocole: Commandes AT ou protocole binaire spécifique au module
 * 
 * Modules supportés:
 * - Wio-SX1262 (Seeed) - Protocole AT
 * - LilyGo T-Beam - CP2102 + firmware personnalisé
 * - Modules CH340 + SX1262 - Protocole variable
 */
class UsbSerialRadio(
    private val context: Context,
    private val usbDevice: UsbDevice
) : LoRaRadio {
    
    private var serialPort: UsbSerialPortWrapper? = null
    private var isRunning = false
    
    // Buffer de réception
    private val rxBuffer = ByteArray(1024)
    
    // Callback de réception
    private var receiveCallback: ((ByteArray, Int, Float) -> Unit)? = null
    
    // Métriques
    private var metrics = LoRaRadio.RadioMetrics()
    
    override fun configure(config: LoRaRadio.RadioConfig) {
        // Ouvrir le port série
        openSerialPort()
        
        // Envoyer commandes de configuration
        when (detectModuleType()) {
            ModuleType.WIO_SX1262 -> configureWio(config)
            ModuleType.TBEAM -> configureTBeam(config)
            ModuleType.GENERIC_AT -> configureGenericAT(config)
            ModuleType.BINARY -> configureBinary(config)
        }
    }
    
    override fun startReceive(callback: (payload: ByteArray, rssi: Int, snr: Float) -> Unit) {
        this.receiveCallback = callback
        isRunning = true
        
        // Démarrer thread de lecture
        Thread {
            while (isRunning) {
                try {
                    readAndProcess()
                    Thread.sleep(10)
                } catch (e: Exception) {
                    // Log erreur
                }
            }
        }.start()
    }
    
    override fun transmit(data: ByteArray) {
        when (detectModuleType()) {
            ModuleType.WIO_SX1262 -> transmitWio(data)
            ModuleType.TBEAM -> transmitTBeam(data)
            ModuleType.GENERIC_AT -> transmitGenericAT(data)
            ModuleType.BINARY -> transmitBinary(data)
        }
        
        metrics = metrics.copy(txCounter = metrics.txCounter + 1)
    }
    
    override fun isChannelFree(frequency: Long, threshold: Int): Boolean {
        // CAD (Channel Activity Detection)
        return when (detectModuleType()) {
            ModuleType.WIO_SX1262 -> cadWio()
            else -> {
                // Fallback: lire RSSI
                val rssi = readRssi()
                rssi < threshold
            }
        }
    }
    
    override fun stop() {
        isRunning = false
        serialPort?.close()
    }
    
    override fun getMetrics(): LoRaRadio.RadioMetrics = metrics
    
    // ============== Configuration spécifique par module ==============
    
    private fun configureWio(config: LoRaRadio.RadioConfig) {
        // Wio-SX1262 utilise des commandes AT
        sendCommand("AT+MODE=TEST")
        sendCommand("AT+TEST=RFCFG,${config.frequency / 1_000_000},${config.spreadingFactor},${config.bandwidth / 1000},${config.codingRate},${config.txPower}")
        sendCommand("AT+TEST=RXLRPKT")
    }
    
    private fun configureTBeam(config: LoRaRadio.RadioConfig) {
        // T-Beam utilise généralement un protocole binaire
        // ou des commandes AT modifiées
        sendCommand("AT+CFG=${config.frequency},${config.spreadingFactor},${config.bandwidth}")
    }
    
    private fun configureGenericAT(config: LoRaRadio.RadioConfig) {
        sendCommand("AT+FREQUENCY=${config.frequency}")
        sendCommand("AT+SF=${config.spreadingFactor}")
        sendCommand("AT+BW=${config.bandwidth}")
        sendCommand("AT+CR=4/${config.codingRate}")
        sendCommand("AT+POWER=${config.txPower}")
    }
    
    private fun configureBinary(config: LoRaRadio.RadioConfig) {
        // Protocole binaire spécifique
        val cmd = ByteArray(16)
        // Construire commande binaire...
        serialPort?.write(cmd, 1000)
    }
    
    // ============== Transmission spécifique par module ==============
    
    private fun transmitWio(data: ByteArray) {
        val hexData = data.toHex()
        sendCommand("AT+TEST=TXLRPKT,\"$hexData\"")
    }
    
    private fun transmitTBeam(data: ByteArray) {
        serialPort?.write(data, 1000)
    }
    
    private fun transmitGenericAT(data: ByteArray) {
        val hexData = data.toHex()
        sendCommand("AT+SEND=$hexData")
    }
    
    private fun transmitBinary(data: ByteArray) {
        // Ajouter header binaire si nécessaire
        val packet = byteArrayOf(0xAA.toByte(), 0x55) + data
        serialPort?.write(packet, 1000)
    }
    
    // ============== CAD (Channel Activity Detection) ==============
    
    private fun cadWio(): Boolean {
        val response = sendCommand("AT+TEST=CAD")
        return response?.contains("CAD_DETECTED=0") ?: true
    }
    
    private fun readRssi(): Int {
        val response = sendCommand("AT+RSSI?")
        // Parser RSSI depuis réponse...
        return -120 // Valeur par défaut
    }
    
    // ============== Utilitaires ==============
    
    private fun openSerialPort() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(usbDevice)
        
        // Trouver l'interface série
        val usbInterface = usbDevice.getInterface(0)
        connection?.claimInterface(usbInterface, true)
        
        // Ouvrir port avec paramètres par défaut (57600 8N1)
        serialPort = UsbSerialPortWrapper(connection, usbInterface.getEndpoint(0), usbInterface.getEndpoint(1))
        serialPort?.open(57600)
    }
    
    private fun sendCommand(cmd: String): String? {
        val data = (cmd + "\r\n").toByteArray()
        serialPort?.write(data, 1000)
        
        // Attendre réponse (simple)
        Thread.sleep(100)
        val response = ByteArray(256)
        val len = serialPort?.read(response, 1000) ?: 0
        
        return if (len > 0) {
            String(response, 0, len)
        } else null
    }
    
    private fun readAndProcess() {
        val len = serialPort?.read(rxBuffer, 100) ?: 0
        if (len > 0) {
            val data = rxBuffer.copyOf(len)
            
            // Parser selon le type de module
            when (detectModuleType()) {
                ModuleType.WIO_SX1262 -> parseWioResponse(data)
                else -> parseGenericResponse(data)
            }
        }
    }
    
    private fun parseWioResponse(data: ByteArray) {
        val text = String(data)
        
        // Format: +TEST: RXLRPKT, RSSI X, SNR Y, DATA "..."
        if (text.contains("RXLRPKT")) {
            val rssi = parseRssi(text)
            val snr = parseSnr(text)
            val payload = parsePayload(text)
            
            receiveCallback?.invoke(payload, rssi, snr)
            
            metrics = metrics.copy(
                rxCounter = metrics.rxCounter + 1,
                lastRssi = rssi,
                lastSnr = snr
            )
        }
    }
    
    private fun parseGenericResponse(data: ByteArray) {
        receiveCallback?.invoke(data, -100, 0f)
    }
    
    private fun detectModuleType(): ModuleType {
        // Détection basée sur VID/PID ou test de commande
        return when {
            usbDevice.getVendorId() == 0x2886 -> ModuleType.WIO_SX1262
            usbDevice.getVendorId() == 0x10C4 -> ModuleType.TBEAM
            else -> ModuleType.GENERIC_AT
        }
    }
    
    private fun parseRssi(response: String): Int {
        val regex = "RSSI (-?\\d+)".toRegex()
        return regex.find(response)?.groupValues?.get(1)?.toInt() ?: -100
    }
    
    private fun parseSnr(response: String): Float {
        val regex = "SNR (-?\\d+\\.?\\d*)".toRegex()
        return regex.find(response)?.groupValues?.get(1)?.toFloat() ?: 0f
    }
    
    private fun parsePayload(response: String): ByteArray {
        val regex = "DATA \"([0-9A-F]+)\"".toRegex()
        val hex = regex.find(response)?.groupValues?.get(1) ?: ""
        return hex.hexToBytes()
    }
    
    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02X".format(it) }
    }
    
    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    
    enum class ModuleType {
        WIO_SX1262,
        TBEAM,
        GENERIC_AT,
        BINARY
    }
}

/**
 * Classe utilitaire pour port USB Serial - utilise usb-serial-for-android
 * Cette classe est un wrapper simplifié, la vraie implémentation utilise
 * la librairie com.hoho.android.usbserial
 */
class UsbSerialPortWrapper(
    private val connection: android.hardware.usb.UsbDeviceConnection?,
    private val readEndpoint: android.hardware.usb.UsbEndpoint,
    private val writeEndpoint: android.hardware.usb.UsbEndpoint
) {
    fun open(baudRate: Int) {
        // Configuration du port (simplifié)
    }
    
    fun write(data: ByteArray, timeout: Int): Int {
        return connection?.bulkTransfer(writeEndpoint, data, data.size, timeout) ?: -1
    }
    
    fun read(buffer: ByteArray, timeout: Int): Int {
        return connection?.bulkTransfer(readEndpoint, buffer, buffer.size, timeout) ?: -1
    }
    
    fun close() {
        connection?.close()
    }
}
