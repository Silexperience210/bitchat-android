package com.bitchat.android.ui.transport

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
// import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * Écran de chat principal avec support multi-transport
 * 
 * Architecture:
 * - TopBar avec statut compact des transports
 * - Liste des messages avec badges de transport
 * - BottomBar avec input et bouton d'envoi
 * - TransportStatusBar persistant
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportChatScreen(
    // viewModel: ChatViewModel = hiltViewModel()
) {
    // val messages by viewModel.messages.collectAsState()
    // val transportStatus by viewModel.transportStatus.collectAsState()
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val transportStatus = com.bitchat.android.transport.TransportManagerStatus()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Scroll automatique vers le dernier message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BitChat Mesh")
                        Text(
                            text = buildStatusText(transportStatus),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Indicateur compact des transports
                    TransportStatusCompact(
                        status = transportStatus,
                        modifier = Modifier.padding(end = 8.dp)
                    )

                    // Menu
                    IconButton(onClick = { /* TODO: Menu */ }) {
                        Icon(Icons.Default.MoreVert, "Menu")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                // Barre de statut des transports (détaillée)
                TransportStatusBar(status = transportStatus)

                // Input de message
                MessageInputBar(
                    onSend = { /* viewModel::sendMessage */ },
                    onBroadcast = { /* viewModel::broadcastMessage */ }
                )
            }
        }
    ) { padding ->
        // Liste des messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                when {
                    message.isSystem -> SystemMessage(text = message.text)
                    else -> TransportAwareMessageBubble(
                        text = message.text,
                        isFromMe = message.isFromMe,
                        timestamp = message.timestamp,
                        transportMetadata = message.transportMetadata,
                        isEncrypted = !message.isFromMe // Messages entrants toujours chiffrés
                    )
                }
            }
        }
    }
}

/**
 * Texte de statut pour la TopBar
 */
private fun buildStatusText(status: com.bitchat.android.transport.TransportManagerStatus): String {
    return buildString {
        val activeTransports = mutableListOf<String>()
        if (status.bleActive) activeTransports.add("BLE:${status.blePeers}")
        if (status.loraActive) activeTransports.add("LoRa:${status.loraPeers}")

        if (activeTransports.isEmpty()) {
            append("Aucun transport actif")
        } else {
            append(activeTransports.joinToString(" • "))
        }

        if (status.pendingPackets > 0) {
            append(" • Queue:${status.pendingPackets}")
        }
    }
}

/**
 * Barre d'input pour envoyer des messages
 */
@Composable
private fun MessageInputBar(
    onSend: (String) -> Unit,
    onBroadcast: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var showBroadcastDialog by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Bouton broadcast (long press pour options)
            IconButton(
                onClick = { showBroadcastDialog = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WifiTethering,
                    contentDescription = "Broadcast",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Champ de texte
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message...") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    }
                ),
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge
            )

            // Bouton envoi
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    }
                )
            }
        }
    }

    // Dialog broadcast
    if (showBroadcastDialog) {
        AlertDialog(
            onDismissRequest = { showBroadcastDialog = false },
            title = { Text("Broadcast Multi-Transport") },
            text = {
                Text("Envoyer ce message sur tous les transports disponibles (BLE + LoRa) ?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onBroadcast(text)
                            text = ""
                        }
                        showBroadcastDialog = false
                    }
                ) {
                    Text("Broadcast")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBroadcastDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}

/**
 * Message système (centré, style différent)
 */
@Composable
private fun SystemMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * Preview de l'écran complet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportChatScreenPreview() {
    // Données de test
    val previewMessages = listOf(
        ChatMessage(
            id = "1",
            text = "Salut !",
            isFromMe = false,
            timestamp = System.currentTimeMillis() - 60000,
            status = MessageStatus.DELIVERED,
            transportMetadata = com.bitchat.android.transport.api.TransportMetadata(
                transport = "ble",
                rssi = -65,
                hops = 1
            )
        ),
        ChatMessage(
            id = "2",
            text = "Hey ! Je suis connecté via LoRa à 2km",
            isFromMe = true,
            timestamp = System.currentTimeMillis() - 30000,
            status = MessageStatus.DELIVERED,
            transportMetadata = com.bitchat.android.transport.api.TransportMetadata(
                transport = "lora",
                rssi = -95,
                hops = 2
            )
        )
    )

    val previewStatus = com.bitchat.android.transport.TransportManagerStatus(
        bleActive = true,
        blePeers = 3,
        bleQuality = 0.85f,
        loraActive = true,
        loraPeers = 1,
        loraQuality = 0.6f,
        pendingPackets = 0
    )

    MaterialTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("BitChat Mesh") },
                    actions = {
                        TransportStatusCompact(status = previewStatus)
                    }
                )
            },
            bottomBar = {
                Column {
                    TransportStatusBar(status = previewStatus)
                    MessageInputBar(onSend = {}, onBroadcast = {})
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(previewMessages) { message ->
                    TransportAwareMessageBubble(
                        text = message.text,
                        isFromMe = message.isFromMe,
                        timestamp = message.timestamp,
                        transportMetadata = message.transportMetadata
                    )
                }
            }
        }
    }
}
