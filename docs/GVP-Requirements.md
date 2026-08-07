# Android Google Voice Pipeline Requirements

## App Identity

- App name: Google Voice Pipeline
- Short app name: GVP
- Package name: com.m15.gvp
- Primary purpose: Fully on-device voice pipeline demonstrating STT → LLM → TTS using Sherpa-ONNX (streaming Zipformer ASR), an energy-based VAD, a multi-engine LLM orchestrator (MediaPipe LiteRT / Gemini Nano AICore / Stub), and Android TTS. User speaks, speech is transcribed locally, processed by an on-device LLM, and the response is spoken back — no cloud calls during pipeline operation.
- Target user: Developer / power user evaluating on-device voice agent architectures
- Primary language: English (US)
- Supported languages: English only for v1
- Future language expansion: Yes — Sherpa-ONNX supports multilingual models; LiteRT models for other languages may appear on Hugging Face

## Project Constraints

- Android Studio on: Windows 11 (Ladybug or later)
- Kotlin only: Yes
- Minimum SDK: 31 (Android 12) — required for AICore / Gemini Nano compatibility and `setCommunicationDevice()` audio routing
- Target SDK: 35 (Android 15)
- Single Activity: Yes
- MainActivity only: Yes
- No fragments: Yes
- No XML layouts: Yes
- Jetpack Compose only: Yes
- Portrait only: Yes for v1 (locked via `android:screenOrientation="portrait"` in manifest)
- Landscape allowed: No
- Edge-to-edge UI: Yes
- Material Design 3: Yes — Material 3 dynamic color (`dynamicDarkColorScheme()` / `dynamicLightColorScheme()`) with a default `darkColorScheme()` / `lightColorScheme()` fallback on pre-31 devices. System/Light/Dark theme switching via `ThemeMode` enum.
- No BOM characters at start of files: Yes

## Architecture

- Architecture pattern: MVVM
- Main packages:
  - `ui` — Compose screens (`SetupScreen`, `VoiceAgentScreen`, `SettingsScreen`, `AudioBlobVisualizer`) and theme
  - `ui/theme` — `GvpTheme`, `Color.kt`, `Type.kt`
  - `viewmodel` — `VoiceAgentViewModel` (lives at package root as `com.m15.gvp.VoiceAgentViewModel`)
  - `data/db` — Room database (`AppDatabase`), entities (`ChatSession`, `MessageItem`, `TranscriptChunk`)
  - `data/db/dao` — `SessionDao`, `MessageDao`, `TranscriptDao`
  - `data/model` — Domain models (`ChatMessage`)
  - `data/repo` — `ConversationRepository`
  - `service` — `VoiceAgentService` (foreground service)
  - `audio` — `AudioCapture` interface + `DefaultAudioCapture` (16 kHz mono PCM, AEC/NS/AGC, software gain), `SileroVad` (RMS-energy VAD with echo suppression)
  - `stt` — `SttEngine` interface, `SherpaSttEngine` (Sherpa-ONNX streaming ASR), `MlKitGenAiSttEngine` (experimental ML Kit GenAI), `SttRouter` (engine A/B switcher), `SttModel` / `GvpSttModel` (ASR model catalog), `SttStatus`
  - `llm` — `LlmClient` interface, `LlmOrchestrator` (engine selector), `MediaPipeLlmEngine` (LiteRT), `MlKitLlmEngine` (Gemini Nano AICore), `StubLlmEngine` (canned fallback), `GvpLlmModel` / `LlmModelSpec` (model catalog), `ModelDownloader`, `PromptBuilder` / `ChatTemplate`, `PipelineMode`, `LlmStatus`
  - `tts` — `TtsClient` interface, `AndroidTtsEngine` (offline Android TTS with sentence-level streaming)
  - `settings` — `GvpPrefs` (DataStore preferences), `ThemeMode`
  - `util` — `LatencyTracker`, `StringUtils` (`areSimilar` Levenshtein), `LlmStop` (hallucination trimming), `SpeechSanitizer` (markdown/emoji stripping for TTS)
  - `di` — `ServiceLocator` (manual DI singleton)
  - Root package — `App` (Application subclass), `MainActivity`, `VoiceAgentViewModel`, `BargeInController`, `CustomPromptScreen`, `SupportsSpeakerphone` interface
