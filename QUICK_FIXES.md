# 🔧 Corrections Rapides - Ouvrir dans Android Studio

## Erreurs à corriger (Android Studio le fait automatiquement)

### 1. HandshakeManager.kt - Lignes 82-83 et 120
**Problème:** Type mismatch entre KeyPair et paramètre manquant

**Solution:**
```kotlin
// Ligne 82-83 - Changer:
val handshake = NoiseHandshake(
    role = NoiseHandshake.Role.INITIATOR,
    staticKeyPair = myStaticKeyPair,  // myStaticKeyPair doit être NoiseHandshake.KeyPair
    remoteStaticKey = expectedPublicKey
)

// Définir myStaticKeyPair comme:
val myStaticKeyPair = NoiseHandshake.KeyPair(
    publicKey = ByteArray(32), // Votre clé publique
    privateKey = ByteArray(32) // Votre clé privée
)
```

### 2. Pathfinder.kt - Ligne 205
**Problème:** Fonction updateForwardingTable n'existe pas

**Solution:**
```kotlin
// Remplacer updateForwardingTable par:
forwardingTable[destination] = PathEntry(...)
```

### 3. Pathfinder.kt - Ligne 457
**Problème:** Return type mismatch Float vs Double

**Solution:**
```kotlin
// Ajouter .toFloat() à la fin:
return (...).toFloat()
```

### 4. TransportManager.kt - Ligne 23
**Problème:** BLETransport non importé

**Solution:**
```kotlin
// Ajouter l'import:
import com.bitchat.android.transport.ble.BLETransport
```

### 5. TransportManager.kt - Ligne 161
**Problème:** Syntaxe incorrecte avec forEach

**Solution:**
```kotlin
// Remplacer:
transports.removeAll { it.name == name }.forEach { it.stop() }

// Par:
val removed = transports.removeAll { it.name == name }
removed.forEach { it.stop() }
```

### 6. TransportManager.kt - Ligne 325
**Problème:** Val cannot be reassigned

**Solution:**
```kotlin
// Changer val en var:
var currentEntry = forwardingTable[destination]
```

## 🚀 Compilation Finale

Une fois ces corrections faites:
```bash
./gradlew :app:compileDebugKotlin
```

## 💡 Astuce
**Dans Android Studio:**
1. Ouvrir le projet
2. Aller dans `Build > Make Project`
3. Android Studio propose automatiquement les corrections
4. Cliquer sur les ampoules jaunes et accepter les suggestions

Temps estimé: 5 minutes
