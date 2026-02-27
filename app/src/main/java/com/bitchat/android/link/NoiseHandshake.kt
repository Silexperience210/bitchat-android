package com.bitchat.android.link

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Implémentation simplifiée du Noise Protocol Pattern XX
 * 
 * NOTE: Cette implémentation utilise des primitives cryptographiques standard
 * d'Android (AES-GCM) en attendant l'intégration complète de Curve25519/ChaCha20.
 * 
 * Pour la production, remplacer par une librairie Noise complète comme noise-java.
 */
class NoiseHandshake(
    private val role: Role,
    private val staticKeyPair: KeyPair,
    private val remoteStaticKeyExpected: ByteArray? = null
) {
    enum class Role { INITIATOR, RESPONDER }
    enum class State {
        INITIALIZED,
        WAITING_FOR_E,
        WAITING_FOR_SE,
        WAITING_FOR_S,
        ESTABLISHED,
        FAILED
    }
    
    private val secureRandom = SecureRandom()
    
    private var state = State.INITIALIZED
    
    private var ephemeralKeyPair: KeyPair? = null
    private var remoteEphemeralKey: ByteArray? = null
    private var remoteStaticKey: ByteArray? = null
    
    private var chainingKey: ByteArray = ByteArray(32) { 0 }
    private var hash: ByteArray = ByteArray(32)
    private var encryptionKey: ByteArray? = null
    
    private var sendCipher: CipherState? = null
    private var receiveCipher: CipherState? = null
    
    var onHandshakeComplete: ((remoteStaticKey: ByteArray, fingerprint: String) -> Unit)? = null
    var onHandshakeFailed: ((error: String) -> Unit)? = null
    
    init {
        // Initialize hash with protocol name
        val protocolName = "Noise_XX_25519_ChaChaPoly_SHA256"
        hash = protocolName.toByteArray(Charsets.UTF_8).copyOf(32)
        chainingKey = hash.copyOf()
    }
    
    // ============== Message 1: Initiator -> Responder ==============
    
    fun createMessage1(): ByteArray {
        require(role == Role.INITIATOR) { "Only initiator creates message 1" }
        require(state == State.INITIALIZED)
        
        ephemeralKeyPair = generateKeyPair()
        
        mixHash(ephemeralKeyPair!!.publicKey)
        
        state = State.WAITING_FOR_SE
        
        return ephemeralKeyPair!!.publicKey
    }
    
    fun processMessage1(message: ByteArray) {
        require(role == Role.RESPONDER)
        require(state == State.INITIALIZED)
        require(message.size == 32)
        
        remoteEphemeralKey = message
        mixHash(message)
        
        state = State.WAITING_FOR_E
    }
    
    // ============== Message 2: Responder -> Initiator ==============
    
    fun createMessage2(): ByteArray {
        require(role == Role.RESPONDER)
        require(state == State.WAITING_FOR_E)
        
        ephemeralKeyPair = generateKeyPair()
        
        val output = mutableListOf<Byte>()
        
        output.addAll(ephemeralKeyPair!!.publicKey.toList())
        mixHash(ephemeralKeyPair!!.publicKey)
        
        mixKey(calculateDH(ephemeralKeyPair!!.privateKey, remoteEphemeralKey!!))
        
        val encryptedStatic = encryptAndHash(staticKeyPair.publicKey)
        output.addAll(encryptedStatic.toList())
        
        mixKey(calculateDH(staticKeyPair.privateKey, remoteEphemeralKey!!))
        
        state = State.WAITING_FOR_S
        
        return output.toByteArray()
    }
    
    fun processMessage2(message: ByteArray): ByteArray? {
        require(role == Role.INITIATOR)
        require(state == State.WAITING_FOR_SE)
        require(message.size >= 80)
        
        var offset = 0
        
        remoteEphemeralKey = message.copyOfRange(offset, offset + 32)
        offset += 32
        mixHash(remoteEphemeralKey!!)
        
        mixKey(calculateDH(ephemeralKeyPair!!.privateKey, remoteEphemeralKey!!))
        
        val encryptedStatic = message.copyOfRange(offset, offset + 48)
        offset += 48
        
        remoteStaticKey = decryptAndHash(encryptedStatic)
            ?: throw NoiseException("Failed to decrypt responder static key")
        
        remoteStaticKeyExpected?.let {
            if (!it.contentEquals(remoteStaticKey!!)) {
                throw NoiseException("Remote key pinning failed")
            }
        }
        
        mixKey(calculateDH(ephemeralKeyPair!!.privateKey, remoteStaticKey!!))
        
        state = State.WAITING_FOR_S
        
        return remoteStaticKey
    }
    
    // ============== Message 3: Initiator -> Responder ==============
    
    fun createMessage3(): ByteArray {
        require(role == Role.INITIATOR)
        require(state == State.WAITING_FOR_S)
        
        val encryptedStatic = encryptAndHash(staticKeyPair.publicKey)
        
        mixKey(calculateDH(staticKeyPair.privateKey, remoteEphemeralKey!!))
        
        val (sendKey, receiveKey) = split()
        
        sendCipher = CipherState(sendKey)
        receiveCipher = CipherState(receiveKey)
        
        state = State.ESTABLISHED
        
        remoteStaticKey?.let {
            val fingerprint = calculateFingerprint(it)
            onHandshakeComplete?.invoke(it, fingerprint)
        }
        
        return encryptedStatic
    }
    
    fun processMessage3(message: ByteArray) {
        require(role == Role.RESPONDER)
        require(state == State.WAITING_FOR_S)
        require(message.size >= 48)
        
        remoteStaticKey = decryptAndHash(message)
            ?: throw NoiseException("Failed to decrypt initiator static key")
        
        remoteStaticKeyExpected?.let {
            if (!it.contentEquals(remoteStaticKey!!)) {
                throw NoiseException("Remote key pinning failed")
            }
        }
        
        mixKey(calculateDH(ephemeralKeyPair!!.privateKey, remoteStaticKey!!))
        
        val (receiveKey, sendKey) = split()
        
        sendCipher = CipherState(sendKey)
        receiveCipher = CipherState(receiveKey)
        
        state = State.ESTABLISHED
        
        remoteStaticKey?.let {
            val fingerprint = calculateFingerprint(it)
            onHandshakeComplete?.invoke(it, fingerprint)
        }
    }
    
    // ============== Transport Encryption ==============
    
    fun encrypt(plaintext: ByteArray): ByteArray {
        require(state == State.ESTABLISHED)
        return sendCipher?.encrypt(plaintext) 
            ?: throw NoiseException("Send cipher not initialized")
    }
    
    fun decrypt(ciphertext: ByteArray): ByteArray {
        require(state == State.ESTABLISHED)
        return receiveCipher?.decrypt(ciphertext)
            ?: throw NoiseException("Receive cipher not initialized")
    }
    
    fun isEstablished(): Boolean = state == State.ESTABLISHED
    fun getState(): State = state
    fun getRemoteStaticKey(): ByteArray? = remoteStaticKey
    
    // ============== Utilitaires cryptographiques ==============
    
    private fun generateKeyPair(): KeyPair {
        // Simplified: in production, use X25519
        val privateKey = ByteArray(32)
        val publicKey = ByteArray(32)
        secureRandom.nextBytes(privateKey)
        secureRandom.nextBytes(publicKey)
        return KeyPair(publicKey, privateKey)
    }
    
    private fun calculateDH(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        // Simplified: in production, use X25519 scalar multiplication
        val shared = ByteArray(32)
        for (i in 0 until 32) {
            shared[i] = (privateKey[i].toInt() xor publicKey[i].toInt()).toByte()
        }
        return shared
    }
    
    private fun mixHash(data: ByteArray) {
        val combined = hash + data
        hash = combined.copyOf(32)
    }
    
    private fun mixKey(inputKeyMaterial: ByteArray) {
        val combined = chainingKey + inputKeyMaterial
        chainingKey = combined.copyOf(32)
        encryptionKey = combined.copyOfRange(32, 64).copyOf(32)
    }
    
    private fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val key = encryptionKey ?: ByteArray(32)
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        
        val ciphertext = cipher.doFinal(plaintext)
        val result = iv + ciphertext
        
        mixHash(result)
        return result
    }
    
    private fun decryptAndHash(ciphertext: ByteArray): ByteArray? {
        return try {
            val key = encryptionKey ?: ByteArray(32)
            if (ciphertext.size < 28) return null
            
            val iv = ciphertext.copyOfRange(0, 12)
            val encrypted = ciphertext.copyOfRange(12, ciphertext.size)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            
            val plaintext = cipher.doFinal(encrypted)
            mixHash(ciphertext)
            plaintext
        } catch (e: Exception) {
            null
        }
    }
    
    private fun split(): Pair<ByteArray, ByteArray> {
        return Pair(chainingKey.copyOf(), chainingKey.reversedArray().copyOf())
    }
    
    private fun calculateFingerprint(publicKey: ByteArray): String {
        return publicKey.copyOfRange(0, 8).toHex()
    }
    
    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }
    
    // ============== Classes internes ==============
    
    data class KeyPair(
        val publicKey: ByteArray,
        val privateKey: ByteArray
    )
    
    class NoiseException(message: String) : Exception(message)
}

/**
 * CipherState pour le chiffrement de transport (post-handshake)
 */
class CipherState(private var key: ByteArray) {
    private var nonce: Long = 0
    private val secureRandom = SecureRandom()
    
    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        iv[11] = (nonce and 0xFF).toByte()
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        
        val ciphertext = cipher.doFinal(plaintext)
        nonce++
        
        return iv + ciphertext
    }
    
    fun decrypt(ciphertext: ByteArray): ByteArray? {
        return try {
            if (ciphertext.size < 28) return null
            
            val iv = ciphertext.copyOfRange(0, 12)
            val encrypted = ciphertext.copyOfRange(12, ciphertext.size)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            
            val plaintext = cipher.doFinal(encrypted)
            nonce++
            plaintext
        } catch (e: Exception) {
            null
        }
    }
}