- Application class: `App` extends `Application`, calls `ServiceLocator.init(this)` in `onCreate()`
- Dependency injection: Manual `ServiceLocator` (singleton object). Reused from Cliff's pattern — rewired to instantiate GVP's on-device engines.
- Repository pattern: Yes — `ConversationRepository` wraps `SessionDao` + `MessageDao`, provides `newSession()`, `addUserText()`, `addAssistantText()`, `clearAll()`
- Coroutine / Flow usage: Yes — `StateFlow` for UI state, `SharedFlow` for STT events, `callbackFlow` for MediaPipe LLM streaming, `Flow` for DataStore preferences. Coroutines for model download, LLM generation, audio capture.
- Background service usage: Yes — `VoiceAgentService` foreground service for active listening sessions
- Foreground service notification: Yes — persistent notification "GVP listening / On-device voice pipeline is active" while pipeline is active, with `FOREGROUND_SERVICE_TYPE_MICROPHONE`

## GUI

- Main screen layout: Single-screen voice agent interface. Top bar with app title (centered, bold 36sp). Pipeline status chips row. Orb visualizer or chat transcript (toggleable). FAB column on right. TTFT latency overlay at top center.
- Top app bar: `CenterAlignedTopAppBar` — title "GVP" (ExtraBold, 36sp, 1.5sp letter spacing). Trailing settings gear `IconButton`. Present on all screens (Setup, VoiceAgent).
- Primary controls: FAB column (3 FABs stacked with 16dp spacing: Close session, Speaker toggle, Visualizer/Chat toggle)
- Secondary controls: Settings gear in top bar, clear transcript button (in settings)
- Output panel: LLM response text displayed inline in transcript as a `ChatBubble` (assistant role, left-aligned, `surfaceVariant` background)
- Transcript panel: `LazyColumn` with `reverseLayout = true`. User utterances right-aligned (`primaryContainer`), assistant left-aligned (`surfaceVariant`). Live partials shown inline. Live assistant streaming shown inline. "thinking..." text indicator below transcript.
- Pipeline status chips: Row of 4 `StatusChip` composables (VAD, STT, LLM, TTS). Active = `primary` background, inactive = `surfaceVariant`. Driven by `AgentUiState` flags (`userSpeaking`, `livePartial != null || userSpeaking`, `isThinking`, `ttsSpeaking`).
- Floating Action Buttons: 3 FABs vertically stacked. End session (Close icon, 32dp). Speaker toggle (VolumeUp/Headset, `primary`/`surfaceVariant` container). Visualizer toggle (Chat/GraphicEq, `primary`/`surfaceVariant` container).
- Setup screen: `SetupScreen` — LLM status card (tappable: shows icon/title/subtitle/monospace detail per `LlmStatus`), STT status card (tappable per `SttStatus`), system message preview card (tappable -> editor), full-width "Start" button (primary, 22sp ExtraBold, 48dp height, 24dp corner radius). Status cards have CloudDownload/CheckCircle/Info icons with color-coded tints.
- Dark theme: Yes — via `dynamicDarkColorScheme()` + `darkColorScheme()` fallback
- Light theme: Yes — via `dynamicLightColorScheme()` + `lightColorScheme()` fallback
- Settings screen: Scrollable column navigated from top bar gear icon. Sections: Theme, Pipeline, LLM Model, STT Model, ML Kit STT toggle, TTS Mute, TTS Voice, VAD Silence, Mic Sensitivity, Barge-in Threshold, Clear History.
- Error display: `Snackbar` via `SnackbarHostState` in `VoiceAgentScreen`
- Empty states: Center text "Tap the mic to start talking" when transcript is empty
- TTFT overlay: "TTFT {ms} ms" pill at top center with semi-transparent `surfaceVariant` background, `CircleShape`, 13sp

### Orb Visualizer Details
- **File**: `ui/AudioBlobVisualizer.kt` — pure Compose Canvas, 267 lines, zero external deps
- **Input**: `level: Float` (0-1), `accent: Color` (defaults to `MaterialTheme.colorScheme.primary`), `accent2: Color`
- **Features**: 3-layer blob (stroke, fill, highlight ring), glow core, background mist, sparks on loud moments (threshold 0.28)
- **Animation**: 35ms tweens, 4.2s infinite rotation cycle, shimmer between 0.6-1.0
- **Wobble**: Parametric sine (k1=3, k2=5, k3=7) with level-dependent strength
- **In GVP**: Driven at a fixed level (0.4) while Android TTS plays via `UtteranceProgressListener.onStart()`/`onDone()`, 0 when idle. Held peak decays at 0.92x per frame. Smooth blend: 75% current level + 25% held peak.

