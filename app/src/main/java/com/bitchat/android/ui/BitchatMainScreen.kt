package com.bitchat.android.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// import androidx.hilt.navigation.compose.hiltViewModel
// import com.bitchat.android.ui.transport.ChatViewModel
import com.bitchat.android.ui.transport.TransportStatusBar
import com.bitchat.android.ui.transport.TransportAwareMessageBubble
import kotlinx.coroutines.launch

/**
 * Écran principal BitChat 2.0 - UI finale production-ready
 * 
 * Features:
 * - Gradient background cyberpunk
 * - Animated status indicators
 * - Glassmorphism cards
 * - Smooth transitions
 * - Real-time transport status
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitchatMainScreen(
    // viewModel: ChatViewModel = hiltViewModel()
) {
    // val messages by viewModel.messages.collectAsState()
    // val transportStatus by viewModel.transportStatus.collectAsState()
    val messages = remember { mutableStateListOf<com.bitchat.android.ui.transport.ChatMessage>() }
    val transportStatus = com.bitchat.android.transport.TransportManagerStatus()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Scroll automatique vers dernier message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }
    
    // Gradient background
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0F0F23),
            Color(0xFF1A1A3E),
            Color(0xFF0F0F23)
        )
    )
    
    Scaffold(
        topBar = {
            BitchatTopBar(
                title = "BitChat Mesh",
                transportStatus = transportStatus,
                onMenuClick = { /* TODO */ },
                onSettingsClick = { /* TODO */ }
            )
        },
        bottomBar = {
            BitchatBottomBar(
                transportStatus = transportStatus,
                onSend = { /* viewModel::sendMessage */ },
                onBroadcast = { /* viewModel::broadcastMessage */ }
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBrush)
        ) {
            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically { it / 2 }
                    ) {
                        when {
                            message.isSystem -> SystemMessageCard(text = message.text)
                            else -> TransportAwareMessageBubble(
                                text = message.text,
                                isFromMe = message.isFromMe,
                                timestamp = message.timestamp,
                                transportMetadata = message.transportMetadata,
                                isEncrypted = true
                            )
                        }
                    }
                }
            }
            
            // Floating scroll-to-bottom button
            val showScrollButton by remember {
                derivedStateOf {
                    listState.firstVisibleItemIndex < messages.size - 5
                }
            }
            
            AnimatedVisibility(
                visible = showScrollButton,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 120.dp, end = 16.dp),
                enter = scaleIn(),
                exit = scaleOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll to bottom"
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitchatTopBar(
    title: String,
    transportStatus: com.bitchat.android.transport.TransportManagerStatus,
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                TransportStatusCompact(status = transportStatus)
            }
        },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Menu",
                    tint = Color.White
                )
            }
        },
        actions = {
            // Encryption indicator
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Encrypted",
                    tint = Color(0xFF4CAF50)
                )
            }
            
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF0F0F23).copy(alpha = 0.95f)
        )
    )
}

@Composable
private fun BitchatBottomBar(
    transportStatus: com.bitchat.android.transport.TransportManagerStatus,
    onSend: (String) -> Unit,
    onBroadcast: (String) -> Unit
) {
    Column {
        // Transport status bar
        TransportStatusBar(status = transportStatus)
        
        // Message input
        MessageInputArea(
            onSend = onSend,
            onBroadcast = onBroadcast
        )
    }
}

@Composable
private fun MessageInputArea(
    onSend: (String) -> Unit,
    onBroadcast: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var showBroadcastDialog by remember { mutableStateOf(false) }
    
    Surface(
        color = Color(0xFF1A1A3E).copy(alpha = 0.95f),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Broadcast button
            IconButton(
                onClick = { showBroadcastDialog = true },
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = Color(0xFFFF9800).copy(alpha = 0.2f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.WifiTethering,
                    contentDescription = "Broadcast",
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(22.dp)
                )
            }
            
            // Text field
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { 
                    Text(
                        "Message...",
                        color = Color.White.copy(alpha = 0.5f)
                    )
                },
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
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    focusedContainerColor = Color.White.copy(alpha = 0.05f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            
            // Send button
            val isEnabled = text.isNotBlank()
            val scale by animateFloatAsState(
                targetValue = if (isEnabled) 1f else 0.8f,
                animationSpec = spring()
            )
            
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = isEnabled,
                modifier = Modifier
                    .size(48.dp)
                    .scale(scale)
                    .background(
                        color = if (isEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Gray.copy(alpha = 0.3f)
                        },
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
    
    // Broadcast dialog
    if (showBroadcastDialog) {
        AlertDialog(
            onDismissRequest = { showBroadcastDialog = false },
            containerColor = Color(0xFF1A1A3E),
            title = {
                Text(
                    "Broadcast Multi-Transport",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Envoyer \"$text\" sur tous les transports disponibles (BLE + LoRa) ?",
                    color = Color.White.copy(alpha = 0.8f)
                )
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
                    Text("Broadcast", color = Color(0xFFFF9800))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBroadcastDialog = false }) {
                    Text("Annuler", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }
}

@Composable
private fun SystemMessageCard(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun TransportStatusCompact(
    status: com.bitchat.android.transport.TransportManagerStatus
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // BLE indicator
        StatusDot(
            isActive = status.bleActive,
            count = status.blePeers,
            color = Color(0xFF2196F3),
            label = "BLE"
        )
        
        // LoRa indicator
        StatusDot(
            isActive = status.loraActive,
            count = status.loraPeers,
            color = Color(0xFF4CAF50),
            label = "LoRa"
        )
        
        // Queue indicator
        if (status.pendingPackets > 0) {
            Text(
                text = "⏳ ${status.pendingPackets}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFF9800)
            )
        }
    }
}

@Composable
private fun StatusDot(
    isActive: Boolean,
    count: Int,
    color: Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.alpha(if (isActive) 1f else 0.4f)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (isActive) color else Color.Gray,
                    shape = CircleShape
                )
        )
        
        Text(
            text = buildString {
                append(label)
                if (isActive && count > 0) append(":$count")
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) color else Color.Gray
        )
    }
}
