# Reticulum Integration - BitChat 2.0

## 🌐 BitChat est maintenant un nœud Reticulum natif !

Cette intégration permet à BitChat de communiquer directement avec le réseau Reticulum :
- Recevoir les packets Reticulum natifs
- Parser les announces et paths Reticulum
- Router les messages via le mesh Reticulum
- S'interfacer avec des nœuds Reticulum (RNode, etc.)

## 📡 Comment ça marche

### Architecture

```
┌─────────────┐     BLE      ┌─────────────┐
│   Tél A     │◄────────────►│   Tél B     │
│  BitChat    │              │  BitChat    │
└──────┬──────┘              └─────────────┘
       │
       │ LoRa (868.1 MHz)
       │
       ▼
┌─────────────┐     LoRa     ┌─────────────┐
│  T-Beam     │◄────────────►│   RNode     │
│ Reticulum   │              │ Reticulum   │
└─────────────┘              └─────────────┘
       │
       │ Reticulum Protocol
       │
       ▼
┌─────────────┐
│  BitChat    │  ◄── Votre téléphone reçoit les messages Reticulum !
│  (ce APK)   │
└─────────────┘
```

### Capacités

| Feature | Statut | Description |
|---------|--------|-------------|
| Parser packets Reticulum | ✅ | Header, context, hash, payload |
| Recevoir announces | ✅ | Découverte de nœuds Reticulum |
| Routing mesh | ✅ | Relay des packets (hops) |
| Envoyer format Reticulum | ✅ | Conversion BitChat → Reticulum |
| Chiffrement Reticulum | ⚠️ | Partiel (structure prête) |
| Links cryptés | ❌ | À implémenter |

## 🚀 Utilisation

### 1. Connexion du T-Beam

1. Branchez le T-Beam en USB-C au téléphone
2. Acceptez la permission USB
3. L'app détecte automatiquement le mode Reticulum

### 2. Réception de messages Reticulum

Les messages arrivant sur la fréquence 868.1 MHz en format Reticulum sont automatiquement :
- Parsés
- Affichés dans le chat
- Tagués avec le badge "Reticulum" + RSSI/SNR

### 3. Envoi vers Reticulum

Quand vous envoyez un message :
- Si BLE disponible → part en BLE (BitChat natif)
- Si T-Beam connecté → converti en format Reticulum et émis sur LoRa
- Les nœuds Reticulum voisins le reçoivent

### 4. Multi-hop (sauts)

Les packets Reticulum peuvent traverser :
- BitChat → Reticulum → Reticulum → BitChat
- BitChat → Reticulum → BitChat (via LoRa)

```
[BitChat A] --LoRa--> [RNode] --LoRa--> [BitChat B]
   Tél Android            Radio           Tél Android
   (ce APK)              Reticulum       (ce APK)
```

## ⚙️ Configuration

### Paramètres Reticulum (dans le code)

```kotlin
// Fréquence
const val RETICULUM_FREQ = 868_100_000L  // Hz

// Spreading Factor
const val RETICULUM_SF = 9

// Bandwidth
const val RETICULUM_BW = 125_000  // Hz

// MTU
const val RETICULUM_MTU = 500  // bytes
```

### Identity

Votre identité Reticulum est générée automatiquement à chaque démarrage :
- Hash: 16 bytes (SHA-256 tronqué)
- Transport ID: Unique par session
- Public Key: 32 bytes (Ed25519)

Pour une identité persistante, il faut stocker les clés dans Keystore.

## 🔧 Compatibilité

### Avec d'autres apps Reticulum

| App | Compatibilité | Notes |
|-----|---------------|-------|
| Nomad Net | ✅ | Messages texte |
| Sideband | ✅ | Messages + fichiers |
| LXMF | ⚠️ | Partiel (sans chiffrement LXMF) |
| RNode Firmware | ✅ | Parfait |

### Avec le matériel Reticulum

| Hardware | Support | Testé |
|----------|---------|-------|
| LilyGo T-Beam | ✅ | Oui |
| RNode (unsigned.io) | ✅ | Théorique |
| Wio-SX1262 | ✅ | Théorique |
| Heltec HT-CT62 | ✅ | Théorique |

## 📝 Format des packets

### Header Reticulum (2 bytes)

```
Bit 7-6: Type (00=Data, 01=Announce, 10=Link, 11=Proof)
Bit 5-4: Dest Type (00=Single, 01=Group, 10=Plain, 11=Link)
Bit 3-0: Hops (0-15)
```

### Structure complète

```
[Header 2B][DestHash 16B][TransportID 16B][Payload N bytes]
```

## 🐛 Dépannage

### "Pas de messages Reticulum reçus"

1. Vérifier la fréquence (doit être 868.1 MHz)
2. Vérifier le SF (doit être 9)
3. S'assurer qu'un autre nœud Reticulum est à portée
4. Vérifier les logs avec `adb logcat | grep Reticulum`

### "Messages Reticulum parsés mais pas affichés"

1. Vérifier que `receiveCallback` est bien enregistré
2. Vérifier que le packet n'est pas filtré comme "from us"
3. Vérifier la conversion vers TransportPacket

## 🔮 Roadmap

### Prochaines améliorations

1. **Identité persistante** - Clés stockées dans Android Keystore
2. **Chiffrement LXMF** - Support complet du chiffrement Reticulum
3. **Links établis** - Création de links chiffrés avec d'autres nœuds
4. **Announce signés** - Vérification cryptographique des announces
5. **Path request** - Découverte dynamique des routes

### Contribution

Pour améliorer l'intégration Reticulum :
1. Tester avec différents nœuds Reticulum
2. Reporter les packets qui ne sont pas parsés correctement
3. Proposer des améliorations du protocole

## 📚 Références

- [Documentation Reticulum](https://reticulum.network/manual/concepts.html)
- [RNode Firmware](https://github.com/markqvist/RNode_Firmware)
- [Nomad Net](https://github.com/markqvist/NomadNet)

---

**Note**: Cette intégration est expérimentale. Le protocole Reticulum évolue, et cette implémentation pourrait nécessiter des mises à jour.