## Navigation

- Number of screens: 4 (Setup, CustomPrompt, VoiceAgent, Settings)
- Routes: `"setup"` (start), `"prompt"`, `"voice"`, `"settings"`
- Start screen: Setup (LLM/STT status cards + system message preview + start session button)
- Main screen: VoiceAgent (transcript + visualizer + pipeline chips)
- Settings screen: App preferences
- System message editor: `"prompt"` route -> `CustomPromptScreen` (keyboard-only)
- History/session screen: No — not in v1
- Navigation library: Jetpack Navigation Compose (`androidx.navigation:navigation-compose:2.8.4`)
- Navigation driver: `LaunchedEffect(uiState.sessionActive)` — auto-navigates to `"voice"` on session start, pops back to `"setup"` on session stop
- Back behavior: Settings -> previous screen. VoiceAgent -> `popBackStack("setup")`. Prompt -> popBackStack. Back from Setup exits app.

## Voice Capture

- Microphone input: Yes — `AudioRecord` API via `DefaultAudioCapture`
- Audio source: `MediaRecorder.AudioSource.VOICE_COMMUNICATION` (enables hardware AEC)
- Push-to-talk: No — toggle listening mode via session start/stop
- Toggle listening: Yes — session start/stop via Start button / End Session FAB
- Always listening: No
- Background listening: No for v1 — pipeline stops when `onDestroy()` fires
- Foreground service required: Yes — `VoiceAgentService` while pipeline is active
- Audio format: PCM 16-bit signed, mono
- Sample rate: 16000 Hz (16 kHz)
- Frame size: 20ms frames (~320 samples)
- Buffer size: `max(minBufSize, sampleRate / 20 * 2)` (~50ms, 16-bit mono)
- Software capture gain: 8x (`CAPTURE_GAIN = 8f`) — device-calibrated for Pixel 10 / Tensor G5 where hardware AEC/AGC chain delivers signal ~20-50x quieter than expected. Clamped to +/-32767 to avoid clipping.
- Audio effects: AcousticEchoCanceler, NoiseSuppressor, AutomaticGainControl — attached to `AudioRecord` session if available
- VAD: Energy-based RMS heuristic (named `SileroVad` but currently an energy heuristic, not the real Silero ONNX model — see TODO). Configurable silence threshold (default 1.5s). Configurable energy threshold (default 0.03 RMS). Full-duplex echo suppression with separate barge-in energy threshold (default 0.025), onset window (60ms), and gap tolerance (400ms).
- Wake word: No for v1
- Volume button control: No
- Mic ownership: `SttEngine.ownsMic` flag — when true (ML Kit `fromMic` mode), `DefaultAudioCapture` is not started to avoid dual `AudioRecord` conflicts. Currently `MlKitGenAiSttEngine` uses `fromPfd` so `ownsMic = false`.

## STT

- STT provider: Primary: Sherpa-ONNX (k2-fsa/sherpa-onnx v1.13.2). Experimental: ML Kit GenAI Speech Recognition (AICore).
- Local STT: Yes — fully on-device
- Cloud STT: No
- Realtime streaming: Yes — Sherpa-ONNX streaming ASR with partial results, VAD-gated
- Interim chunks: Yes — live partials displayed in `AgentUiState.livePartial`
- Final chunks: isFinal partials sent to LLM
- Input language: English (US)
- Confidence threshold: No — accept all recognized text
- Duplicate detection: `areSimilar()` (Levenshtein, 0.85 threshold) to skip near-duplicate final transcripts
- STT engine router: `SttRouter` — A/B switches between Sherpa and ML Kit GenAI at session start via `useMlKit` toggle. Merges both engines' event flows.
- **Sherpa-ONNX details**:
  - Recognizer config: `sampleRate=16000`, `featureDim=80`, `numThreads=2`, `provider=cpu`, `enableEndpoint=false` (finalization by VAD, not Sherpa's endpointing), `decodingMethod=greedy_search`
  - Audio frame buffering: up to 50 frames queued while recognizer loads
  - Echo suppression gate: PCM not fed to recognizer while `echoSuppression` is true
  - Model lifecycle: kept loaded for app lifetime; `close()` only resets state
- **ML Kit GenAI STT details** (experimental):
  - Fed via `ParcelFileDescriptor` pipe from app's `AudioCapture` (not ML Kit's `fromMic`)
  - VAD runs on same PCM (barge-in + echo suppression preserved)
  - Prefers `MODE_BASIC` (SODA, API 31+) by default; `MODE_ADVANCED` (GenAI ASR) available on Pixel 10 but contends with Gemini Nano on AICore
  - Recognizer cached across sessions (avoids ~5s re-probe)
