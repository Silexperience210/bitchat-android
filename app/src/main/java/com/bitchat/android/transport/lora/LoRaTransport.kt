package com.bitchat.android.transport.lora

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.bitchat.android.transport.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.PriorityBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transport LoRa pour BitChat
 * 
 * Supporte:
 * - Modules SX1262/LLCC68 via USB Serial (CH340/CP2102)
 * - Modules SPI via GPIO (sur appareils rootés ou Raspberry Pi)
 * - Duty cycle management (1% EU868)
 * - CSMA/CA (Listen Before Talk)
 * - Fragmentation automatique (MTU 237 bytes)
 * 
 * Hardware supporté:
 * - Wio-SX1262 (Seeed Studio) - USB
 * - LilyGo T-Beam - USB
 * - Heltec HT-CT62 - USB
 * - Modules DIY SX1262 + CH340
 */
@Singleton
class LoRaTransport @Inject constructor(
    private val context: Context
) : BitchatTransport {
    
    companion object {
        const val TAG = "LoRaTransport"
        
        // LoRa PHY Limits
        const val LORA_MAX_PAYLOAD = 237        // Bytes max par packet LoRa
        const val LORA_MTU = 200                // Bytes utiles (overhead protocol)
        
        // Fréquences EU868 (légales sans licence)
        const val FREQ_868_1 = 868_100_000L     // Canal 1
        const val FREQ_868_3 = 868_300_000L     // Canal 2  
        const val FREQ_868_5 = 868_500_000L     // Canal 3
        
        // Configuration par défaut (compromis portée/vitesse)
        const val DEFAULT_SPREADING_FACTOR = 9
        const val DEFAULT_BANDWIDTH = 125_000
        const val DEFAULT_CODING_RATE = 8       // 4/8
        const val DEFAULT_TX_POWER = 14         // dBm (25mW)
    }
    
    override val name = "lora"
    override var isAvailable = false
        private set
    
    // Débit variable selon configuration LoRa
    override val bitrate: Int
        get() = calculateBitrate(config.spreadingFactor, config.bandwidth)
    
    override val reliability = 0.75f          // LoRa moins fiable que BLE (pertes, collisions)
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Radio driver
    private var radio: LoRaRadio? = null
    
    // Configuration
    private val config = LoRaConfig()
    
    // Duty cycle manager (CRITIQUE pour légalité)
    private val dutyCycleManager = DutyCycleManager(DutyCycleManager.Region.EU868)
    
    // Fragmentation
    private val fragmenter = LoRaFragmenter(LORA_MTU)
    
    // File d'attente prioritaire
    private val transmitQueue = PriorityBlockingQueue<QueuedPacket>(
        100,
        compareBy { it.priority.ordinal }
    )
    
    // Callback pour réception
    private var receiveCallback: TransportCallback? = null
    
    // État du transport
    private val _state = MutableStateFlow(LoRaState.DISCONNECTED)
    val state: StateFlow<LoRaState> = _state
    
    enum class LoRaState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        TRANSMITTING,
        RECEIVING,
        ERROR
    }
    
    /**
     * Démarre le transport LoRa
     * Détecte et connecte le module automatiquement
     */
    override fun start() {
        scope.launch {
            _state.value = LoRaState.CONNECTING
            
            try {
                // 1. Détecter le module connecté
                val device = detectLoRaModule()
                    ?: throw LoRaException("No LoRa module detected")
                
                // 2. Initialiser le driver (T-Beam utilise CP2102)
                radio = TBeamLoRaRadio(context, device)
                
                // 3. Configurer la radio
                radio?.configure(
                    LoRaRadio.RadioConfig(
                        frequency = config.frequency,
                        spreadingFactor = config.spreadingFactor,
                        bandwidth = config.bandwidth,
                        codingRate = config.codingRate,
                        txPower = config.txPower,
                        preambleLength = 16
                    )
                )
                
                // 4. Démarrer la réception
                radio?.startReceive { payload, rssi, snr ->
                    handleReceivedPacket(payload, rssi, snr)
                }
                
                isAvailable = true
                _state.value = LoRaState.CONNECTED
                
                // 5. Démarrer le processeur de file d'attente
                startQueueProcessor()
                
                // 6. Envoyer announcement de présence
                sendAnnouncement()
                
            } catch (e: Exception) {
                _state.value = LoRaState.ERROR
                isAvailable = false
                throw e
            }
        }
    }
    
    /**
     * Arrête proprement le transport
     */
    override fun stop() {
        scope.cancel()
        radio?.stop()
        isAvailable = false
        _state.value = LoRaState.DISCONNECTED
    }
    
    /**
     * Transmet un packet via LoRa
     * 
     * Process:
     * 1. Vérifier duty cycle
     * 2. Fragmenter si nécessaire
     * 3. CSMA/CA (écouter avant parler)
     * 4. Transmettre avec retry
     */
    override suspend fun transmit(packet: TransportPacket): TransmitResult {
        if (!isAvailable) {
            return TransmitResult(
                success = false,
                queued = true,
                error = "LoRa not connected"
            )
        }
        
        // Calculer la taille totale (avec overhead fragmentation)
        val totalSize = if (packet.payload.size > LORA_MTU) {
            // Sera fragmenté
            fragmenter.calculateTotalSize(packet.payload.size)
        } else {
            packet.payload.size + 20 // overhead header
        }
        
        // Vérifier duty cycle
        val backoff = dutyCycleManager.getBackoffTime(totalSize, config.spreadingFactor)
        if (backoff > 0) {
            // Mettre en queue pour plus tard
            queuePacket(packet, Priority.NORMAL, backoff)
            return TransmitResult(
                success = false,
                queued = true,
                estimatedDeliveryTime = System.currentTimeMillis() + backoff,
                error = "Duty cycle limit, queued for ${backoff}ms"
            )
        }
        
        return try {
            // Fragmenter si nécessaire
            val fragments = fragmenter.fragment(packet)
            
            // Transmettre chaque fragment
            for (fragment in fragments) {
                // CSMA/CA - attendre canal libre
                if (!performCAD()) {
                    // Canal occupé, requeue tout le packet
                    val retryDelay = (100..1000).random().toLong()
                    queuePacket(packet, Priority.NORMAL, retryDelay)
                    return TransmitResult(
                        success = false,
                        queued = true,
                        estimatedDeliveryTime = System.currentTimeMillis() + retryDelay,
                        error = "Channel busy"
                    )
                }
                
                // Transmettre
                _state.value = LoRaState.TRANSMITTING
                val airtime = transmitFragment(fragment)
                _state.value = LoRaState.CONNECTED
                
                // Logger pour duty cycle
                dutyCycleManager.logTransmission(airtime)
                
                // Petit délai entre fragments
                if (fragments.size > 1) {
                    delay(airtime + 50)
                }
            }
            
            TransmitResult(
                success = true,
                estimatedDeliveryTime = System.currentTimeMillis() + 
                    calculateTotalAirtime(fragments.size)
            )
            
        } catch (e: Exception) {
            _state.value = LoRaState.ERROR
            TransmitResult(
                success = false,
                error = e.message
            )
        }
    }
    
    override fun setReceiveCallback(callback: TransportCallback) {
        this.receiveCallback = callback
    }
    
    override fun getMetrics(): TransportMetrics {
        val radioMetrics = radio?.getMetrics()
        
        return TransportMetrics(
            rtt = radioMetrics?.rtt ?: 1000L,
            lossRate = 0.2f, // Estimation
            availableBandwidth = if (isAvailable) {
                (bitrate * (1 - dutyCycleManager.getCurrentUtilization())).toInt()
            } else 0,
            currentLoad = transmitQueue.size,
            peerCount = 0 // TODO: Lora peer discovery
        )
    }
    
    /**
     * Détecte automatiquement un module LoRa USB connecté
     */
    private fun detectLoRaModule(): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        
        // VID/PID connus des modules LoRa courants
        val knownDevices = mapOf(
            // Wio-SX1262
            0x2886 to 0x802F,
            // LilyGo T-Beam (CP2102)
            0x10C4 to 0xEA60,
            // CH340
            0x1A86 to 0x7523,
            // FTDI
            0x0403 to 0x6001
        )
        
        usbManager.deviceList.values.forEach { device ->
            if (knownDevices.containsKey(device.vendorId) &&
                knownDevices[device.vendorId] == device.productId) {
                return device
            }
        }
        
        return null
    }
    
    /**
     * Channel Activity Detection
     * Vérifie si le canal est libre avant transmission
     */
    private suspend fun performCAD(): Boolean {
        val radio = this.radio ?: return false
        
        // Attendre max 1 seconde que le canal soit libre
        repeat(10) {
            if (radio.isChannelFree(config.frequency, -120)) {
                return true
            }
            delay(100)
        }
        
        return false
    }
    
    /**
     * Transmet un fragment unique
     * @return airtime en ms
     */
    private fun transmitFragment(fragment: LoRaFragment): Long {
        val bytes = fragment.serialize()
        val airtime = calculateAirtime(bytes.size, config.spreadingFactor, config.bandwidth)
        
        radio?.transmit(bytes)
        
        return airtime
    }
    
    /**
     * Gère un packet reçu du module LoRa
     */
    private fun handleReceivedPacket(payload: ByteArray, rssi: Int, snr: Float) {
        _state.value = LoRaState.RECEIVING
        
        try {
            // Défragmenter si nécessaire
            val fragment = LoRaFragment.deserialize(payload)
            val packet = fragmenter.defragment(fragment)
            
            packet?.let {
                val metadata = TransportMetadata(
                    transport = "lora",
                    rssi = rssi,
                    snr = snr,
                    timestamp = System.currentTimeMillis(),
                    hops = it.hops
                )
                
                receiveCallback?.invoke(it, metadata)
            }
            
        } catch (e: Exception) {
            // Packet invalide, ignorer
        } finally {
            _state.value = LoRaState.CONNECTED
        }
    }
    
    /**
     * Processeur de file d'attente
     */
    private fun startQueueProcessor() {
        scope.launch {
            while (isActive) {
                val queued = transmitQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                
                if (queued != null && queued.dueTime <= System.currentTimeMillis()) {
                    val result = transmit(queued.packet)
                    
                    if (!result.success && queued.retryCount < 3) {
                        // Retry
                        queuePacket(
                            queued.packet,
                            queued.priority,
                            5000,
                            queued.retryCount + 1
                        )
                    }
                }
            }
        }
    }
    
    private fun queuePacket(
        packet: TransportPacket,
        priority: Priority,
        delay: Long,
        retryCount: Int = 0
    ) {
        transmitQueue.offer(QueuedPacket(
            packet = packet,
            priority = priority,
            dueTime = System.currentTimeMillis() + delay,
            retryCount = retryCount
        ))
    }
    
    private suspend fun sendAnnouncement() {
        val announcePacket = TransportPacket(
            sourceHash = ByteArray(16), // TODO: identity hash
            destinationHash = ByteArray(16) { 0xFF.toByte() },
            payload = byteArrayOf(),
            type = TransportPacket.PacketType.ANNOUNCE,
            ttl = 1
        )
        transmit(announcePacket)
    }
    
    /**
     * Calcule le débit LoRa en bps
     */
    private fun calculateBitrate(sf: Int, bw: Int): Int {
        // Formule: SF * BW / 2^SF * CR
        // Simplifié pour estimation
        return when (sf) {
            7 -> 5470
            8 -> 3125
            9 -> 1760
            10 -> 980
            11 -> 540
            12 -> 290
            else -> 1760
        }
    }
    
    /**
     * Calcule le temps d'air (airtime) pour une transmission
     */
    private fun calculateAirtime(payloadBytes: Int, sf: Int, bw: Int): Long {
        // Symbole rate = BW / 2^SF
        val tsym = 1000.0 * (1 shl sf) / bw  // ms par symbole
        
        // Preambule: 16 symboles
        val preambleTime = 16 * tsym
        
        // Payload symbols (approximation)
        val payloadSym = 8 + kotlin.math.ceil(
            (8 * payloadBytes - 4 * sf + 28 + 16) / (4.0 * sf)
        ) * 4  // CR = 4/8
        
        val payloadTime = payloadSym * tsym
        
        return (preambleTime + payloadTime).toLong()
    }
    
    private fun calculateTotalAirtime(fragments: Int): Long {
        // Approximation
        return fragments * calculateAirtime(LORA_MTU, config.spreadingFactor, config.bandwidth)
    }
    
    data class QueuedPacket(
        val packet: TransportPacket,
        val priority: Priority,
        val dueTime: Long,
        val retryCount: Int = 0
    )
    
    data class LoRaConfig(
        var frequency: Long = FREQ_868_1,
        var spreadingFactor: Int = DEFAULT_SPREADING_FACTOR,
        var bandwidth: Int = DEFAULT_BANDWIDTH,
        var codingRate: Int = DEFAULT_CODING_RATE,
        var txPower: Int = DEFAULT_TX_POWER
    )
    
    data class RadioMetrics(
        val rtt: Long = 1000,
        val packetLossRate: Float = 0.25f
    )
    
    class LoRaException(message: String) : Exception(message)
}
