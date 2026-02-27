package com.bitchat.android.transport.ble

import android.content.Context
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.transport.api.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transport BLE - Adapter pour BluetoothMeshService existant
 * 
 * Cette classe fait le pont entre ton BLE existant et la nouvelle
 * architecture transport-agnostic.
 */
@Singleton
class BLETransport @Inject constructor(
    private val context: Context
) : BitchatTransport {
    
    override val name = "ble"
    override val bitrate = 2_000_000 // 2 Mbps
    override val reliability = 0.95f
    
    // Service BLE existant
    private lateinit var meshService: BluetoothMeshService
    
    private var isStarted = false
    private var receiveCallback: TransportCallback? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val connectedPeers = CopyOnWriteArrayList<String>()
    
    override val isAvailable: Boolean
        get() = isStarted
    
    override fun start() {
        if (isStarted) return
        
        // Initialiser le service BLE existant
        meshService = BluetoothMeshService(context)
        
        // Configurer le listener
        setupMeshListener()
        
        // Démarrer
        // Note: Le démarrage réel se fait via les méthodes existantes
        isStarted = true
        
        // Démarrer les tâches périodiques
        startPeriodicTasks()
    }
    
    override fun stop() {
        isStarted = false
        scope.cancel()
        // Note: Le service s'arrête via ses propres mécanismes
    }
    
    override suspend fun transmit(packet: TransportPacket): TransmitResult {
        if (!isAvailable) {
            return TransmitResult(
                success = false,
                queued = true,
                error = "BLE not available"
            )
        }
        
        return try {
            // Convertir TransportPacket -> BitchatPacket legacy
            val legacyPacket = adaptToLegacy(packet)
            
            // Utiliser le broadcast existant
            meshService.connectionManager.broadcastPacket(
                com.bitchat.android.model.RoutedPacket(legacyPacket)
            )
            
            TransmitResult(
                success = true,
                estimatedDeliveryTime = System.currentTimeMillis() + 50
            )
        } catch (e: Exception) {
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
        return TransportMetrics(
            rtt = 50,
            lossRate = 0.05f,
            availableBandwidth = if (isAvailable) bitrate else 0,
            currentLoad = 0,
            peerCount = connectedPeers.size
        )
    }
    
    /**
     * Configure le listener sur le BluetoothMeshService existant
     */
    private fun setupMeshListener() {
        meshService.delegate = object : BluetoothMeshDelegate {
            override fun didReceiveMessage(message: com.bitchat.android.model.BitchatMessage) {
                // Convertir le message en TransportPacket
                val packet = TransportPacket(
                    sourceHash = message.sender.toByteArray(Charsets.UTF_8).copyOf(16),
                    destinationHash = ByteArray(16) { 0xFF.toByte() },
                    payload = message.content.toByteArray(Charsets.UTF_8),
                    type = TransportPacket.PacketType.DATA,
                    timestamp = message.timestamp.time
                )
                
                val metadata = TransportMetadata(
                    transport = "ble",
                    timestamp = System.currentTimeMillis()
                )
                
                receiveCallback?.invoke(packet, metadata)
            }
            
            override fun didUpdatePeerList(peerIDs: List<String>) {
                connectedPeers.clear()
                connectedPeers.addAll(peerIDs)
            }
            
            override fun didReceiveChannelLeave(channel: String, fromPeer: String) {}
            override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {}
            override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {}
            override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {}
            override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {}
            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
            override fun getNickname(): String? = null
            override fun isFavorite(peerID: String): Boolean = false
        }
    }
    
    /**
     * Tâches périodiques (keepalive, announce, etc.)
     */
    private fun startPeriodicTasks() {
        scope.launch {
            while (isActive) {
                delay(30000) // Toutes les 30 secondes
                
                if (isAvailable) {
                    // Envoyer announcement périodique
                    sendAnnouncement()
                }
            }
        }
    }
    
    /**
     * Envoie un announcement de présence
     */
    private suspend fun sendAnnouncement() {
        val announcePacket = TransportPacket(
            sourceHash = getMyIdentityHash(),
            destinationHash = ByteArray(16) { 0xFF.toByte() }, // Broadcast
            payload = byteArrayOf(), // Payload vide pour announce
            type = TransportPacket.PacketType.ANNOUNCE,
            hops = 0,
            ttl = 1 // Announce reste local
        )
        
        transmit(announcePacket)
    }
    
    /**
     * Adapte TransportPacket vers BitchatPacket existant
     * 
     * Mapping:
     * - v2 = nouveau format avec support transport-agnostic
     * - Garde la compatibilité avec iOS existant
     */
    private fun adaptToLegacy(packet: TransportPacket): BitchatPacket {
        return BitchatPacket(
            version = 2u, // v2 = nouveau format
            type = when (packet.type) {
                TransportPacket.PacketType.ANNOUNCE -> MessageType.ANNOUNCE.value
                TransportPacket.PacketType.DATA -> MessageType.MESSAGE.value
                TransportPacket.PacketType.HANDSHAKE -> MessageType.NOISE_HANDSHAKE.value
                TransportPacket.PacketType.ACK -> MessageType.LEAVE.value
                TransportPacket.PacketType.FRAGMENT -> MessageType.FRAGMENT.value
            },
            senderID = packet.sourceHash.copyOfRange(0, 8), // Truncate à 8 bytes
            recipientID = if (isBroadcast(packet.destinationHash)) {
                null
            } else {
                packet.destinationHash.copyOfRange(0, 8)
            },
            timestamp = packet.timestamp.toULong(),
            payload = packet.payload,
            ttl = packet.ttl.toUByte(),
            signature = null,
            route = null
        )
    }
    
    /**
     * Adapte depuis BitchatPacket existant
     */
    private fun adaptFromLegacy(legacy: BitchatPacket): TransportPacket {
        return TransportPacket(
            sourceHash = legacy.senderID + ByteArray(8), // Pad à 16 bytes
            destinationHash = legacy.recipientID ?: ByteArray(16) { 0xFF.toByte() },
            payload = legacy.payload,
            type = when (legacy.type.toInt()) {
                0x01 -> TransportPacket.PacketType.ANNOUNCE
                0x02 -> TransportPacket.PacketType.DATA
                0x10, 0x11 -> TransportPacket.PacketType.HANDSHAKE
                0x03 -> TransportPacket.PacketType.ACK
                0x20 -> TransportPacket.PacketType.FRAGMENT
                else -> TransportPacket.PacketType.DATA
            },
            hops = 0, // Inconnu depuis legacy
            ttl = legacy.ttl.toInt(),
            timestamp = legacy.timestamp.toLong()
        )
    }
    
    private fun isBroadcast(address: ByteArray): Boolean {
        return address.all { it == 0xFF.toByte() }
    }
    
    private fun getMyIdentityHash(): ByteArray {
        // Récupérer depuis le mesh service
        return meshService.myPeerID.toByteArray(Charsets.UTF_8).copyOf(16)
    }
}
