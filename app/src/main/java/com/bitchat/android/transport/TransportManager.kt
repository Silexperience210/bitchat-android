package com.bitchat.android.transport

import com.bitchat.android.transport.api.*
import com.bitchat.android.transport.ble.BLETransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transport Manager - Point central d'accès aux transports
 * 
 * Responsabilités:
 * - Gérer la liste des transports disponibles
 * - Router les packets vers le bon transport
 * - Agréger les statuts
 * - Déduplication des packets
 */
@Singleton
class TransportManager @Inject constructor(
    private val bleTransport: BLETransport,
    // private val loraTransport: LoRaTransport? = null  // Ajouté plus tard
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /** Liste des transports actifs */
    private val transports = mutableListOf<BitchatTransport>(bleTransport)
    
    /** Status agrégé de tous les transports */
    private val _status = MutableStateFlow(TransportManagerStatus())
    val status: StateFlow<TransportManagerStatus> = _status.asStateFlow()
    
    /** Handler pour packets entrants */
    private var packetHandler: ((TransportPacket, TransportMetadata) -> Unit)? = null
    
    /** Cache pour déduplication (packetId -> timestamp) */
    private val seenPackets = ConcurrentHashMap<String, Long>()
    private val DEDUP_WINDOW_MS = 60_000 // 1 minute
    
    /** File d'attente pour store & forward */
    private val pendingPackets = mutableListOf<PendingPacket>()
    
    init {
        // Configurer les callbacks sur tous les transports
        setupTransportCallbacks()
        
        // Démarrer le nettoyage périodique
        startMaintenance()
    }
    
    /**
     * Démarre tous les transports
     */
    fun startAll() {
        transports.forEach { transport ->
            try {
                transport.start()
            } catch (e: Exception) {
                // Logger l'erreur mais continuer avec les autres
                e.printStackTrace()
            }
        }
        updateStatus()
    }
    
    /**
     * Arrête tous les transports
     */
    fun stopAll() {
        transports.forEach { it.stop() }
        scope.cancel()
    }
    
    /**
     * Envoie un packet sur le meilleur transport disponible
     */
    suspend fun send(packet: TransportPacket): TransmitResult {
        // Vérifier si c'est un doublon
        if (isDuplicate(packet)) {
            return TransmitResult(success = false, error = "Duplicate packet")
        }
        
        // Marquer comme vu
        seenPackets[packet.packetId] = System.currentTimeMillis()
        
        // Stratégie de routing simple pour l'instant:
        // - Si hops == 0 et BLE disponible -> BLE
        // - Sinon -> Premier transport disponible
        val transport = selectTransport(packet)
            ?: return TransmitResult(
                success = false, 
                queued = true,
                error = "No transport available, queued for later"
            )
        
        return try {
            val result = transport.transmit(packet)
            
            // Si échec mais reliable, mettre en file d'attente
            if (!result.success && packet.reliable) {
                queuePacket(packet)
            }
            
            result
        } catch (e: Exception) {
            if (packet.reliable) {
                queuePacket(packet)
            }
            TransmitResult(success = false, error = e.message)
        }
    }
    
    /**
     * Broadcast sur tous les transports actifs
     */
    suspend fun broadcast(packet: TransportPacket) {
        seenPackets[packet.packetId] = System.currentTimeMillis()
        
        transports.filter { it.isAvailable }.forEach { transport ->
            try {
                transport.transmit(packet)
            } catch (e: Exception) {
                // Log mais continuer avec les autres transports
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Enregistre un handler pour les packets entrants
     */
    fun setPacketHandler(handler: (TransportPacket, TransportMetadata) -> Unit) {
        this.packetHandler = handler
    }
    
    /**
     * Retourne un transport par nom
     */
    fun getTransport(name: String): BitchatTransport? {
        return transports.find { it.name == name }
    }
    
    /**
     * Ajoute un transport dynamiquement (ex: LoRa branché en USB)
     */
    fun addTransport(transport: BitchatTransport) {
        transport.setReceiveCallback { packet, metadata ->
            handleIncomingPacket(packet, metadata)
        }
        transports.add(transport)
        transport.start()
        updateStatus()
    }
    
    /**
     * Retire un transport
     */
    fun removeTransport(name: String) {
        val removed = transports.filter { it.name == name }
        transports.removeAll { it.name == name }
        removed.forEach { it.stop() }
        updateStatus()
    }
    
    /**
     * Sélectionne le meilleur transport pour un packet
     */
    private fun selectTransport(packet: TransportPacket): BitchatTransport? {
        val available = transports.filter { it.isAvailable }
        
        if (available.isEmpty()) return null
        
        // TODO: Logique de routing intelligente basée sur:
        // - Destination connue sur quel transport?
        // - Métriques de qualité
        // - Type de message (urgent vs batch)
        // - Distance estimée (hops)
        
        return available.firstOrNull { it.name == "ble" } ?: available.first()
    }
    
    /**
     * Gère un packet entrant
     */
    private fun handleIncomingPacket(packet: TransportPacket, metadata: TransportMetadata) {
        // Déduplication
        if (isDuplicate(packet)) {
            return
        }
        seenPackets[packet.packetId] = System.currentTimeMillis()
        
        // Propager au handler
        packetHandler?.invoke(packet, metadata)
        
        // Si c'est un broadcast et qu'on est en mesh, retransmettre
        if (isBroadcast(packet.destinationHash) && packet.hops < packet.ttl) {
            relayPacket(packet, metadata.transport)
        }
    }
    
    /**
     * Relaye un packet (mesh flooding contrôlé)
     */
    private fun relayPacket(packet: TransportPacket, receivedOn: String) {
        scope.launch {
            val relayPacket = packet.copy(
                hops = packet.hops + 1,
                ttl = packet.ttl - 1
            )
            
            // Ne pas renvoyer sur le même transport (éviter boucle)
            transports
                .filter { it.isAvailable && it.name != receivedOn }
                .forEach { transport ->
                    try {
                        transport.transmit(relayPacket)
                    } catch (e: Exception) {
                        // Ignorer les erreurs de relay
                    }
                }
        }
    }
    
    /**
     * Vérifie si un packet est un doublon
     */
    private fun isDuplicate(packet: TransportPacket): Boolean {
        val lastSeen = seenPackets[packet.packetId]
        return lastSeen != null && 
               (System.currentTimeMillis() - lastSeen) < DEDUP_WINDOW_MS
    }
    
    /**
     * Vérifie si c'est une adresse de broadcast
     */
    private fun isBroadcast(address: ByteArray): Boolean {
        return address.all { it == 0xFF.toByte() }
    }
    
    /**
     * Met un packet en file d'attente pour retry
     */
    private fun queuePacket(packet: TransportPacket) {
        pendingPackets.add(PendingPacket(
            packet = packet,
            queuedAt = System.currentTimeMillis(),
            retryCount = 0
        ))
    }
    
    /**
     * Configure les callbacks sur tous les transports
     */
    private fun setupTransportCallbacks() {
        transports.forEach { transport ->
            transport.setReceiveCallback { packet, metadata ->
                handleIncomingPacket(packet, metadata)
            }
        }
    }
    
    /**
     * Met à jour le status agrégé
     */
    private fun updateStatus() {
        val metrics = transports.map { it.getMetrics() }
        
        _status.value = TransportManagerStatus(
            bleActive = transports.find { it.name == "ble" }?.isAvailable ?: false,
            blePeers = metrics.find { it.peerCount > 0 }?.peerCount ?: 0,
            loraActive = false, // TODO
            loraPeers = 0,
            totalBandwidth = metrics.sumOf { it.availableBandwidth },
            pendingPackets = pendingPackets.size
        )
    }
    
    /**
     * Démarrage des tâches de maintenance
     */
    private fun startMaintenance() {
        scope.launch {
            while (isActive) {
                delay(5000) // Toutes les 5 secondes
                
                // Nettoyer les vieux packets vus
                val now = System.currentTimeMillis()
                seenPackets.entries.removeIf { 
                    (now - it.value) > DEDUP_WINDOW_MS 
                }
                
                // Retry les packets en attente
                retryPendingPackets()
                
                // Mettre à jour le status
                updateStatus()
            }
        }
    }
    
    /**
     * Retry les packets en attente
     */
    private suspend fun retryPendingPackets() {
        val iterator = pendingPackets.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            
            // Max 3 retries
            if (pending.retryCount >= 3) {
                iterator.remove()
                continue
            }
            
            // Attendre au moins 5s entre les retries
            if (System.currentTimeMillis() - pending.queuedAt < 5000) {
                continue
            }
            
            val result = send(pending.packet)
            if (result.success) {
                iterator.remove()
            } else {
                pending.retryCount++
                pending.queuedAt = System.currentTimeMillis()
            }
        }
    }
    
    data class PendingPacket(
        val packet: TransportPacket,
        var queuedAt: Long,
        var retryCount: Int
    )
}

/**
 * Status agrégé du TransportManager
 */
data class TransportManagerStatus(
    val bleActive: Boolean = false,
    val blePeers: Int = 0,
    val bleQuality: Float = 0f,
    val loraActive: Boolean = false,
    val loraPeers: Int = 0,
    val loraQuality: Float = 0f,
    val totalBandwidth: Int = 0,
    val pendingPackets: Int = 0
)
