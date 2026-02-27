package com.bitchat.android.ui.transport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.transport.api.TransportMetadata
import java.text.SimpleDateFormat
import java.util.*

/**
 * Bulle de message avec indicateur de transport
 * Affiche par quel médium le message est passé (BLE, LoRa, etc.)
 */
@Composable
fun TransportAwareMessageBubble(
    text: String,
    isFromMe: Boolean,
    timestamp: Long,
    transportMetadata: TransportMetadata?,
    isEncrypted: Boolean = true,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isFromMe) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start
    ) {
        // Badge de transport (si disponible)
        transportMetadata?.let { metadata ->
            TransportBadge(metadata = metadata)
        }
        
        // Bulle de message
        Box(
            modifier = Modifier
                .background(
                    color = backgroundColor,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isFromMe) 16.dp else 4.dp,
                        bottomEnd = if (isFromMe) 4.dp else 16.dp
                    )
                )
                .padding(12.dp)
                .widthIn(max = 280.dp)
        ) {
            Column {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Timestamp
                    Text(
                        text = formatTimestamp(timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    
                    // Icône de chiffrement
                    if (isEncrypted) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Encrypted",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                    
                    // Status de livraison (seulement pour messages envoyés)
                    if (isFromMe) {
                        DeliveryStatusIcon(status = DeliveryStatus.DELIVERED)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportBadge(metadata: TransportMetadata) {
    val (icon, color, label) = when (metadata.transport) {
        "ble" -> Triple(
            Icons.Default.Bluetooth,
            Color(0xFF2196F3),
            "BLE"
        )
        "lora" -> Triple(
            Icons.Default.WifiTethering,
            Color(0xFF4CAF50),
            "LoRa"
        )
        "tcp" -> Triple(
            Icons.Default.Language,
            Color(0xFF9C27B0),
            "Internet"
        )
        else -> Triple(
            Icons.Default.DeviceHub,
            Color.Gray,
            metadata.transport.uppercase()
        )
    }
    
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(bottom = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = color
            )
            
            Text(
                text = buildString {
                    append(label)
                    if (metadata.hops > 0) {
                        append(" • ${metadata.hops} hops")
                    }
                    metadata.rssi?.let { rssi ->
                        append(" • ${rssi}dBm")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun DeliveryStatusIcon(status: DeliveryStatus) {
    val (icon, tint) = when (status) {
        DeliveryStatus.SENDING -> Pair(Icons.Default.Schedule, Color.Gray)
        DeliveryStatus.SENT -> Pair(Icons.Default.Check, MaterialTheme.colorScheme.onSurfaceVariant)
        DeliveryStatus.DELIVERED -> Pair(Icons.Default.DoneAll, Color(0xFF4CAF50))
        DeliveryStatus.READ -> Pair(Icons.Default.DoneAll, Color(0xFF2196F3))
        DeliveryStatus.FAILED -> Pair(Icons.Default.Error, Color(0xFFE53935))
    }
    
    Icon(
        imageVector = icon,
        contentDescription = status.name,
        modifier = Modifier.size(14.dp),
        tint = tint
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

enum class DeliveryStatus {
    SENDING,    // En cours d'envoi
    SENT,       // Envoyé (pas encore reçu)
    DELIVERED,  // Livré au destinataire
    READ,       // Lu par le destinataire
    FAILED      // Échec d'envoi
}

/**
 * Preview/Exemple d'utilisation
 */
@Composable
fun MessageListExample() {
    Column {
        // Message distant via BLE
        TransportAwareMessageBubble(
            text = "Salut ! Tu es connecté en BLE ?",
            isFromMe = false,
            timestamp = System.currentTimeMillis() - 60000,
            transportMetadata = TransportMetadata(
                transport = "ble",
                rssi = -65,
                hops = 1
            )
        )
        
        // Ma réponse
        TransportAwareMessageBubble(
            text = "Oui ! Et toi tu relaies via LoRa ?",
            isFromMe = true,
            timestamp = System.currentTimeMillis() - 30000,
            transportMetadata = null // Message local, pas encore relayé
        )
        
        // Message distant via LoRa (longue distance)
        TransportAwareMessageBubble(
            text = "Exact ! Je suis à 3km, le message passe par 2 repeaters LoRa",
            isFromMe = false,
            timestamp = System.currentTimeMillis(),
            transportMetadata = TransportMetadata(
                transport = "lora",
                rssi = -95,
                snr = 8.5f,
                hops = 2
            )
        )
    }
}
