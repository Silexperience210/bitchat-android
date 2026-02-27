package com.bitchat.android.reticulum

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Implémentation du protocole Reticulum
 * 
 * Reticulum Packet Format:
 * - Header (2 bytes): Flags et type
 * - Context (1 byte): Contexte du packet
 * - Destination hash (16 bytes): Hash du destinataire
 * - Transport ID (16 bytes): ID de transport
 * - Payload: Données chiffrées
 * 
 * Documentation: https://reticulum.network/manual/concepts.html
 */
object ReticulumProtocol {
    
    // Header flags
    const val HEADER_TYPE_MASK = 0b11000000
    const val HEADER_TYPE_DATA = 0b00000000
    const val HEADER_TYPE_ANNOUNCE = 0b01000000
    const val HEADER_TYPE_LINK = 0b10000000
    const val HEADER_TYPE_PROOF = 0b11000000
    
    const val HEADER_DEST_TYPE_MASK = 0b00110000
    const val HEADER_DEST_TYPE_SINGLE = 0b00000000
    const val HEADER_DEST_TYPE_GROUP = 0b00010000
    const val HEADER_DEST_TYPE_PLAIN = 0b00100000
    const val HEADER_DEST_TYPE_LINK = 0b00110000
    
    const val HEADER_HOPS_MASK = 0b00001111
    
    // Context flags
    const val CONTEXT_NONE = 0x00
    const val CONTEXT_PATH_REQUEST = 0x01
    const val CONTEXT_PATH_RESPONSE = 0x02
    const val CONTEXT_PATH_PROOF = 0x03
    const val CONTEXT_LINK_REQUEST = 0x04
    const val CONTEXT_LINK_ESTABLISHED = 0x05
    const val CONTEXT_LINK_CLOSE = 0x06
    const val CONTEXT_LINK_PROOF = 0x07
    
    // Constants
    const val HASH_LENGTH = 16
    const val MAX_HOPS = 15
    const val MTU = 500  // Reticulum MTU
}

/**
 * Représente un packet Reticulum
 */
