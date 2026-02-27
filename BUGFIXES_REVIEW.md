# Revue des Bugs - BitChat 2.0 Reticulum Integration

## 🐛 Bugs Corrigés

### 1. **ReticulumPacket.kt - Variable dupliquée**
- **Ligne 149 & 163** : Variable `context` déclarée deux fois
- **Fix** : Suppression de la déclaration dupliquée, utilisation de `contextByte`

### 2. **ReticulumPacket.kt - Type mismatch Byte/Int**
- **Ligne 190** : `context` devait être Int, pas Byte
- **Fix** : Changé `context: Byte` → `context: Int`

### 3. **ReticulumTransport.kt - Thread.sleep() dans coroutine**
- **Ligne 316** : `Thread.sleep(100)` utilisé au lieu de `delay(100)`
- **Problème** : Bloque le thread au lieu de suspendre la coroutine
- **Fix** : Changé en `delay(100)`

### 4. **ReticulumTransport.kt - Vérification isAvailable manquante**
- **Ligne 241** : `relayPacket` ne vérifiait pas si le transport était disponible
- **Fix** : Ajout de `|| !isAvailable` dans la condition de garde

### 5. **RNodeDriver.kt - Type mismatch Byte/Int**
- **Lignes 53, 56-65** : Constantes KISS déclarées comme Int au lieu de Byte
- **Fix** : Ajout explicit du type `: Byte` et `.toByte()` pour 0xFF

### 6. **ReticulumAnnounce.kt - Type context**
- **Ligne 227** : `CONTEXT_NONE` (Byte) passé où Int attendu
- **Fix** : Conversion explicite `.toInt() and 0xFF`

---

## ⚠️ Problèmes Architecturaux Identifiés

### 1. **Conflit Hardware LoRa**
```
Problème :
- LoRaTransport (BitChat natif) utilise TBeamLoRaRadio
- ReticulumTransport utilise aussi LoRaRadio
- Les deux ne peuvent pas utiliser le même port USB simultanément
```

**Solutions possibles :**
1. **Mode switch** : L'utilisateur choisit BitChat OU Reticulum
2. **RNode uniquement** : Utiliser RNodeDriver pour les deux protocoles
3. **Deux devices** : Un T-Beam pour BitChat, un pour Reticulum

### 2. **Firmware Incompatible**
```
T-Beam Firmware BitChat  → Protocole binaire custom
T-Beam Firmware RNode    → Protocole KISS TNC
                         → Non interchangeables !
```

**Pour communiquer avec Reticulum :**
- Le T-Beam DOIT avoir le firmware RNode
- Utiliser `RNodeDriver` (créé dans ce fix)
- Le protocole KISS est standard

### 3. **Identity non-persistante**
```kotlin
// ReticulumTransport.kt ligne 364-368
private fun generateIdentity(): ByteArray {
    val random = java.security.SecureRandom()
    val id = ByteArray(16)
    random.nextBytes(id)
    return id
}
```
**Problème** : Nouvelle identité à chaque démarrage
**Impact** : Les autres nœuds voient un nouveau peer à chaque fois
**Fix nécessaire** : Stocker l'identité dans Android Keystore

### 4. **Chiffrement non-implémenté**
- Reticulum utilise Curve25519 + ChaCha20
- Actuellement : clés factices (ByteArray rempli de zéros)
- **TODO** : Intégrer LazySodium pour le chiffrement réel

---

## 🔧 Améliorations Recommandées

### 1. **Gestion d'erreurs**
```kotlin
// Ajouter des try-catch autour des callbacks
private fun handleLoRaReceive(payload: ByteArray, rssi: Int, snr: Float) {
    try {
        val packet = ReticulumPacket.parse(payload)
        packet?.let { handleReticulumPacket(it, rssi, snr) }
    } catch (e: Exception) {
        Log.e(TAG, "Error parsing packet", e)
    }
}
```

### 2. **Logging**
- Ajouter des logs détaillés pour le debugging
- Utiliser `android.util.Log`
- Niveaux : DEBUG pour parsing, INFO pour connexion, ERROR pour failures

### 3. **Timeout et retry**
```kotlin
// Ajouter des timeouts pour les opérations USB
withTimeoutOrNull(5000) {
    loraRadio?.transmit(data)
}
```

### 4. **Validation des packets**
```kotlin
// Vérifier la checksum si présente
// Vérifier la taille minimale
// Vérifier les hops (anti-loop)
```

---

## 🧪 Tests Recommandés

### Tests Unitaires
```kotlin
@Test
fun testReticulumPacketParse() {
    val raw = byteArrayOf(0x00, 0x00, /* ... */)
    val packet = ReticulumPacket.parse(raw)
    assertNotNull(packet)
    assertEquals(ReticulumPacket.PacketType.DATA, packet.type)
}

@Test
fun testPacketHop() {
    val packet = ReticulumPacket(..., hops = 5)
    val hopped = packet.hop()
    assertEquals(6, hopped.hops)
}
```

### Tests d'Intégration
1. **Parse announce** reçu d'un vrai RNode
2. **Transmit data** vers un RNode
3. **Relay packet** vérifier incrémentation hops
4. **USB plug/unplug** gestion reconnexion

### Tests sur Hardware
1. T-Beam avec firmware RNode
2. Connexion USB-C
3. Réception announce Reticulum
4. Envoi message BitChat → visible sur NomadNet

---

## 📋 Checklist avant Release

- [ ] Tester avec un vrai RNode/T-Beam
- [ ] Vérifier la fréquence (868.1 MHz)
- [ ] Confirmer le format des announces
- [ ] Tester le relay (multi-hop)
- [ ] Vérifier la déduplication
- [ ] Tester reconnexion USB
- [ ] Logger toutes les erreurs
- [ ] Documenter les limitations

---

## 🎯 Fonctionnalités Manquantes (TODO)

### Priorité Haute
1. Chiffrement Curve25519 (LazySodium)
2. Stockage persistant de l'identité
3. Path request/response complet
4. Gestion des links chiffrés

### Priorité Moyenne
1. UI sélecteur de transport (BLE/LoRa/Reticulum)
2. Indicateur de qualité de lien
3. Stats détaillées (bytes in/out)
4. Configuration de la fréquence

### Priorité Basse
1. Support Bluetooth pour RNode
2. Interface web de config
3. Mode "bridge" automatique
4. Intégration LXMF

---

## 📝 Notes de Développement

### Architecture Actuelle
```
┌─────────────────────────────────────┐
│        TransportManager             │
├─────────────────────────────────────┤
│  BLETransport  │  ReticulumTransport │
│  (BitChat)     │  (RNodeDriver)      │
└────────────────┴─────────────────────┘
                 │
            ┌────┴────┐
            │ T-Beam  │  (USB Serial)
            │ RNode   │  (Firmware Reticulum)
            └─────────┘
```

### Limitations Connues
1. Un seul transport LoRa actif à la fois
2. Pas de chiffrement E2E (pour l'instant)
3. Identity régénérée à chaque démarrage
4. Pas de persistance des messages
5. MTU limité à 255 bytes (KISS)

---

**Date de la revue** : 27 Février 2026
**Développeur** : Kimi Code CLI
**Statut** : ✅ Bugs critiques corrigés, prêt pour tests hardware
