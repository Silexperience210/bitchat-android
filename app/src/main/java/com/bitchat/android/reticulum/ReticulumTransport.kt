package com.bitchat.android.reticulum

import com.bitchat.android.transport.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transport Reticulum - Intégration native du protocole Reticulum
 * 
 * Permet à BitChat de communiquer avec le réseau Reticulum:
 * - Parse les packets Reticulum natifs
 * - Implémente le routing Reticulum (announces, paths)
 * - S'interface avec LoRaRadio pour la couche physique
 * 
 * Usage:
 * - Recevoir: T-Beam LoRa → parse Reticulum → afficher dans UI
 * - Envoyer: UI → format Reticulum → T-Beam LoRa
 */
@Singleton
class ReticulumTransport @Inject constructor(
    private val loraRadio: com.bitchat.android.transport.lora.LoRaRadio? = null
) : BitchatTransport {
    
    companion object {
        const val TAG = "ReticulumTransport"
        const val RETICULUM_MTU = 500
        const val ANNOUNCE_INTERVAL_MS = 300_000L  // 5 minutes
        const val PATH_EXPIRY_MS = 600_000L  // 10 minutes
    }
    
    override val name = "reticulum"
    override val bitrate = 1760  // LoRa SF9 BW125
    override val reliability = 0.75f
    
    override var isAvailable = false
        private set
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Identity
    private val identityHash = generateIdentity()
    private val destinationHash = identityHash  // Pour l'instant, same as identity
    
    // Routing tables
    private val forwardingTable = ConcurrentHashMap<String, PathEntry>()
    private val knownDestinations = ConcurrentHashMap<String, DestinationEntry>()
    
    // Callbacks
    private var receiveCallback: TransportCallback? = null
    private var isRunning = false
    
    // Stats
    private val _stats = MutableStateFlow(ReticulumStats())
    val stats: StateFlow<ReticulumStats> = _stats
    
    data class ReticulumStats(
        val packetsReceived: Long = 0,
        val packetsSent: Long = 0,
        val packetsRelayed: Long = 0,
        val announcesReceived: Long = 0,
        val pathsKnown: Int = 0,
        val isActive: Boolean = false
    )
    
    override fun start() {
        if (isRunning) return
        isRunning = true
        isAvailable = loraRadio != null
        
        // Start receiving from LoRa
        loraRadio?.startReceive { payload, rssi, snr ->
            handleLoRaReceive(payload, rssi, snr)
        }
        
        // Start periodic announces
        startAnnounceTimer()
        
        // Update stats
        _stats.value = _stats.value.copy(isActive = true)
        
        // Send initial announce
        scope.launch {
            sendAnnounce()
        }
    }
    
    override fun stop() {
        isRunning = false
        isAvailable = false
        scope.cancel()
        _stats.value = _stats.value.copy(isActive = false)
    }
    
    override suspend fun transmit(packet: TransportPacket): TransmitResult {
        if (!isAvailable) {
            return TransmitResult(
                success = false,
                queued = true,
                error = "Reticulum transport not available"
            )
        }
        
        return try {
            // Convert BitChat packet to Reticulum format
            val reticulumPacket = convertToReticulum(packet)
            
            // Serialize and transmit via LoRa
            val serialized = reticulumPacket.serialize()
            
            // Fragment if necessary
            if (serialized.size > 200) {  // LoRa MTU
                transmitFragmented(serialized)
            } else {
                loraRadio?.transmit(serialized)
            }
            
            _stats.value = _stats.value.copy(packetsSent = _stats.value.packetsSent + 1)
            
            TransmitResult(
                success = true,
                estimatedDeliveryTime = System.currentTimeMillis() + 1000
            )
        } catch (e: Exception) {
            TransmitResult(success = false, error = e.message)
        }
    }
    
    override fun setReceiveCallback(callback: TransportCallback) {
        this.receiveCallback = callback
    }
    
    override fun getMetrics(): TransportMetrics {
        return TransportMetrics(
            rtt = 500,
            lossRate = 0.25f,
            availableBandwidth = if (isAvailable) bitrate else 0,
            currentLoad = 0,
            peerCount = knownDestinations.size
        )
    }
    
    /**
     * Handle incoming LoRa data - parse as Reticulum
     */
    private fun handleLoRaReceive(payload: ByteArray, rssi: Int, snr: Float) {
        // Try to parse as Reticulum packet
        val packet = ReticulumPacket.parse(payload)
        
        if (packet != null) {
            handleReticulumPacket(packet, rssi, snr)
        } else {
            // Not a valid Reticulum packet, ignore
            // Could be BitChat native or other protocol
        }
    }
    
    /**
     * Process a valid Reticulum packet
     */
    private fun handleReticulumPacket(packet: ReticulumPacket, rssi: Int, snr: Float) {
        _stats.value = _stats.value.copy(packetsReceived = _stats.value.packetsReceived + 1)
        
        // Check if packet is for us or if we should relay
        val isForUs = packet.isForUs(destinationHash) || packet.isBroadcast()
        val shouldRelay = packet.hops < ReticulumProtocol.MAX_HOPS && !isFromUs(packet)
        
        when (packet.type) {
            ReticulumPacket.PacketType.ANNOUNCE -> {
                handleAnnounce(packet, rssi, snr)
            }
            
            ReticulumPacket.PacketType.DATA -> {
                if (isForUs) {
                    // Convert to BitChat format and deliver
                    val bitchatPacket = convertFromReticulum(packet)
                    val metadata = TransportMetadata(
                        transport = "reticulum",
                        rssi = rssi,
                        snr = snr,
                        timestamp = System.currentTimeMillis(),
                        hops = packet.hops
                    )
                    receiveCallback?.invoke(bitchatPacket, metadata)
                }
                
                if (shouldRelay && packet.hops < 8) {  // Relay limit
                    relayPacket(packet)
                }
            }
            
            ReticulumPacket.PacketType.LINK_REQUEST,
            ReticulumPacket.PacketType.LINK_PROOF -> {
                // Link layer - not fully implemented yet
            }
            
            ReticulumPacket.PacketType.PROOF -> {
                // Delivery proof
            }
        }
    }
    
    /**
     * Handle announce packet
     */
    private fun handleAnnounce(packet: ReticulumPacket, rssi: Int, snr: Float) {
        val announce = ReticulumAnnounce.parse(packet.payload) ?: return
        
        val destHash = announce.destinationHash.toHexString()
        knownDestinations[destHash] = DestinationEntry(
            hash = announce.destinationHash,
            publicKey = announce.publicKey,
            lastSeen = System.currentTimeMillis(),
            viaTransport = packet.transportId,
            hops = packet.hops,
            rssi = rssi,
            snr = snr
        )
        
        // Update forwarding table
        forwardingTable[destHash] = PathEntry(
            destination = destHash,
            nextHop = packet.transportId.toHexString(),
            hops = packet.hops,
            expiresAt = System.currentTimeMillis() + PATH_EXPIRY_MS
        )
        
        _stats.value = _stats.value.copy(
            announcesReceived = _stats.value.announcesReceived + 1,
            pathsKnown = knownDestinations.size
        )
    }
    
    /**
     * Relay a packet to other peers
     */
    private fun relayPacket(packet: ReticulumPacket) {
        if (!isRunning || !isAvailable) return
        
        val hopped = packet.hop()
        val serialized = hopped.serialize()
        
        scope.launch {
            try {
                if (serialized.size > 200) {
                    transmitFragmented(serialized)
                } else {
                    loraRadio?.transmit(serialized)
                }
                _stats.value = _stats.value.copy(packetsRelayed = _stats.value.packetsRelayed + 1)
            } catch (e: Exception) {
                // Relay failed
            }
        }
    }
    
    /**
     * Send announce packet
     */
    private suspend fun sendAnnounce() {
        if (!isAvailable) return
        
        val publicKey = ByteArray(32) { (it % 256).toByte() }  // Placeholder - should be real Ed25519 key
        
        val announce = ReticulumAnnounce(
            identityHash = identityHash,
            destinationHash = destinationHash,
            publicKey = publicKey,
            appData = "BitChat-Reticulum".toByteArray(Charsets.UTF_8)
        )
        
        val packet = announce.toPacket()
        val serialized = packet.serialize()
        
        try {
            if (serialized.size > 200) {
                transmitFragmented(serialized)
            } else {
                loraRadio?.transmit(serialized)
            }
        } catch (e: Exception) {
            // Failed to send announce
        }
    }
    
    /**
     * Start periodic announce timer
     */
    private fun startAnnounceTimer() {
        scope.launch {
            while (isActive) {
                delay(ANNOUNCE_INTERVAL_MS)
                if (isRunning) {
                    sendAnnounce()
                }
            }
        }
    }
    
    /**
     * Transmit large packets in fragments
     */
    private suspend fun transmitFragmented(data: ByteArray) {
        // Simple fragmentation - split into chunks
        val chunkSize = 200
        var offset = 0
        
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            loraRadio?.transmit(chunk)
            offset = end
            delay(100)  // Delay between fragments
        }
    }
    
    /**
     * Check if packet is from us
     */
    private fun isFromUs(packet: ReticulumPacket): Boolean {
        return packet.transportId.contentEquals(identityHash)
    }
    
    /**
     * Convert BitChat packet to Reticulum format
     */
    private fun convertToReticulum(packet: TransportPacket): ReticulumPacket {
        return ReticulumPacket(
            type = ReticulumPacket.PacketType.DATA,
            destinationType = if (packet.destinationHash.all { it == 0xFF.toByte() }) {
                ReticulumPacket.DestinationType.PLAIN
            } else {
                ReticulumPacket.DestinationType.SINGLE
            },
            hops = packet.hops,
            context = ReticulumProtocol.CONTEXT_NONE.toInt() and 0xFF,
            destinationHash = packet.destinationHash,
            transportId = identityHash,
            payload = packet.payload
        )
    }
    
    /**
     * Convert Reticulum packet to BitChat format
     */
    private fun convertFromReticulum(packet: ReticulumPacket): TransportPacket {
        return TransportPacket(
            sourceHash = packet.transportId,  // Use transport ID as source
            destinationHash = packet.destinationHash,
            payload = packet.payload,
            type = TransportPacket.PacketType.DATA,
            hops = packet.hops,
            ttl = ReticulumProtocol.MAX_HOPS - packet.hops,
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Generate identity hash
     */
    private fun generateIdentity(): ByteArray {
        val random = java.security.SecureRandom()
        val id = ByteArray(16)
        random.nextBytes(id)
        return id
    }
    
    // Data classes
    data class DestinationEntry(
        val hash: ByteArray,
        val publicKey: ByteArray,
        val lastSeen: Long,
        val viaTransport: ByteArray,
        val hops: Int,
        val rssi: Int,
        val snr: Float
    )
    
    data class PathEntry(
        val destination: String,
        val nextHop: String,
        val hops: Int,
        val expiresAt: Long
    )
}
