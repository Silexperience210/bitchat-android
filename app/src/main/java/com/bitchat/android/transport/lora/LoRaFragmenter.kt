package com.bitchat.android.transport.lora

import com.bitchat.android.transport.api.TransportPacket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Fragmenteur pour messages LoRa
 * 
 * LoRa a une limite de 237 bytes par packet PHY.
 * Avec overhead protocol, on a ~200 bytes utiles.
 * Les messages plus grands doivent être fragmentés.
 * 
 * Format de fragment:
 * - Header (4 bytes):
 *   - packet_id: 16 bits (identifie le message original)
 *   - fragment_num: 8 bits (numéro de ce fragment)
 *   - total_fragments: 8 bits (nombre total)
 * - Payload: jusqu'à 196 bytes
 * - Total: 200 bytes max par fragment
 */
class LoRaFragmenter(private val mtu: Int = 200) {
    
    companion object {
        const val HEADER_SIZE = 4
        const val MAX_PAYLOAD_PER_FRAGMENT = 196 // 200 - 4
    }
    
    // Buffer de réassembly (packet_id -> fragments reçus)
    private val reassemblyBuffer = ConcurrentHashMap<String, FragmentBuffer>()
    
    // Timeout pour reassembly (5 secondes)
    private val REASSEMBLY_TIMEOUT_MS = 5000
    
    /**
     * Fragmente un TransportPacket en plusieurs LoRaFragment
     */
    fun fragment(packet: TransportPacket): List<LoRaFragment> {
        val payload = packet.payload
        
        // Si ça tient dans un seul fragment, pas besoin de fragmentation
        if (payload.size <= MAX_PAYLOAD_PER_FRAGMENT) {
            return listOf(LoRaFragment(
                packetId = packet.packetId.take(4).hashCode().toUShort(),
                fragmentNum = 0.toUByte(),
                totalFragments = 1.toUByte(),
                payload = payload,
                isLast = true,
                originalPacket = packet
            ))
        }
        
        // Calculer le nombre de fragments nécessaires
        val numFragments = kotlin.math.ceil(
            payload.size.toDouble() / MAX_PAYLOAD_PER_FRAGMENT
        ).toInt()
        
        val fragments = mutableListOf<LoRaFragment>()
        val packetId = packet.packetId.take(4).hashCode().toUShort()
        
        for (i in 0 until numFragments) {
            val start = i * MAX_PAYLOAD_PER_FRAGMENT
            val end = kotlin.math.min(start + MAX_PAYLOAD_PER_FRAGMENT, payload.size)
            val chunk = payload.copyOfRange(start, end)
            
            fragments.add(LoRaFragment(
                packetId = packetId,
                fragmentNum = i.toUByte(),
                totalFragments = numFragments.toUByte(),
                payload = chunk,
                isLast = (i == numFragments - 1),
                originalPacket = packet
            ))
        }
        
        return fragments
    }
    
    /**
     * Réassemble un fragment en TransportPacket complet
     * Retourne null si le message n'est pas encore complet
     */
    fun defragment(fragment: LoRaFragment): TransportPacket? {
        val bufferKey = "${fragment.packetId}_${fragment.originalPacket.sourceHash.toHex()}"
        
        // Récupérer ou créer le buffer
        val buffer = reassemblyBuffer.getOrPut(bufferKey) {
            FragmentBuffer(
                totalFragments = fragment.totalFragments.toInt(),
                fragments = mutableMapOf(),
                firstReceivedAt = System.currentTimeMillis()
            )
        }
        
        // Vérifier timeout
        if (System.currentTimeMillis() - buffer.firstReceivedAt > REASSEMBLY_TIMEOUT_MS) {
            reassemblyBuffer.remove(bufferKey)
            return null // Expiré
        }
        
        // Ajouter ce fragment
        buffer.fragments[fragment.fragmentNum.toInt()] = fragment
        
        // Vérifier si complet
        if (buffer.fragments.size == buffer.totalFragments) {
            // Réassembler
            val sortedFragments = buffer.fragments.toSortedMap().values
            val completePayload = sortedFragments.fold(ByteArray(0)) { acc, frag ->
                acc + frag.payload
            }
            
            // Nettoyer
            reassemblyBuffer.remove(bufferKey)
            
            // Retourner le packet reconstruit
            return fragment.originalPacket.copy(
                payload = completePayload
            )
        }
        
        return null // Pas encore complet
    }
    
    /**
     * Calcule la taille totale d'un message fragmenté (avec overhead)
     */
    fun calculateTotalSize(payloadSize: Int): Int {
        val numFragments = kotlin.math.ceil(
            payloadSize.toDouble() / MAX_PAYLOAD_PER_FRAGMENT
        ).toInt()
        
        return numFragments * (MAX_PAYLOAD_PER_FRAGMENT + HEADER_SIZE)
    }
    
    /**
     * Nettoie les buffers expirés
     */
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        reassemblyBuffer.entries.removeIf { (_, buffer) ->
            (now - buffer.firstReceivedAt) > REASSEMBLY_TIMEOUT_MS
        }
    }
    
    private data class FragmentBuffer(
        val totalFragments: Int,
        val fragments: MutableMap<Int, LoRaFragment>,
        val firstReceivedAt: Long
    )
    
    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }
}

/**
 * Représente un fragment LoRa individuel
 */
data class LoRaFragment(
    val packetId: UShort,           // ID du packet original (16 bits)
    val fragmentNum: UByte,         // Numéro de ce fragment (0-indexed)
    val totalFragments: UByte,      // Nombre total de fragments
    val payload: ByteArray,         // Données (max 196 bytes)
    val isLast: Boolean,            // True si dernier fragment
    val originalPacket: TransportPacket // Référence au packet original
) {
    /**
     * Sérialise le fragment en bytes pour transmission
     */
    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(LoRaFragmenter.HEADER_SIZE + payload.size)
        
        // Header
        buffer.putShort(packetId.toShort())
        buffer.put(fragmentNum.toByte())
        buffer.put(totalFragments.toByte())
        
        // Payload
        buffer.put(payload)
        
        return buffer.array()
    }
    
    companion object {
        const val HEADER_SIZE = 4
        
        /**
         * Désérialise depuis bytes reçus
         */
        fun deserialize(bytes: ByteArray): LoRaFragment {
            require(bytes.size >= HEADER_SIZE) { "Fragment too small" }
            
            val buffer = ByteBuffer.wrap(bytes)
            
            val packetId = buffer.short.toUShort()
            val fragmentNum = buffer.get().toUByte()
            val totalFragments = buffer.get().toUByte()
            
            val payload = ByteArray(bytes.size - HEADER_SIZE)
            buffer.get(payload)
            
            return LoRaFragment(
                packetId = packetId,
                fragmentNum = fragmentNum,
                totalFragments = totalFragments,
                payload = payload,
                isLast = fragmentNum == (totalFragments - 1u).toUByte(),
                originalPacket = createDummyPacket() // Sera reconstruit
            )
        }
        
        private fun createDummyPacket(): TransportPacket {
            return TransportPacket(
                sourceHash = ByteArray(16),
                destinationHash = ByteArray(16),
                payload = ByteArray(0),
                packetId = "dummy"
            )
        }
    }
}
