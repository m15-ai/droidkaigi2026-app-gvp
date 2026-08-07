package com.m15.gvp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m15.gvp.llm.LlmStatus
import com.m15.gvp.stt.SttStatus

/** Presentation for the on-device model status card (icon, tint, title, subtitle). */
private data class ModelStatusUi(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val subtitle: String
)

/** A tappable status card with an optional monospace debug line. */
@Composable
private fun StatusCard(
    status: ModelStatusUi,
    detail: String?,
    clickable: Boolean,
    onTap: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onTap() } else Modifier),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(status.icon, contentDescription = null, tint = status.tint)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(status.title, fontWeight = FontWeight.SemiBold)
                Text(
                    status.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun llmStatusUi(s: LlmStatus, modelName: String): ModelStatusUi = when (s) {
    LlmStatus.CHECKING -> ModelStatusUi(
        Icons.Default.Info, MaterialTheme.colorScheme.onSurfaceVariant,
        "LLM: checking…", "Detecting on-device engine"
    )
    LlmStatus.DOWNLOADING -> ModelStatusUi(
        Icons.Default.CloudDownload, MaterialTheme.colorScheme.primary,
        "LLM: downloading model…", "Downloading $modelName"
    )
    LlmStatus.NEEDS_DOWNLOAD -> ModelStatusUi(
        Icons.Default.CloudDownload, MaterialTheme.colorScheme.primary,
        "LLM: tap to download $modelName", "Using stub until the model is downloaded · tap to start"
    )
    LlmStatus.READY -> ModelStatusUi(
        Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary,
        "LLM ready · $modelName", "On-device language model loaded"
    )
    LlmStatus.UNAVAILABLE -> ModelStatusUi(
        Icons.Default.Info, MaterialTheme.colorScheme.error,
        "LLM unavailable", "Using stub LLM — no on-device engine usable · tap to re-check"
    )
}

@Composable
private fun sttStatusUi(s: SttStatus): ModelStatusUi = when (s) {
    SttStatus.CHECKING -> ModelStatusUi(
        Icons.Default.Info, MaterialTheme.colorScheme.onSurfaceVariant,
        "STT: checking…", "Detecting speech model"
    )
    SttStatus.DOWNLOADING -> ModelStatusUi(
        Icons.Default.CloudDownload, MaterialTheme.colorScheme.primary,
        "STT: downloading model…", "On-device speech model is downloading"
    )
    SttStatus.NEEDS_DOWNLOAD -> ModelStatusUi(
        Icons.Default.CloudDownload, MaterialTheme.colorScheme.primary,
        "STT: tap to download model", "Speech recognition needs its model · tap to start"
    )
    SttStatus.READY -> ModelStatusUi(
        Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary,
        "STT ready", "On-device speech recognition loaded"
    )
    SttStatus.UNAVAILABLE -> ModelStatusUi(
        Icons.Default.Info, MaterialTheme.colorScheme.error,
        "STT unavailable", "Speech model failed to load · tap to retry"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    systemMessagePreview: String,
    llmStatus: LlmStatus,
    llmStatusDetail: String?,
    llmModelName: String,
    onLlmStatusTap: () -> Unit,
    sttStatus: SttStatus,
    sttStatusDetail: String?,
    onSttStatusTap: () -> Unit,
    onEditSystemMessage: () -> Unit,
    onStartSession: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // LLM (MediaPipe / Gemini Nano / stub) status card.
            StatusCard(
                status = llmStatusUi(llmStatus, llmModelName),
                detail = llmStatusDetail,
                clickable = llmStatus != LlmStatus.READY && llmStatus != LlmStatus.DOWNLOADING,
                onTap = onLlmStatusTap
            )

            // STT (Sherpa-ONNX) status card.
            StatusCard(
                status = sttStatusUi(sttStatus),
                detail = sttStatusDetail,
                clickable = sttStatus != SttStatus.READY && sttStatus != SttStatus.DOWNLOADING,
                onTap = onSttStatusTap
            )

            // System message editor entry
            Column {
                Text(
                    text = "System Message",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditSystemMessage() },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = systemMessagePreview.ifEmpty { "You are a helpful assistant." },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = "Edit system message"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Primary START button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onStartSession),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Start",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
