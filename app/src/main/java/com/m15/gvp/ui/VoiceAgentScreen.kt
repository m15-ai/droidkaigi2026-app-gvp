package com.m15.gvp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m15.gvp.AgentUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAgentScreen(
    ui: AgentUiState,
    isSpeakerOn: Boolean,
    onSpeakerToggle: () -> Unit,
    onDismissSession: () -> Unit,
    onToggleVisualizer: () -> Unit,
    showVisualizer: Boolean,
    ttsLevel: Float,
    latencyMs: Long?,
    onOpenSettings: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    ui.error?.let { errorMsg ->
        LaunchedEffect(errorMsg) {
            snackbarHostState.showSnackbar(
                message = errorMsg,
                actionLabel = "OK",
                duration = SnackbarDuration.Long
            )
        }
    }

    val lastUserMsg = ui.messages.lastOrNull { it.first == "user" }?.second
    val lastAssistantMsg = ui.messages.lastOrNull { it.first == "assistant" }?.second
    val showLiveUser = !ui.livePartial.isNullOrEmpty() && ui.livePartial != lastUserMsg
    val showLiveAssistant = !ui.assistantLive.isNullOrEmpty() && ui.assistantLive != lastAssistantMsg

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "GVP",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 36.sp,
                        letterSpacing = 1.5.sp
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FloatingActionButton(onClick = onDismissSession) {
                    Icon(Icons.Default.Close, contentDescription = "End Session", modifier = Modifier.size(32.dp))
                }
                FloatingActionButton(
                    onClick = onSpeakerToggle,
                    containerColor = if (isSpeakerOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        imageVector = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.Headset,
                        contentDescription = if (isSpeakerOn) "Speaker On" else "Speaker Off",
                        modifier = Modifier.size(28.dp)
                    )
                }
                FloatingActionButton(
                    onClick = onToggleVisualizer,
                    containerColor = if (showVisualizer) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        imageVector = if (showVisualizer) Icons.Default.Chat else Icons.Default.GraphicEq,
                        contentDescription = "Toggle Visualizer"
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            // Pipeline status chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusChip("VAD", ui.userSpeaking)
                StatusChip("STT", ui.livePartial != null || ui.userSpeaking)
                StatusChip("LLM", ui.isThinking)
                StatusChip("TTS", ui.ttsSpeaking)
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (showVisualizer) {
                    AudioBlobVisualizer(
                        level = ttsLevel,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        accent = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        val isEmpty = ui.messages.isEmpty() && !showLiveUser && !showLiveAssistant
                        if (isEmpty) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "Tap the mic to start talking",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f), reverseLayout = true) {
                                if (showLiveAssistant) {
                                    item { ChatBubble("assistant", ui.assistantLive!!) }
                                }
                                if (showLiveUser) {
                                    item { ChatBubble("user", ui.livePartial!!) }
                                }
                                items(ui.messages.asReversed()) { (role, msg) ->
                                    ChatBubble(role, msg)
                                }
                            }
                            if (ui.isThinking) {
                                Text(
                                    "thinking...",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Pipeline latency (time to first token), shown over both views
                latencyMs?.let { ms ->
                    Text(
                        text = "TTFT $ms ms",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                CircleShape
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, active: Boolean) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ChatBubble(role: String, text: String) {
    val isAssistant = role == "assistant"
    val alignment = if (isAssistant) Alignment.CenterStart else Alignment.CenterEnd
    val bubbleColor = if (isAssistant) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.primaryContainer
    val textColor = if (isAssistant) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onPrimaryContainer

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .background(bubbleColor, MaterialTheme.shapes.large)
                .padding(14.dp)
                .widthIn(max = 320.dp)
        ) {
            Text(text = text, color = textColor, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
