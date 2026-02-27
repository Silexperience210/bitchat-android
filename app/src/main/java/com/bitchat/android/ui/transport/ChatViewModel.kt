package com.bitchat.android.ui.transport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.transport.TransportManager
import com.bitchat.android.transport.TransportManagerStatus
import com.bitchat.android.transport.api.TransportMetadata
import com.bitchat.android.transport.api.TransportPacket
// import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
// import javax.inject.Inject

/**
 * ViewModel pour l'écran de chat avec gestion multi-transport
 * 
 * Connecte l'UI avec le TransportManager
 * Gère les messages, leur affichage et les métadonnées de transport
 */
// @HiltViewModel
class ChatViewModel /* @Inject */ constructor(
    private val transportManager: TransportManager
) : ViewModel() {

    // Status des transports (BLE, LoRa, etc.)
    val transportStatus: StateFlow<TransportManagerStatus> = transportManager.status

    // Liste des messages (UI models)
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Métadonnées de transport par message ID
    private val messageTransportMetadata = mutableMapOf<String, TransportMetadata>()

    // Mon identité (hash)
    private val myIdentityHash = generateMyIdentityHash()

    init {
        // Démarrer les transports
        transportManager.startAll()

        // Écouter les packets entrants
        setupPacketListener()

        // Ajouter un message système de démarrage
        addSystemMessage("Transports démarrés. En attente de connexions...")
    }

    /**
     * Envoie un message texte
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            // Créer le packet
            val packet = TransportPacket(
                sourceHash = myIdentityHash,
                destinationHash = ByteArray(16) { 0xFF.toByte() }, // Broadcast
                payload = text.toByteArray(Charsets.UTF_8),
                type = TransportPacket.PacketType.DATA,
                reliable = true
            )

            // Créer le message UI (optimistic)
            val messageId = packet.packetId
            val optimisticMessage = ChatMessage(
                id = messageId,
                text = text,
                isFromMe = true,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING,
                transportMetadata = null // Sera mis à jour après envoi
            )

            addMessage(optimisticMessage)

            // Envoyer via TransportManager
            val result = transportManager.send(packet)

            // Mettre à jour le statut
            updateMessageStatus(
                messageId = messageId,
                status = if (result.success) MessageStatus.SENT else MessageStatus.FAILED,
                metadata = if (result.success) {
                    TransportMetadata(
                        transport = "ble", // TODO: détecter le transport utilisé
                        timestamp = System.currentTimeMillis()
                    )
                } else null
            )

            // Si mis en file d'attente
            if (result.queued) {
                updateMessageStatus(messageId, MessageStatus.QUEUED)
            }
        }
    }

    /**
     * Envoie un broadcast sur tous les transports
     */
    fun broadcastMessage(text: String) {
        viewModelScope.launch {
            val packet = TransportPacket(
                sourceHash = myIdentityHash,
                destinationHash = ByteArray(16) { 0xFF.toByte() },
                payload = text.toByteArray(Charsets.UTF_8),
                type = TransportPacket.PacketType.DATA
            )

            transportManager.broadcast(packet)
        }
    }

    /**
     * Configure le listener pour packets entrants
     */
    private fun setupPacketListener() {
        transportManager.setPacketHandler { packet, metadata ->
            handleIncomingPacket(packet, metadata)
        }
    }

    /**
     * Traite un packet entrant
     */
    private fun handleIncomingPacket(packet: TransportPacket, metadata: TransportMetadata) {
        // Ignorer mes propres messages (déduplication)
        if (packet.sourceHash.contentEquals(myIdentityHash)) {
            return
        }

        when (packet.type) {
            TransportPacket.PacketType.DATA -> {
                val text = String(packet.payload, Charsets.UTF_8)
                val message = ChatMessage(
                    id = packet.packetId,
                    text = text,
                    isFromMe = false,
                    timestamp = packet.timestamp,
                    status = MessageStatus.DELIVERED,
                    transportMetadata = metadata
                )

                addMessage(message)
            }

            TransportPacket.PacketType.ANNOUNCE -> {
                // Nouveau pair découvert
                val peerId = packet.sourceHash.toHexString().take(8)
                addSystemMessage("Peer découvert: $peerId via ${metadata.transport}")
            }

            TransportPacket.PacketType.HANDSHAKE -> {
                // Géré par le LinkManager
            }

            else -> {
                // Ignorer autres types pour l'instant
            }
        }
    }

    /**
     * Ajoute un message à la liste
     */
    private fun addMessage(message: ChatMessage) {
        val current = _messages.value.toMutableList()
        current.add(message)
        _messages.value = current

        // Garder les métadonnées
        message.transportMetadata?.let {
            messageTransportMetadata[message.id] = it
        }
    }

    /**
     * Ajoute un message système
     */
    private fun addSystemMessage(text: String) {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            isFromMe = false,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SYSTEM,
            isSystem = true
        )
        addMessage(message)
    }

    /**
     * Met à jour le statut d'un message
     */
    private fun updateMessageStatus(
        messageId: String,
        status: MessageStatus,
        metadata: TransportMetadata? = null
    ) {
        val current = _messages.value.toMutableList()
        val index = current.indexOfFirst { it.id == messageId }

        if (index != -1) {
            val updated = current[index].copy(
                status = status,
                transportMetadata = metadata ?: current[index].transportMetadata
            )
            current[index] = updated
            _messages.value = current
        }
    }

    /**
     * Génère un hash d'identité (16 bytes)
     * TODO: Remplacer par vraie identité cryptographique
     */
    private fun generateMyIdentityHash(): ByteArray {
        // Générer un hash unique stable pour cette session
        val uuid = UUID.randomUUID().toString()
        return uuid.toByteArray(Charsets.UTF_8).take(16).toByteArray()
    }

    override fun onCleared() {
        super.onCleared()
        transportManager.stopAll()
    }
}

/**
 * Modèle UI pour un message
 */
data class ChatMessage(
    val id: String,
    val text: String,
    val isFromMe: Boolean,
    val timestamp: Long,
    val status: MessageStatus,
    val transportMetadata: TransportMetadata? = null,
    val isSystem: Boolean = false
)

enum class MessageStatus {
    SENDING,    // En cours
    SENT,       // Envoyé
    DELIVERED,  // Livré
    READ,       // Lu
    FAILED,     // Échec
    QUEUED,     // En attente
    SYSTEM      // Message système
}

/**
 * Extension pour convertir ByteArray en hex
 */
private fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}