- STT model catalog (`GvpSttModel`):

  | Model ID | Display Name | Size | Source |
  |---|---|---|---|
  | `zipformer-en-2023-06-26-int8` (default) | Zipformer EN 2023-06-26 (int8) | ~73 MB | LibriSpeech-trained |
  | `zipformer-en-libri-giga-2023-06-21-int8` | Zipformer EN Libri+GigaSpeech 2023-06-21 (int8) | ~190 MB | LibriSpeech + GigaSpeech |
  | `zipformer-en-2023-06-26-float` | Zipformer EN 2023-06-26 (float, highest accuracy) | ~265 MB | LibriSpeech, float32 |

- Model storage: Per-model subdirectories under `filesDir/stt/<model-id>/`. Fallback to `/data/local/tmp/<file>` (adb-push). Legacy flat `filesDir` fallback for default model.
- Model download: On-demand via `ModelDownloader` from Hugging Face (`csukuangfj` repos). 4 files per model (encoder, decoder, joiner, tokens.txt).
- **Interface contract**:
  ```kotlin
  interface SttEngine {
      val ownsMic: Boolean get() = false
      fun start()
      fun sendPcm(pcm: ShortArray)
      fun events(): Flow<SttEvent>
      fun close()
  }
  
  sealed interface SttEvent {
      data object UserStart : SttEvent
      data object UserStop : SttEvent
      data class Partial(val text: String, val isFinal: Boolean) : SttEvent
  }
  ```

## LLM

