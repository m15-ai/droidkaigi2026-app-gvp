package com.m15.gvp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m15.gvp.VoiceAgentViewModel
import com.m15.gvp.llm.GvpLlmModel
import com.m15.gvp.llm.LlmModelSpec
import com.m15.gvp.llm.LlmStatus
import com.m15.gvp.llm.PipelineMode
import com.m15.gvp.stt.GvpSttModel
import com.m15.gvp.stt.SttModelSpec
import com.m15.gvp.stt.SttStatus
import com.m15.gvp.settings.GvpPrefs
import com.m15.gvp.settings.ThemeMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: VoiceAgentViewModel,
    onBack: () -> Unit
) {
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val ttsMuted by vm.ttsMuted.collectAsStateWithLifecycle()
    val vadSilenceMs by vm.vadSilenceMs.collectAsStateWithLifecycle()
    val micEnergyThreshold by vm.micEnergyThreshold.collectAsStateWithLifecycle()
    val bargeInEnergyThreshold by vm.bargeInEnergyThreshold.collectAsStateWithLifecycle()

    val llmModelId by vm.llmModelId.collectAsStateWithLifecycle()
    val pipelineMode by vm.pipelineMode.collectAsStateWithLifecycle()
    val hfToken by vm.hfToken.collectAsStateWithLifecycle()
    val llmStatus by vm.llmStatus.collectAsStateWithLifecycle()
    val llmStatusDetail by vm.llmStatusDetail.collectAsStateWithLifecycle()

    val sttModelId by vm.sttModelId.collectAsStateWithLifecycle()
    val sttStatus by vm.sttStatus.collectAsStateWithLifecycle()
    val sttStatusDetail by vm.sttStatusDetail.collectAsStateWithLifecycle()
    val useMlKitStt by vm.useMlKitStt.collectAsStateWithLifecycle()

    val voices = remember { vm.availableTtsVoices() }
    val selectedVoice by vm.ttsVoice.collectAsStateWithLifecycle()
    var voiceMenuOpen by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Theme
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Theme")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { vm.setThemeMode(mode) },
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }

            // Inference pipeline (which on-device engine runs the LLM)
            PipelineSection(
                selected = pipelineMode,
                onSelect = { vm.setPipelineMode(it) },
                status = llmStatus,
                statusDetail = llmStatusDetail,
                onRetryTap = { vm.onLlmStatusTap() }
            )

            // Language model (on-device LiteRT via MediaPipe)
            LlmModelSection(
                selectedId = llmModelId,
                models = vm.availableLlmModels,
                onSelect = { vm.setLlmModel(it) },
                hfToken = hfToken,
                onHfTokenChange = { vm.setHfToken(it) },
                status = llmStatus,
                onDownloadTap = { vm.onLlmStatusTap() },
                enabled = pipelineMode != PipelineMode.AICORE
            )

            // Speech recognition model (on-device Sherpa-ONNX)
            SttModelSection(
                selectedId = sttModelId,
                models = vm.availableSttModels,
                onSelect = { vm.setSttModel(it) },
                status = sttStatus,
                statusDetail = sttStatusDetail,
                onDownloadTap = { vm.onSttStatusTap() }
            )

            // EXPERIMENT(stt-eval): swap the recognizer to ML Kit GenAI (Pixel 10, AICore), fed from our
            // own capture via fromPfd so the VAD/barge-in stay live. Applies on the next session.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("ML Kit GenAI STT (experimental)")
                    Text(
                        "Use Google's on-device GenAI recognizer instead of Sherpa-ONNX. Falls back to " +
                            "Basic mode until the Advanced model finishes downloading. Restart the " +
                            "session to apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = useMlKitStt, onCheckedChange = { vm.setUseMlKitStt(it) })
            }

            // TTS mute
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("Mute TTS")
                    Text(
                        "Transcript-only mode (no spoken responses)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = ttsMuted, onCheckedChange = { vm.setTtsMuted(it) })
            }

            // TTS voice
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("TTS voice")
                if (voices.isEmpty()) {
                    Text(
                        "No installed voices detected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = voiceMenuOpen,
                        onExpandedChange = { voiceMenuOpen = it }
                    ) {
                        OutlinedTextField(
                            value = selectedVoice ?: "System default",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Voice") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceMenuOpen) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = voiceMenuOpen,
                            onDismissRequest = { voiceMenuOpen = false }
                        ) {
                            voices.forEach { name ->
                                val isSelected = name == selectedVoice
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    trailingIcon = {
                                        if (isSelected) Icon(Icons.Default.Check, contentDescription = "Selected")
                                    },
                                    colors = if (isSelected) {
                                        MenuDefaults.itemColors(
                                            textColor = MaterialTheme.colorScheme.primary,
                                            trailingIconColor = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        MenuDefaults.itemColors()
                                    },
                                    onClick = {
                                        vm.setTtsVoice(name)
                                        voiceMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // VAD silence threshold
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val seconds = vadSilenceMs / 1000f
                SectionLabel("End-of-utterance silence: ${"%.1f".format(seconds)}s")
                Slider(
                    value = seconds,
                    onValueChange = { v -> vm.setVadSilenceMs((v * 1000).roundToInt().toLong()) },
                    valueRange = 0.5f..3.0f,
                    steps = 4 // 0.5, 1.0, 1.5, 2.0, 2.5, 3.0
                )
            }

            // Mic sensitivity (idle voiced-energy bar)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Mic sensitivity: ${"%.3f".format(micEnergyThreshold)}")
                Text(
                    "Lower = picks up quieter speech but more false triggers from noise. " +
                        "Raise until idle background stops triggering.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = micEnergyThreshold,
                    onValueChange = { v -> vm.setMicEnergyThreshold(v) },
                    valueRange = 0.005f..0.10f
                )
            }

            // Barge-in threshold (stricter bar while TTS plays)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Barge-in threshold: ${"%.3f".format(bargeInEnergyThreshold)}")
                Text(
                    "How loud you must speak to interrupt the assistant. Raise it if the assistant " +
                        "cuts itself off (echo); lower it if talking over it doesn't interrupt. " +
                        "Watch the \"barge-in candidate rms=…\" logs to find your echo level.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = bargeInEnergyThreshold,
                    onValueChange = { v -> vm.setBargeInEnergyThreshold(v) },
                    valueRange = GvpPrefs.BARGE_IN_THRESHOLD_MIN..GvpPrefs.BARGE_IN_THRESHOLD_MAX
                )
            }

            // Clear history
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Data")
                OutlinedButton(onClick = { showClearDialog = true }) {
                    Text("Clear history")
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear history?") },
            text = { Text("This permanently deletes all sessions and transcripts.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    showClearDialog = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
}

private fun formatSize(mb: Int): String =
    if (mb >= 1000) "%.1f GB".format(mb / 1000f) else "$mb MB"

/**
 * Inference-pipeline picker. Lets the user force which on-device engine runs the LLM:
 *  - Auto — prefer the downloaded LiteRT model, else AICore Gemini Nano (the historical default).
 *  - MediaPipe — always the selected LiteRT model below.
 *  - AICore — always Gemini Nano (needs a Tensor G5 Pixel / Feature 636).
 * Owns the engine-level status line so feedback (active engine, AICore availability, download
 * progress) sits directly under the toggle for every mode.
 */
@Composable
private fun PipelineSection(
    selected: PipelineMode,
    onSelect: (PipelineMode) -> Unit,
    status: LlmStatus,
    statusDetail: String?,
    onRetryTap: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Inference pipeline")
        Text(
            "Which on-device engine runs replies. Auto prefers the LiteRT model below and falls back " +
                "to AICore Gemini Nano. AICore forces Gemini Nano and needs a provisioned device " +
                "(Tensor G5 Pixel); MediaPipe forces the LiteRT model.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PipelineMode.entries.forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.label) }
                )
            }
        }

        // Engine-level status / progress from the orchestrator (applies to whichever mode is active).
        if (!statusDetail.isNullOrBlank()) {
            Text(
                statusDetail,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when (status) {
            LlmStatus.DOWNLOADING ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            LlmStatus.UNAVAILABLE ->
                OutlinedButton(onClick = onRetryTap, modifier = Modifier.fillMaxWidth()) {
                    Text("Retry")
                }
            else -> Unit
        }
    }
}

/**
 * On-device LiteRT model picker. Lets the user switch the MediaPipe model, shows per-model size +
 * a downloaded badge, surfaces the orchestrator's status/progress, and (for gated Gemma) takes a
 * Hugging Face token. Downloading reuses the same path as the Setup screen's status card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmModelSection(
    selectedId: String,
    models: List<LlmModelSpec>,
    onSelect: (String) -> Unit,
    hfToken: String,
    onHfTokenChange: (String) -> Unit,
    status: LlmStatus,
    onDownloadTap: () -> Unit,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val selected = GvpLlmModel.byId(selectedId)
    var menuOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Language model")
        Text(
            if (!enabled)
                "Not used while the pipeline is set to AICore — Gemini Nano runs from the system, " +
                    "not a downloaded model. Switch the pipeline to Auto or MediaPipe to pick a model."
            else
                "On-device model used for replies. Larger models are more capable but slower to load " +
                    "and bigger to download. Gemma is license-gated and needs a Hugging Face token below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ExposedDropdownMenuBox(
            expanded = menuOpen && enabled,
            onExpandedChange = { if (enabled) menuOpen = it }
        ) {
            OutlinedTextField(
                value = "${selected.displayName} · ${formatSize(selected.approxSizeMb)}",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text("Model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                models.forEach { model ->
                    val downloaded = remember(model.id) { model.isDownloaded(context) }
                    DropdownMenuItem(
                        text = {
                            Text(
                                buildString {
                                    append(model.displayName)
                                    append(" · ${formatSize(model.approxSizeMb)}")
                                    if (model.authGated) append(" · gated")
                                    if (downloaded) append(" · ✓ downloaded")
                                }
                            )
                        },
                        onClick = {
                            onSelect(model.id)
                            menuOpen = false
                        }
                    )
                }
            }
        }

        // Model-specific controls only matter when MediaPipe is in play; AICore ignores them.
        if (enabled) {
            // Hugging Face token — only relevant for gated (Gemma) downloads.
            if (selected.authGated) {
                OutlinedTextField(
                    value = hfToken,
                    onValueChange = onHfTokenChange,
                    label = { Text("Hugging Face token") },
                    placeholder = { Text("hf_…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Accept the Gemma license on huggingface.co, create a read token, and paste it " +
                        "here. Or adb-push the .task to /data/local/tmp to skip the token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Download the selected model when it isn't on disk yet. Engine-level status (active
            // engine, AICore availability, progress) is shown under the pipeline toggle above.
            if (status == LlmStatus.NEEDS_DOWNLOAD) {
                Button(onClick = onDownloadTap, modifier = Modifier.fillMaxWidth()) {
                    Text("Download ${formatSize(selected.approxSizeMb)}")
                }
            }
        }
    }
}

/**
 * On-device STT (Sherpa-ONNX) model picker. Mirrors [LlmModelSection]: switch the streaming ASR
 * model, show per-model size + a downloaded badge, and surface the engine's status/progress.
 * Switching to a not-yet-downloaded model flags it for download via the button below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SttModelSection(
    selectedId: String,
    models: List<SttModelSpec>,
    onSelect: (String) -> Unit,
    status: SttStatus,
    statusDetail: String?,
    onDownloadTap: () -> Unit
) {
    val context = LocalContext.current
    val selected = GvpSttModel.byId(selectedId)
    var menuOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Speech recognition model (STT)")
        Text(
            "On-device model used to transcribe your speech. Larger models are more accurate but " +
                "slower and bigger to download. All are English streaming Zipformer (Sherpa-ONNX).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
            OutlinedTextField(
                value = "${selected.displayName} · ${formatSize(selected.approxSizeMb)}",
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                models.forEach { model ->
                    val downloaded = remember(model.id) { model.isDownloaded(context) }
                    DropdownMenuItem(
                        text = {
                            Text(
                                buildString {
                                    append(model.displayName)
                                    append(" · ${formatSize(model.approxSizeMb)}")
                                    if (downloaded) append(" · ✓ downloaded")
                                }
                            )
                        },
                        onClick = {
                            onSelect(model.id)
                            menuOpen = false
                        }
                    )
                }
            }
        }

        // Status / progress line from the engine.
        if (!statusDetail.isNullOrBlank()) {
            Text(
                statusDetail,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Download / retry control when the selected model isn't loaded yet.
        when (status) {
            SttStatus.NEEDS_DOWNLOAD ->
                Button(onClick = onDownloadTap, modifier = Modifier.fillMaxWidth()) {
                    Text("Download ${formatSize(selected.approxSizeMb)}")
                }
            SttStatus.UNAVAILABLE ->
                OutlinedButton(onClick = onDownloadTap, modifier = Modifier.fillMaxWidth()) {
                    Text("Retry")
                }
            SttStatus.DOWNLOADING ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else -> Unit
        }
    }
}
