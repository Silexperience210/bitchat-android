package com.bitchat.android.transport.api

/**
 * Interface abstraite pour tous les transports (BLE, LoRa, TCP...)
 * Architecture Reticulum-style: transport-agnostic
 * 
 * Chaque implémentation gère un médium physique spécifique
 * mais expose une interface unifiée pour le reste de l'application.
 */
interface BitchatTransport {
    
    /** Nom unique du transport: "ble", "lora", "tcp" */
    val name: String
    
    /** Disponibilité actuelle (hardware présent et activé) */
    val isAvailable: Boolean
    
    /** Débit nominal en bits par seconde */
    val bitrate: Int
    
    /** Fiabilité estimée (0.0 - 1.0) basée sur les stats historiques */
    val reliability: Float
    
    /**
     * Transmet un packet sur ce transport
     * @param packet Le packet à transmettre
     * @return true si transmis immédiatement, false si mis en file d'attente
     */
    suspend fun transmit(packet: TransportPacket): TransmitResult
    
    /**
     * Enregistre un callback pour la réception de packets
     * Appelé pour chaque packet reçu sur ce transport
     */
    fun setReceiveCallback(callback: TransportCallback)
    
    /**
     * Démarre le transport (allume radio, scan, etc.)
     */
    fun start()
    
    /**
     * Arrête le transport proprement
     */
    fun stop()
    
    /**
     * Retourne les métriques actuelles (RTT, charge, etc.)
     */
    fun getMetrics(): TransportMetrics
}

/**
 * Résultat d'une transmission
 */
data class TransmitResult(
    val success: Boolean,
    val queued: Boolean = false,
    val estimatedDeliveryTime: Long? = null,
    val error: String? = null
)

/**
 * Callback pour réception de packets
 */
typealias TransportCallback = (packet: TransportPacket, metadata: TransportMetadata) -> Unit

/**
 * Packet de transport universel
 * Format binaire optimisé, indépendant du médium
 */
data class TransportPacket(
    /** Hash de l'expéditeur (16 bytes) - anonymisé */
    val sourceHash: ByteArray,
    
    /** Hash du destinataire (16 bytes) - 0xFF... pour broadcast */
    val destinationHash: ByteArray,
    
    /** Payload chiffré ou en clair selon type */
    val payload: ByteArray,
    
    /** Type de packet */
    val type: PacketType = PacketType.DATA,
    
    /** Nombre de sauts déjà effectués */
    val hops: Int = 0,
    
    /** Time To Live - sauts maximum avant drop */
    val ttl: Int = 8,
    
    /** Timestamp de création */
    val timestamp: Long = System.currentTimeMillis(),
    
    /** Si true, demande un ACK de livraison */
    val reliable: Boolean = false,
    
    /** ID unique du packet pour déduplication */
    val packetId: String = generatePacketId()
) {
    enum class PacketType {
        DATA,           // Données applicatives
        ANNOUNCE,       // Announcement de présence
        HANDSHAKE,      // Handshake cryptographique
        ACK,            // Accusé de réception
        FRAGMENT        // Fragment de message
    }
    
    companion object {
        private fun generatePacketId(): String {
            return java.util.UUID.randomUUID().toString().take(16)
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TransportPacket
        return packetId == other.packetId
    }
    
    override fun hashCode(): Int = packetId.hashCode()
}

/**
 * Métadonnées de réception spécifiques au transport
 */
data class TransportMetadata(
    /** Nom du transport utilisé */
    val transport: String,
    
    /** RSSI en dBm (si applicable) */
    val rssi: Int? = null,
    
    /** Signal-to-Noise Ratio (si applicable) */
    val snr: Float? = null,
    
    /** Timestamp de réception */
    val timestamp: Long = System.currentTimeMillis(),
    
    /** Nombre de sauts connus */
    val hops: Int = 0,
    
    /** Latence estimée du lien */
    val linkLatency: Long? = null
)

/**
 * Métriques d'un transport
 */
data class TransportMetrics(
    /** Round-trip time moyen en ms */
    val rtt: Long = 0,
    
    /** Taux de perte (0.0 - 1.0) */
    val lossRate: Float = 0f,
    
    /** Bande passante disponible actuellement */
    val availableBandwidth: Int = 0,
    
    /** Charge actuelle (file d'attente) */
    val currentLoad: Int = 0,
    
    /** Nombre de pairs connectés/découverts */
    val peerCount: Int = 0
)

/**
 * Priorité de transmission
 */
enum class Priority {
    BACKGROUND,     // Sync périodique
    NORMAL,         // Messages standards
    HIGH,           // Messages utilisateur
    CRITICAL        // Urgences, handshakes
}