- LLM architecture: Multi-engine orchestrator (`LlmOrchestrator`) with 3 backends and user-selectable pipeline mode
- **Engine priority** (under `PipelineMode.AUTO`):
  1. **MediaPipe LiteRT** (`MediaPipeLlmEngine`) — preferred. Runs downloaded `.task` models via `com.google.mediapipe:tasks-genai:0.10.27`. Same runtime as Google's AI Edge Gallery.
  2. **Gemini Nano** (`MlKitLlmEngine`) — ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt:1.0.0-beta2`). Used only where AICore Feature 636 is provisioned (Pixel 8+/9/Galaxy S24, etc.).
  3. **Stub** (`StubLlmEngine`) — canned response fallback so pipeline always runs.
- Pipeline modes (`PipelineMode` enum):
  - `AUTO` — MediaPipe (if downloaded) -> AICore -> offer download -> stub
  - `MEDIAPIPE` — force LiteRT only; never use AICore
  - `AICORE` — force Gemini Nano only; unavailable if device not provisioned
- Local LLM: Yes — fully on-device
- Cloud LLM: No
- Streaming: Yes — MediaPipe streams tokens via `ProgressListener` as `TextDelta` events. ML Kit streams via `generateContentStream()`. Stub simulates streaming at 40ms/word.
- Prompt template: Per-model `ChatTemplate` enum:
  - `GEMMA` — `<start_of_turn>user\n...<end_of_turn>` (system folded into first user turn)
  - `CHATML` — `<|im_start|>role\n...<|im_end|>` (Qwen family)
  - `PHI` — `<|user|>\n...<|end|>` (Phi family)
  - `GENERIC` — plain `User:/Assistant:` prose (ML Kit GenAI)
- System prompt: `DEFAULT_SYSTEM_MESSAGE` = "You are a helpful voice assistant running entirely on-device. Keep responses concise and conversational — they will be spoken aloud via TTS. Limit responses to 1-3 sentences unless the user asks for detail. Never use bullet points, numbered lists, markdown, emojis, or special formatting. Speak in plain, natural sentences like a real conversation."
- Context window strategy: Rolling context — last 5 exchange pairs (user + assistant) + system message prepended. Built by `PromptBuilder.build()`.
- Hallucination handling: `LlmStop` detects turn markers and special tokens in streaming output and halts generation to save compute. `sanitizeForSpeech()` strips markdown, emojis, and formatting that small models emit despite the system prompt.
- LLM model catalog (`GvpLlmModel`):

  | Model ID | Display Name | Size | Gated | Template | Notes |
  |---|---|---|---|---|---|
  | `qwen2.5-0.5b-q8` (default) | Qwen2.5-0.5B-Instruct (q8) | ~530 MB | No | CHATML | Small, fast |
  | `qwen2.5-1.5b-q8` | Qwen2.5-1.5B-Instruct (q8) | ~1.65 GB | No | CHATML | More coherent |
  | `phi-4-mini-q8` | Phi-4-mini-instruct (q8) | ~4.1 GB | No | PHI | Strongest reasoning |
  | `gemma3-1b-q8` | Gemma3-1B-IT (q8) | ~1 GB | Yes (HF license) | GEMMA | Google's open-weight |

- Model storage: `filesDir/<fileName>`. Fallback to `/data/local/tmp/<fileName>` (adb-push escape hatch).
- Model download: On-demand via `ModelDownloader` from Hugging Face LiteRT Community repos. Bearer auth for gated models (Gemma). Progress reporting with ~2 MB throttle.
- MediaPipe config: `Backend.CPU`, `maxTokens=1024`, `temperature=0.7`, `topK=40`, `topP=0.95` (per-model overridable via `LlmModelSpec`)
- Conversation reset: Switching LLM model or pipeline mode clears in-memory chat context (not DB transcripts) for clean A/B comparisons.
- **Interface contract**:
  ```kotlin
  interface LlmClient {
      fun sendUserText(
          text: String,
          history: List<Pair<String, String>> = emptyList(),
          systemMessage: String = DEFAULT_SYSTEM_MESSAGE
      ): Flow<Event>
      fun cancelResponse()
      fun close()
      sealed interface Event {
          data class TextDelta(val text: String) : Event
          data class TextCompleted(val text: String) : Event
          data class Error(val t: Throwable) : Event
      }
  }
  ```

## TTS

- TTS required: Yes
- TTS provider: Android `TextToSpeech` (offline Google TTS voice data)
- Local TTS: Yes — uses offline Android TTS engine
- Cloud TTS: No
- Voice: System default English (US). User can select from installed voices in Settings.
- Streaming audio: **Sentence-level streaming** — `streamDelta()` buffers incoming LLM tokens, detects sentence boundaries (`.!?...` + whitespace), and enqueues each complete sentence. First sentence uses `QUEUE_FLUSH`, subsequent use `QUEUE_ADD`. Playback of sentence N starts while sentence N+1 is still being generated by the LLM.
- Sentence boundary detection: Scans for `.`, `!`, `?`, `...` followed by optional closing quotes/brackets and whitespace. Dots not followed by whitespace (decimals, "U.S.") don't count.
- Interruptible speech: Yes — `stop()` calls `tts.stop()`, clears stream buffer, resets pending utterance count
- Mute option: Yes — `muted` flag, toggle in settings. When muted, `speak()`, `streamDelta()`, `flush()` are no-ops.
- Audio focus handling: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` before first enqueue, abandoned when all utterances finish (tracked by `pendingUtterances` counter). `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH` attributes.
- Speaker routing: `SupportsSpeakerphone` interface (routing owned by `VoiceAgentViewModel.applyRouting()` — SDK 31+ `setCommunicationDevice()` with device-type preference list)
- Visualizer integration: Fixed level (0.4) while speaking via `UtteranceProgressListener.onStart()` -> `onAudioLevel(0.4f)`, `onDone()` -> `onAudioLevel(0f)` (after all pending utterances finish). No raw PCM access.
- **Interface contract**:
  ```kotlin
  interface TtsClient {
      fun speak(text: String)
      fun streamDelta(delta: String)
      fun flush()
      fun stop()
      fun close() {}
      fun isSpeaking(): Boolean
      fun msSinceSpeakStarted(): Long = Long.MAX_VALUE
  }
  ```

## AI Pipeline

