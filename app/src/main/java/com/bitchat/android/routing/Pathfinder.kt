package com.bitchat.android.routing

import com.bitchat.android.transport.api.BitchatTransport
import com.bitchat.android.transport.api.TransportMetadata
import com.bitchat.android.transport.api.TransportPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import kotlin.math.pow

/**
 * Pathfinder - Système de routage intelligent multi-transport
 * 
 * Inspiré de Reticulum mais simplifié pour BitChat:
 * - Distance-vector routing (tables de voisins)
 * - Link-state local (métriques de qualité)
 * - Transport selection algorithm
 * - Multi-path support (redondance)
 * 
 * Le Pathfinder maintient une vue du réseau et choisit le meilleur
 * chemin pour chaque destination, potentiellement en utilisant
 * plusieurs transports (BLE pour local, LoRa pour distance).
 */
class Pathfinder(
    private val transports: List<BitchatTransport>,
    private val myIdentityHash: ByteArray,
    private val scope: CoroutineScope
) {
    companion object {
        const val TAG = "Pathfinder"
        
        // Timeouts
        const val PATH_EXPIRY_MS = 5 * 60 * 1000L      // 5 minutes
        const val ANNOUNCE_INTERVAL_MS = 30_000L       // 30 secondes
        const val METRIC_UPDATE_INTERVAL_MS = 10_000L  // 10 secondes
        
        // Poids pour le score composite
        const val WEIGHT_LATENCY = 0.25f
        const val WEIGHT_RELIABILITY = 0.25f
        const val WEIGHT_BANDWIDTH = 0.20f
        const val WEIGHT_ENERGY = 0.15f
        const val WEIGHT_HOPS = 0.15f
    }
    
    // Table de forwarding: destination hash -> meilleur chemin
    private val forwardingTable = ConcurrentHashMap<String, PathEntry>()
    
    // Table des voisins directs (ceux qu'on voit en BLE/LoRa direct)
    private val neighborTable = ConcurrentHashMap<String, NeighborEntry>()
    
    // Métriques de liens (lien identifié par paire de hashes)
    private val linkMetrics = ConcurrentHashMap<String, LinkMetrics>()
    
    // Historique de transmission pour estimation des métriques
    private val transmissionHistory = ConcurrentHashMap<String, MutableList<TransmissionRecord>>()
    
    // État du pathfinder
    private val _state = MutableStateFlow(PathfinderState())
    val state: StateFlow<PathfinderState> = _state
    
    data class PathfinderState(
        val knownDestinations: Int = 0,
        val directNeighbors: Int = 0,
        val totalHopsLearned: Int = 0,
        val averagePathQuality: Float = 0f
    )
    
    /**
     * Démarrage du Pathfinder
     */
    fun start() {
        // Démarrer les tâches périodiques
        startPeriodicTasks()
        
        // S'abonner aux annonces de présence
        startListeningForAnnounces()
    }
    
    /**
     * Trouve le meilleur chemin vers une destination
     * 
     * @param destination Hash de la destination (16 bytes)
     * @param constraints Contraintes de routage (bande passante min, etc.)
     * @return Liste des chemins possibles (vide si inconnue)
     */
    fun findPath(
        destination: ByteArray,
        constraints: PathConstraints = PathConstraints()
    ): List<Path> {
        val destKey = destination.toHex()
        
        // Vérifier table de forwarding
        val paths = forwardingTable[destKey]?.let { listOf(it.toPath()) } ?: emptyList()
        
        // Filtrer selon contraintes
        return paths.filter { path ->
            meetsConstraints(path, constraints)
        }.sortedBy { it.metric.compositeScore() }
    }
    
    /**
     * Sélectionne le meilleur transport pour une destination
     * C'est le cœur de la logique de routing
     */
    fun selectTransport(
        destination: ByteArray,
        packetSize: Int = 0,
        urgency: Urgency = Urgency.NORMAL
    ): TransportSelection? {
        val paths = findPath(destination)
        
        if (paths.isEmpty()) {
            // Pas de chemin connu -> flood sur tous les transports
            return TransportSelection(
                strategy = RoutingStrategy.FLOOD,
                transports = transports.filter { it.isAvailable },
                estimatedSuccessRate = 0.5f
            )
        }
        
        val bestPath = paths.first()
        
        // Décider de la stratégie selon la qualité du chemin
        return when {
            // Chemin excellent -> unicast direct
            bestPath.metric.reliability > 0.9 && bestPath.hops <= 2 -> {
                TransportSelection(
                    strategy = RoutingStrategy.UNICAST,
                    primaryTransport = getTransport(bestPath.transport),
                    fallbackTransports = emptyList(),
                    nextHop = bestPath.nextHop,
                    estimatedSuccessRate = bestPath.metric.reliability,
                    estimatedLatency = bestPath.metric.latency
                )
            }
            
            // Chemin moyen -> unicast avec fallback
            bestPath.metric.reliability > 0.6 -> {
                TransportSelection(
                    strategy = RoutingStrategy.UNICAST_WITH_FALLBACK,
                    primaryTransport = getTransport(bestPath.transport),
                    fallbackTransports = getAlternativeTransports(bestPath.transport),
                    nextHop = bestPath.nextHop,
                    estimatedSuccessRate = bestPath.metric.reliability,
                    estimatedLatency = bestPath.metric.latency
                )
            }
            
            // Chemin faible ou urgent -> multi-transport (parallèle)
            urgency == Urgency.CRITICAL || bestPath.metric.reliability < 0.4 -> {
                TransportSelection(
                    strategy = RoutingStrategy.MULTI_TRANSPORT,
                    transports = transports.filter { it.isAvailable },
                    estimatedSuccessRate = 1 - (1 - bestPath.metric.reliability).pow(2),
                    estimatedLatency = bestPath.metric.latency
                )
            }
            
            // Défaut
            else -> TransportSelection(
                strategy = RoutingStrategy.UNICAST,
                primaryTransport = getTransport(bestPath.transport),
                nextHop = bestPath.nextHop,
                estimatedSuccessRate = bestPath.metric.reliability
            )
        }
    }
    
    /**
     * Met à jour les métriques après une transmission
     */
    fun updateMetrics(
        destination: ByteArray,
        transport: String,
        success: Boolean,
        rtt: Long,
        metadata: TransportMetadata?
    ) {
        val linkKey = "${transport}_${destination.toHex()}"
        
        // Ajouter au historique
        val history = transmissionHistory.getOrPut(linkKey) { mutableListOf() }
        history.add(TransmissionRecord(
            timestamp = System.currentTimeMillis(),
            success = success,
            rtt = rtt
        ))
        
        // Garder seulement les 100 derniers
        if (history.size > 100) history.removeAt(0)
        
        // Recalculer les métriques
        val reliability = history.count { it.success }.toFloat() / history.size
        val avgRtt = history.filter { it.success }.map { it.rtt }.average().toLong()
        
        linkMetrics[linkKey] = LinkMetrics(
            reliability = reliability,
            latency = avgRtt,
            lastUpdated = System.currentTimeMillis()
        )
        
        // Mettre à jour la table de forwarding
        updateForwardingTable(destination.toHex(), transport, reliability, avgRtt)
    }
    
    /**
     * Met à jour la table de forwarding avec de nouvelles métriques
     */
    private fun updateForwardingTable(
        destKey: String,
        transport: String,
        reliability: Float,
        latency: Long
    ) {
        val existing = forwardingTable[destKey]
        val now = System.currentTimeMillis()
        
        if (existing != null) {
            // Mettre à jour les métriques existantes
            forwardingTable[destKey] = existing.copy(
                metric = existing.metric.copy(
                    reliability = reliability,
                    latency = latency
                ),
                expiresAt = now + PATH_EXPIRY_MS
            )
        }
    }
    
    /**
     * Traite une annonce de présence reçue
     */
    fun handleAnnouncement(
        from: ByteArray,
        transport: String,
        metadata: TransportMetadata,
        announcedPaths: List<AnnouncedPath> = emptyList()
    ) {
        val neighborKey = from.toHex()
        val now = System.currentTimeMillis()
        
        // Mettre à jour le voisin
        neighborTable[neighborKey] = NeighborEntry(
            identityHash = from,
            transport = transport,
            lastSeen = now,
            directLink = true,
            hops = 0
        )
        
        // Mettre à jour métriques du lien direct
        val linkKey = "${transport}_${neighborKey}"
        linkMetrics[linkKey] = LinkMetrics(
            reliability = 0.95f,  // Direct = fiable
            latency = metadata.linkLatency ?: 50,
            rssi = metadata.rssi,
            snr = metadata.snr,
            lastUpdated = now
        )
        
        // Mettre à jour notre propre entrée dans forwarding table
        forwardingTable[neighborKey] = PathEntry(
            destination = from,
            nextHop = from,  // Direct
            transport = transport,
            hops = 0,
            metric = PathMetric(
                latency = metadata.linkLatency ?: 50,
                reliability = 0.95f,
                bandwidth = getTransport(transport)?.bitrate ?: 0,
                energyCost = 1.0f,
                hops = 0
            ),
            expiresAt = now + PATH_EXPIRY_MS
        )
        
        // Traiter les paths annoncés par ce voisin (distance-vector)
        announcedPaths.forEach { announced ->
            val destKey = announced.destination.toHex()
            val existing = forwardingTable[destKey]
            
            // Métriques annoncées + coût du lien jusqu'au voisin
            val newMetric = announced.metric.copy(
                hops = announced.hops + 1,
                latency = announced.metric.latency + (metadata.linkLatency ?: 50),
                reliability = announced.metric.reliability * 0.95f  // Dégradation
            )
            
            // Mettre à jour si meilleur ou inexistant
            if (existing == null || 
                existing.expiresAt < now ||
                newMetric.compositeScore() < existing.metric.compositeScore()
            ) {
                forwardingTable[destKey] = PathEntry(
                    destination = announced.destination,
                    nextHop = from,  // Via ce voisin
                    transport = transport,
                    hops = announced.hops + 1,
                    metric = newMetric,
                    expiresAt = now + PATH_EXPIRY_MS
                )
            }
        }
        
        updateState()
    }
    
    /**
     * Crée une annonce de nos propres paths pour diffusion
     */
    fun createAnnouncement(): Announcement {
        // Sélectionner les meilleurs paths à annoncer
        val pathsToAnnounce = forwardingTable.values
            .filter { it.hops < 3 }  // Pas annoncer les paths trop longs
            .map { AnnouncedPath(
                destination = it.destination,
                hops = it.hops,
                metric = it.metric
            ) }
        
        return Announcement(
            identity = myIdentityHash,
            paths = pathsToAnnounce,
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Décide si on doit retransmettre un packet (mesh relay)
     */
    fun shouldRelay(packet: TransportPacket, receivedOn: String): Boolean {
        // Vérifier TTL
        if (packet.hops >= packet.ttl) return false
        
        // Vérifier si on a un meilleur chemin
        val paths = findPath(packet.destinationHash)
        if (paths.isEmpty()) return false
        
        // Éviter de renvoyer sur le même transport (prévenir boucles)
        val betterPath = paths.firstOrNull { it.transport != receivedOn }
        return betterPath != null
    }
    
    // ============== Tâches périodiques ==============
    
    private fun startPeriodicTasks() {
        // Annonce périodique
        scope.launch {
            while (isActive) {
                delay(ANNOUNCE_INTERVAL_MS)
                broadcastAnnouncement()
            }
        }
        
        // Nettoyage des entrées expirées
        scope.launch {
            while (isActive) {
                delay(60_000) // Toutes les minutes
                cleanupExpired()
            }
        }
        
        // Mise à jour des métriques
        scope.launch {
            while (isActive) {
                delay(METRIC_UPDATE_INTERVAL_MS)
                recalculateMetrics()
            }
        }
    }
    
    private fun startListeningForAnnounces() {
        // Les annonces sont reçues via TransportManager
        // Cette méthode s'abonnerait aux packets de type ANNOUNCE
    }
    
    private fun broadcastAnnouncement() {
        val announcement = createAnnouncement()
        // Envoyer sur tous les transports via TransportManager
    }
    
    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        forwardingTable.entries.removeIf { it.value.expiresAt < now }
        neighborTable.entries.removeIf { it.value.lastSeen + PATH_EXPIRY_MS < now }
        updateState()
    }
    
    private fun recalculateMetrics() {
        // Recalculer les métriques basées sur l'historique récent
        linkMetrics.forEach { (key, metrics) ->
            val history = transmissionHistory[key] ?: return@forEach
            
            if (history.isEmpty()) return@forEach
            
            // Garder seulement les 10 dernières transmissions
            val recent = history.takeLast(10)
            val reliability = recent.count { it.success }.toFloat() / recent.size
            
            linkMetrics[key] = metrics.copy(
                reliability = reliability
            )
        }
    }
    
    private fun updateState() {
        _state.value = PathfinderState(
            knownDestinations = forwardingTable.size,
            directNeighbors = neighborTable.count { it.value.directLink },
            totalHopsLearned = forwardingTable.values.sumOf { it.hops.toInt() },
            averagePathQuality = forwardingTable.values
                .map { it.metric.reliability }
                .average()
                .toFloat()
        )
    }
    
    // ============== Helpers ==============
    
    private fun getTransport(name: String): BitchatTransport? {
        return transports.find { it.name == name }
    }
    
    private fun getAlternativeTransports(exclude: String): List<BitchatTransport> {
        return transports.filter { it.name != exclude && it.isAvailable }
    }
    
    private fun meetsConstraints(path: Path, constraints: PathConstraints): Boolean {
        return (constraints.minBandwidth == null || path.metric.bandwidth >= constraints.minBandwidth) &&
               (constraints.maxLatency == null || path.metric.latency <= constraints.maxLatency) &&
               (constraints.maxHops == null || path.hops <= constraints.maxHops)
    }
    
    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }
    
    // ============== Classes de données ==============
    
    data class PathEntry(
        val destination: ByteArray,
        val nextHop: ByteArray,
        val transport: String,
        val hops: Int,
        val metric: PathMetric,
        val expiresAt: Long
    ) {
        fun toPath() = Path(
            destination = destination,
            nextHop = nextHop,
            transport = transport,
            hops = hops,
            metric = metric
        )
    }
    
    data class Path(
        val destination: ByteArray,
        val nextHop: ByteArray,
        val transport: String,
        val hops: Int,
        val metric: PathMetric
    )
    
    data class PathMetric(
        val latency: Long,          // ms
        val reliability: Float,     // 0.0 - 1.0
        val bandwidth: Int,         // bps
        val energyCost: Float,      // unité arbitraire
        val hops: Int
    ) {
        /**
         * Score composite: plus bas = meilleur chemin
         */
        fun compositeScore(): Float {
            val normalizedLatency = (latency / 1000f).coerceAtMost(10f) // Max 10s
            val normalizedBandwidth = 1_000_000f / bandwidth.coerceAtLeast(1)
            
            return (normalizedLatency * WEIGHT_LATENCY +
                   (1 - reliability) * 100f * WEIGHT_RELIABILITY +
                   normalizedBandwidth * WEIGHT_BANDWIDTH +
                   energyCost * WEIGHT_ENERGY +
                   hops * 10f * WEIGHT_HOPS)
        }
    }
    
    data class NeighborEntry(
        val identityHash: ByteArray,
        val transport: String,
        val lastSeen: Long,
        val directLink: Boolean,
        val hops: Int
    )
    
    data class LinkMetrics(
        val reliability: Float,
        val latency: Long,
        val rssi: Int? = null,
        val snr: Float? = null,
        val lastUpdated: Long
    )
    
    data class TransmissionRecord(
        val timestamp: Long,
        val success: Boolean,
        val rtt: Long
    )
    
    data class TransportSelection(
        val strategy: RoutingStrategy,
        val primaryTransport: BitchatTransport? = null,
        val fallbackTransports: List<BitchatTransport> = emptyList(),
        val transports: List<BitchatTransport> = emptyList(),
        val nextHop: ByteArray? = null,
        val estimatedSuccessRate: Float,
        val estimatedLatency: Long? = null
    )
    
    data class PathConstraints(
        val minBandwidth: Int? = null,
        val maxLatency: Long? = null,
        val maxHops: Int? = null
    )
    
    data class Announcement(
        val identity: ByteArray,
        val paths: List<AnnouncedPath>,
        val timestamp: Long
    )
    
    data class AnnouncedPath(
        val destination: ByteArray,
        val hops: Int,
        val metric: PathMetric
    )
    
    enum class RoutingStrategy {
        UNICAST,                // Un seul transport
        UNICAST_WITH_FALLBACK,  // Principal + fallback
        MULTI_TRANSPORT,        // Parallèle sur plusieurs
        FLOOD                   // Broadcast sur tous
    }
    
    enum class Urgency {
        BACKGROUND,
        NORMAL,
        HIGH,
        CRITICAL
    }
}