data class ReticulumPacket(
    val type: PacketType,
    val destinationType: DestinationType,
    val hops: Int,
    val context: Int,
    val destinationHash: ByteArray,
    val transportId: ByteArray,
    val payload: ByteArray,
    val rawPacket: ByteArray? = null
) {
    enum class PacketType {
        DATA,
        ANNOUNCE,
        LINK_REQUEST,
        LINK_PROOF,
        PROOF
    }
    
    enum class DestinationType {
        SINGLE,
        GROUP,
        PLAIN,
        LINK
    }
    
    fun serialize(): ByteArray {
        val header = constructHeader()
        return header + destinationHash + transportId + payload
    }
    
    private fun constructHeader(): ByteArray {
        val typeBits = when (type) {
            PacketType.DATA -> ReticulumProtocol.HEADER_TYPE_DATA
            PacketType.ANNOUNCE -> ReticulumProtocol.HEADER_TYPE_ANNOUNCE
            PacketType.LINK_REQUEST -> ReticulumProtocol.HEADER_TYPE_LINK
            PacketType.LINK_PROOF -> ReticulumProtocol.HEADER_TYPE_LINK
            PacketType.PROOF -> ReticulumProtocol.HEADER_TYPE_PROOF
        }
        
        val destTypeBits = when (destinationType) {
            DestinationType.SINGLE -> ReticulumProtocol.HEADER_DEST_TYPE_SINGLE
            DestinationType.GROUP -> ReticulumProtocol.HEADER_DEST_TYPE_GROUP
            DestinationType.PLAIN -> ReticulumProtocol.HEADER_DEST_TYPE_PLAIN
            DestinationType.LINK -> ReticulumProtocol.HEADER_DEST_TYPE_LINK
        }
        
        val hopsBits = (hops.coerceIn(0, ReticulumProtocol.MAX_HOPS) and 
                       ReticulumProtocol.HEADER_HOPS_MASK)
        
        val headerByte = (typeBits or destTypeBits or hopsBits).toByte()
        
        return byteArrayOf(headerByte, context.toByte())
    }
    
    fun hop(): ReticulumPacket {
        return if (hops < ReticulumProtocol.MAX_HOPS) {
            copy(hops = hops + 1)
        } else {
            this
        }
    }
    
    fun isForUs(ourHash: ByteArray): Boolean {
        return destinationHash.contentEquals(ourHash) || isBroadcast()
    }
    
    fun isBroadcast(): Boolean {
        return destinationType == DestinationType.PLAIN ||
               destinationHash.all { it == 0xFF.toByte() }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ReticulumPacket
        return destinationHash.contentEquals(other.destinationHash) &&
               transportId.contentEquals(other.transportId) &&
               payload.contentEquals(other.payload)
    }
    
    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + destinationHash.contentHashCode()
        result = 31 * result + transportId.contentHashCode()
        return result
    }
    
    companion object {
        fun parse(bytes: ByteArray): ReticulumPacket? {
            return try {
                if (bytes.size < 34) {
                    return null
                }
                
                val header = bytes[0].toInt() and 0xFF
                val contextByte = bytes[1]
                
                val typeBits = header and ReticulumProtocol.HEADER_TYPE_MASK
                val destTypeBits = header and ReticulumProtocol.HEADER_DEST_TYPE_MASK
                val hops = header and ReticulumProtocol.HEADER_HOPS_MASK
                
                val type = when (typeBits) {
                    ReticulumProtocol.HEADER_TYPE_DATA -> PacketType.DATA
                    ReticulumProtocol.HEADER_TYPE_ANNOUNCE -> PacketType.ANNOUNCE
                    ReticulumProtocol.HEADER_TYPE_LINK -> PacketType.LINK_REQUEST
                    ReticulumProtocol.HEADER_TYPE_PROOF -> PacketType.PROOF
                    else -> PacketType.DATA
                }
                
                val destType = when (destTypeBits) {
                    ReticulumProtocol.HEADER_DEST_TYPE_SINGLE -> DestinationType.SINGLE
                    ReticulumProtocol.HEADER_DEST_TYPE_GROUP -> DestinationType.GROUP
                    ReticulumProtocol.HEADER_DEST_TYPE_PLAIN -> DestinationType.PLAIN
                    ReticulumProtocol.HEADER_DEST_TYPE_LINK -> DestinationType.LINK
                    else -> DestinationType.SINGLE
                }
                
                val destHash = bytes.copyOfRange(2, 18)
                val transportId = bytes.copyOfRange(18, 34)
                val payload = if (bytes.size > 34) bytes.copyOfRange(34, bytes.size) else byteArrayOf()
                
                ReticulumPacket(
                    type = type,
                    destinationType = destType,
                    hops = hops,
                    context = contextByte.toInt() and 0xFF,
                    destinationHash = destHash,
                    transportId = transportId,
                    payload = payload,
                    rawPacket = bytes
                )
            } catch (e: Exception) {
                null
            }
        }
        
        fun createBroadcast(payload: ByteArray, context: Int = ReticulumProtocol.CONTEXT_NONE.toInt() and 0xFF): ReticulumPacket {
            return ReticulumPacket(
                type = PacketType.DATA,
                destinationType = DestinationType.PLAIN,
                hops = 0,
                context = context,
                destinationHash = ByteArray(16) { 0xFF.toByte() },
                transportId = generateTransportId(),
                payload = payload
            )
        }
        
        private fun generateTransportId(): ByteArray {
            val random = java.security.SecureRandom()
            val id = ByteArray(16)
            random.nextBytes(id)
            return id
        }
    }
}

data class ReticulumAnnounce(
    val identityHash: ByteArray,
    val destinationHash: ByteArray,
    val publicKey: ByteArray,
    val appData: ByteArray? = null,
    val hops: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toPacket(): ReticulumPacket {
        val payload = serialize()
        return ReticulumPacket(
            type = ReticulumPacket.PacketType.ANNOUNCE,
            destinationType = ReticulumPacket.DestinationType.SINGLE,
            hops = hops,
            context = ReticulumProtocol.CONTEXT_NONE.toInt() and 0xFF,
            destinationHash = destinationHash,
            transportId = ByteArray(16), // Will be set by packet
            payload = payload
        )
    }
    
    private fun serialize(): ByteArray {
        val appDataSize = appData?.size ?: 0
        val buffer = ByteBuffer.allocate(identityHash.size + publicKey.size + 4 + appDataSize)
        buffer.put(identityHash)
        buffer.put(publicKey)
        buffer.putInt(appDataSize)
        appData?.let { buffer.put(it) }
        return buffer.array()
    }
    
    companion object {
        fun parse(payload: ByteArray): ReticulumAnnounce? {
            return try {
                val buffer = ByteBuffer.wrap(payload)
                val identityHash = ByteArray(16)
                val publicKey = ByteArray(32)
                buffer.get(identityHash)
                buffer.get(publicKey)
                val appDataSize = buffer.int
                val appData = if (appDataSize > 0 && buffer.remaining() >= appDataSize) {
                    ByteArray(appDataSize).apply { buffer.get(this) }
                } else null
                
                ReticulumAnnounce(
                    identityHash = identityHash,
                    destinationHash = identityHash,
                    publicKey = publicKey,
                    appData = appData
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

fun String.toReticulumHash(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(this.toByteArray(Charsets.UTF_8))
    return hash.copyOf(16)
}

fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}