- Input flow:
  1. Microphone (`DefaultAudioCapture` 16 kHz PCM mono, 20ms frames, 8x software gain)
  2. Energy VAD gate (`SileroVad.process()` — speech/silence detection with echo suppression)
  3. Sherpa-ONNX streaming STT or ML Kit GenAI STT (partial -> final transcript via `SttRouter`)
  4. Duplicate detection via `areSimilar()` (Levenshtein 0.85)
  5. Final transcript sent to LLM via `sendToLLM()` (rolling 5-pair history + system message)
  6. LLM response streamed token-by-token -> `streamDelta()` splits into sentences -> TTS enqueues each sentence
  7. `sanitizeForSpeech()` applied before TTS enqueue; `LlmStop.trim()` applied on `TextCompleted`
  8. Both user transcript and LLM response displayed in UI via `AgentUiState.messages`
  9. Session logged to Room DB via `ConversationRepository`
- Echo suppression: While TTS plays, `vad.echoSuppression = true` -> VAD switches to stricter barge-in threshold + onset window. Echo suppression cleared 400ms after TTS ends (hangover via `ECHO_SUPPRESS_HANGOVER_MS`). STT does not receive PCM during echo suppression.
- Barge-in: `BargeInController.onSttEvent()` — triggers on `SttEvent.UserStart` with 150ms debounce. Only fires when `tts.isSpeaking()` is true (not during "thinking" — prevents cascade cancellation). 1200ms TTS onset grace (`TTS_ONSET_GRACE_MS`) — suppresses barge-in during initial echo of new utterance. Calls `llm.cancelResponse()` + `tts.stop()`.
- Latency tracking: `LatencyTracker` — `markRequestSent()` when final text dispatched to LLM, `markFirstToken()` on first `TextDelta`. Uses `SystemClock.elapsedRealtime()`. Displayed as "TTFT {ms} ms" pill.
- Session lifecycle:
  - `startSession()`: register audio device callback, apply routing, start foreground service, create DB session, start STT, collect STT events, start mic (unless engine owns it)
  - `stopSession()`: reset UI state, stop TTS/LLM/STT/audio, cancel all jobs, reset audio mode, unregister callback, stop foreground service
- Audio routing: `MODE_IN_COMMUNICATION`. Speaker on -> `TYPE_BUILTIN_SPEAKER`. Speaker off -> `TYPE_BLUETOOTH_SCO` -> `TYPE_WIRED_HEADSET` -> `TYPE_BUILTIN_EARPIECE`. Registered `AudioDeviceCallback` re-applies routing on device add/remove.

## Database

- Room DB: Yes
- KSP: Yes (KSP over KAPT for Room annotation processing)
- Entities:
  - `ChatSession` — `id: String` (UUID, PK), `title: String`, `createdAt: Long`
  - `MessageItem` — `messageId: String` (UUID, PK), `sessionId: String`, `role: String` ("user"/"assistant"), `text: String`, `createdAt: Long`
  - `TranscriptChunk` — `id: String` (UUID, PK), `sessionId: String`, `fromMs: Long`, `toMs: Long`, `text: String`, `isFinal: Boolean`
- DAOs:
  - `SessionDao` — upsert, fetch recent, clear all
  - `MessageDao` — insert, stream all for session, clear all
  - `TranscriptDao` — upsert chunk, clear non-final, clear all
- Repositories: `ConversationRepository` — `newSession()`, `addUserText()`, `addAssistantText()`, `clearAll()`
- DB name: `"gvp.db"`
- Version: 1
- Migrations: No — `fallbackToDestructiveMigration()`
- Seed data: No
- Session log from day one: Yes — every voice interaction is logged
- Export data: No for v1
- Delete data: Yes — `clearAll()` from settings screen (with confirmation dialog)

## Files and Storage

- App-specific storage: Yes — LLM `.task` models and STT model files stored in `filesDir`. Per-model subdirectories for STT (`filesDir/stt/<model-id>/`).
- Shared storage: No
- Model storage: `filesDir/<model.task>` for LLM, `filesDir/stt/<id>/<file>` for STT. Fallback resolution: check filesDir -> `/data/local/tmp/<file>` (adb-push escape hatch). STT default model has additional legacy flat `filesDir` fallback.
- Audio recording storage: No — audio is processed in real-time and not saved to disk
- Cleanup policy: No automatic cleanup. User can clear conversation history manually.
- Export format: N/A for v1

