package com.bitchat.android.link

import com.bitchat.android.transport.api.TransportPacket
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * HandshakeManager - Orchestre les handshakes Noise entre pairs
 * 
 * Gère:
 * - Création/récupération des handshakes actifs
 * - Timeout et retry
 * - Stockage des liens sécurisés établis
 * - Rotation des clés (rekey périodique)
 */
class HandshakeManager(
    private val myStaticKeyPair: NoiseHandshake.KeyPair,
    private val scope: CoroutineScope
) {
    companion object {
        const val TAG = "HandshakeManager"
        const val HANDSHAKE_TIMEOUT_MS = 30_000L    // 30 secondes
        const val REKEY_INTERVAL_MS = 60 * 60 * 1000L // 1 heure
        const val MAX_RETRIES = 3
    }
    
    // Handshakes en cours
    private val activeHandshakes = ConcurrentHashMap<String, HandshakeSession>()
    
    // Liens sécurisés établis (peerId -> SecureLink)
    private val establishedLinks = ConcurrentHashMap<String, SecureLink>()
    
    // Pending handshakes (attente de réponse)
    private val pendingHandshakes = ConcurrentHashMap<String, CompletableDeferred<HandshakeResult>>()
    
    // Callbacks
    var onLinkEstablished: ((peerId: String, link: SecureLink) -> Unit)? = null
    var onLinkFailed: ((peerId: String, error: String) -> Unit)? = null
    var onRekeyRequired: ((peerId: String) -> Unit)? = null
    
    // Utilise NoiseHandshake.KeyPair directement
    
    init {
        // Démarrer le rekey timer
        startRekeyTimer()
    }
    
    /**
     * Initie un handshake avec un nouveau pair (en tant qu'initiator)
     * 
     * @param peerId Identifiant du pair (hash de sa clé publique)
     * @param expectedPublicKey Clé publique attendue (optionnel, pour pinning)
     * @return Résultat du handshake
     */
    suspend fun initiateHandshake(
        peerId: String,
        expectedPublicKey: ByteArray? = null
    ): HandshakeResult {
        // Vérifier si on a déjà un lien
        establishedLinks[peerId]?.let { existing ->
            if (existing.isHealthy()) {
                return HandshakeResult.Success(existing)
            }
        }
        
        // Vérifier si handshake déjà en cours
        activeHandshakes[peerId]?.let { existing ->
            if (existing.isActive()) {
                // Attendre le résultat existant
                return waitForHandshake(peerId)
            }
        }
        
        // Créer nouveau handshake
        val handshake = NoiseHandshake(
            role = NoiseHandshake.Role.INITIATOR,
            staticKeyPair = myStaticKeyPair,
            remoteStaticKeyExpected = expectedPublicKey
        )
        
        val session = HandshakeSession(
            peerId = peerId,
            handshake = handshake,
            role = HandshakeRole.INITIATOR,
            createdAt = System.currentTimeMillis()
        )
        
        activeHandshakes[peerId] = session
        
        return try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) {
                performInitiatorHandshake(session)
            }
        } catch (e: TimeoutCancellationException) {
            HandshakeResult.Failed("Handshake timeout")
        } finally {
            activeHandshakes.remove(peerId)
        }
    }
    
    /**
     * Traite un message de handshake entrant (en tant que responder)
     */
    suspend fun handleIncomingHandshake(
        peerId: String,
        messageType: Int,
        payload: ByteArray
    ): HandshakeResponse? {
        
        when (messageType) {
            1 -> {
                // Message 1: Nouveau handshake
                val handshake = NoiseHandshake(
                    role = NoiseHandshake.Role.RESPONDER,
                    staticKeyPair = myStaticKeyPair
                )
                
                val session = HandshakeSession(
                    peerId = peerId,
                    handshake = handshake,
                    role = HandshakeRole.RESPONDER,
                    createdAt = System.currentTimeMillis()
                )
                
                activeHandshakes[peerId] = session
                
                // Traiter message 1
                handshake.processMessage1(payload)
                
                // Créer réponse (message 2)
                val response = handshake.createMessage2()
                
                return HandshakeResponse(
                    payload = response,
                    nextStep = 2
                )
            }
            
            3 -> {
                // Message 3: Finalisation
                val session = activeHandshakes[peerId]
                    ?: return null
                
                session.handshake.processMessage3(payload)
                
                if (session.handshake.isEstablished()) {
                    // Créer le lien sécurisé
                    val link = createSecureLink(session)
                    establishedLinks[peerId] = link
                    
                    // Notifier
                    onLinkEstablished?.invoke(peerId, link)
                    
                    // Répondre à l'attente éventuelle
                    pendingHandshakes[peerId]?.complete(HandshakeResult.Success(link))
                    
                    activeHandshakes.remove(peerId)
                }
                
                return null // Pas de réponse nécessaire
            }
            
            else -> return null
        }
    }
    
    /**
     * Récupère un lien sécurisé existant
     */
    fun getLink(peerId: String): SecureLink? {
        return establishedLinks[peerId]?.takeIf { it.isHealthy() }
    }
    
    /**
     * Vérifie si un lien existe et est valide
     */
    fun hasLink(peerId: String): Boolean {
        return getLink(peerId) != null
    }
    
    /**
     * Ferme un lien et supprime les clés
     */
    fun closeLink(peerId: String) {
        establishedLinks.remove(peerId)
        activeHandshakes.remove(peerId)
    }
    
    /**
     * Ferme tous les liens (panic mode)
     */
    fun closeAllLinks() {
        establishedLinks.clear()
        activeHandshakes.clear()
    }
    
    /**
     * Retourne la liste des pairs connectés
     */
    fun getConnectedPeers(): List<String> {
        return establishedLinks.entries
            .filter { it.value.isHealthy() }
            .map { it.key }
    }
    
    // ============== Implémentation privée ==============
    
    private suspend fun performInitiatorHandshake(
        session: HandshakeSession
    ): HandshakeResult {
        val handshake = session.handshake
        
        // Étape 1: Envoyer e
        val msg1 = handshake.createMessage1()
        sendHandshakeMessage(session.peerId, 1, msg1)
        
        // Attendre message 2
        val msg2 = receiveHandshakeMessage(session.peerId, 2)
            ?: return HandshakeResult.Failed("No response to message 1")
        
        // Traiter message 2
        handshake.processMessage2(msg2)
        
        // Étape 3: Envoyer s, se
        val msg3 = handshake.createMessage3()
        sendHandshakeMessage(session.peerId, 3, msg3)
        
        // Handshake complet!
        val link = createSecureLink(session)
        establishedLinks[session.peerId] = link
        
        onLinkEstablished?.invoke(session.peerId, link)
        
        return HandshakeResult.Success(link)
    }
    
    private fun createSecureLink(session: HandshakeSession): SecureLink {
        return SecureLink(
            peerId = session.peerId,
            remotePublicKey = session.handshake.getRemoteStaticKey()!!,
            encrypt = { plaintext ->
                session.handshake.encrypt(plaintext)
            },
            decrypt = { ciphertext ->
                session.handshake.decrypt(ciphertext)
            },
            establishedAt = System.currentTimeMillis()
        )
    }
    
    private suspend fun waitForHandshake(peerId: String): HandshakeResult {
        val deferred = pendingHandshakes.getOrPut(peerId) {
            CompletableDeferred()
        }
        return deferred.await()
    }
    
    private suspend fun sendHandshakeMessage(
        peerId: String,
        step: Int,
        payload: ByteArray
    ) {
        // TODO: Envoyer via TransportManager
        // Pour l'instant, simuler
        delay(100)
    }
    
    private suspend fun receiveHandshakeMessage(
        peerId: String,
        expectedStep: Int
    ): ByteArray? {
        // TODO: Recevoir via TransportManager
        // Pour l'instant, simuler
        delay(100)
        return null
    }
    
    private fun startRekeyTimer() {
        scope.launch {
            while (isActive) {
                delay(REKEY_INTERVAL_MS)
                
                // Vérifier les liens à rekey
                establishedLinks.forEach { (peerId, link) ->
                    if (link.needsRekey()) {
                        onRekeyRequired?.invoke(peerId)
                        
                        // Relancer handshake
                        scope.launch {
                            initiateHandshake(peerId, link.remotePublicKey)
                        }
                    }
                }
            }
        }
    }
    
    // ============== Classes de données ==============
    
    data class HandshakeSession(
        val peerId: String,
        val handshake: NoiseHandshake,
        val role: HandshakeRole,
        val createdAt: Long,
        var retryCount: Int = 0
    ) {
        fun isActive(): Boolean {
            return System.currentTimeMillis() - createdAt < HANDSHAKE_TIMEOUT_MS
        }
    }
    
    enum class HandshakeRole { INITIATOR, RESPONDER }
    
    sealed class HandshakeResult {
        data class Success(val link: SecureLink) : HandshakeResult()
        data class Failed(val error: String) : HandshakeResult()
    }
    
    data class HandshakeResponse(
        val payload: ByteArray,
        val nextStep: Int
    )
}

/**
 * Lien sécurisé établi (post-handshake)
 */
class SecureLink(
    val peerId: String,
    val remotePublicKey: ByteArray,
    private val encrypt: (ByteArray) -> ByteArray,
    private val decrypt: (ByteArray) -> ByteArray?,
    val establishedAt: Long
) {
    private var lastActivity = System.currentTimeMillis()
    private var messageCount = 0
    
    fun encrypt(plaintext: ByteArray): ByteArray {
        lastActivity = System.currentTimeMillis()
        messageCount++
        return encrypt.invoke(plaintext)
    }
    
    fun decrypt(ciphertext: ByteArray): ByteArray? {
        lastActivity = System.currentTimeMillis()
        return decrypt.invoke(ciphertext)
    }
    
    fun isHealthy(): Boolean {
        return System.currentTimeMillis() - establishedAt < 24 * 60 * 60 * 1000 // 24h
    }
    
    fun needsRekey(): Boolean {
        return System.currentTimeMillis() - establishedAt > 60 * 60 * 1000 || // 1h
               messageCount > 10_000 // ou 10k messages
    }
    
    fun getFingerprint(): String {
        return remotePublicKey.copyOfRange(0, 8).toHex()
    }
    
    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