## Network and Cloud

- Internet required: No for pipeline operation — fully offline. Internet used only for on-demand model downloads (LLM and STT models from Hugging Face, Gemini Nano model via AICore).
- Cloud sync: No
- Firebase: No
- Google Cloud: No
- API backend: No
- Authentication: No API keys. Optional Hugging Face access token for gated model downloads (Gemma). Stored in DataStore, sent as Bearer header only to `huggingface.co` (stripped on CDN redirect).
- Model download: `ModelDownloader` — `HttpURLConnection` with manual redirect following (HF 302 -> CDN). `.part` temp file + atomic rename. Cancel-aware. Progress throttled to ~2 MB intervals.
- Offline mode: The entire app is designed for offline operation after initial model download

## Permissions

| Permission | Type | Required | Notes |
|---|---|---|---|
| `RECORD_AUDIO` | Runtime | Yes | Mic capture. Requested via `registerForActivityResult`. |
| `POST_NOTIFICATIONS` | Runtime | Yes (API 33+) | Foreground service notification. |
| `INTERNET` | Install | Yes | Model downloads only. |
| `FOREGROUND_SERVICE` | Install | Yes | Keeps process alive during listening. |
| `FOREGROUND_SERVICE_MICROPHONE` | Install | Yes | Mic-type foreground service. |

- Permission request flow: `requestAndStart()` builds permission array (RECORD_AUDIO + POST_NOTIFICATIONS on 33+), launches via `ActivityResultContracts.RequestMultiplePermissions()`. Session starts in result callback if RECORD_AUDIO granted.

## Security

- API keys stored on device: No API keys for core pipeline. Optional HF token stored in plaintext DataStore (low-sensitivity — only used for gated model downloads).
- Secure storage: No — no sensitive credentials
- User data encryption: No — Room DB unencrypted (user-generated transcripts, low sensitivity)
- PII handling: Transcripts contain user speech — stored locally only, never transmitted. User can delete all data via Settings.
- Logs redaction: Transcript content logged at DEBUG level only; expected to be stripped in release builds

## Settings

- DataStore: Yes — Jetpack DataStore (Preferences), name `"gvp_prefs"`
- Settings (0ersisted keys):

  | Setting | Key | Type | Default | UI Control |
  |---|---|---|---|---|
  | System message | `system_message` | String | `DEFAULT_SYSTEM_MESSAGE` | OutlinedTextField (CustomPromptScreen) |
  | Speaker on/off | `speaker_on` | Boolean | `true` | FAB toggle |
  | TTS muted | `tts_muted` | Boolean | `false` | Switch |
  | TTS voice | `tts_voice` | String? | `null` (system default) | Dropdown |

## Build and Gradle

- Plugins: 
- Kotlin: 2.0+ (K2 compiler) with Compose compiler plugin
- Compile SDK: 35
- Min SDK: 31
- Target SDK: 35
- JVM: 21
- Namespace: `com.m15.gvp`
- Application ID: `com.m15.gvp`
- Version: `0.1` (versionCode 1)
- NDK ABI filters: 
- Compose BOM: `2024.10.01`
- KSP Room config: 

## Testing

- Unit tests: No
- ViewModel tests: No
- Repository tests: No
- Room tests: No
- Manual test checklist:

## Logging and Debugging

- On-screen debug panel: No
- TTFT overlay: "TTFT {ms} ms" pill at top center of VoiceAgentScreen
- Logcat tags:
  - `AudioCapture` — AudioRecord capture events, gain application
  - `GVP.VAD` — Energy VAD speech/silence transitions, barge-in candidate RMS values, echo suppression state
  - `GVP.STT` — Sherpa-ONNX recognition events, model loading, partials
  - `GVP.STT.MLKit` — ML Kit GenAI STT events, mode selection, download status
  - `GVP.LLM` — LLM orchestrator engine selection, MediaPipe/AICore init, generation, model download progress, prompt dispatches, hallucination marker hits
  - `GVP.TTS` — Android TTS init status, speak/stop events
- Session logs: Each session records start/end via `ConversationRepository`; all user + assistant messages stored in Room
- Release logging policy: INFO and above only (DEBUG/VERBOSE stripped in release)
