<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" alt="GVP app icon" width="128">
</p>

# GVP — Google Voice Pipeline

> Part of the [DroidKaigi 2026 demo suite](https://github.com/m15-ai/droidkaigi2026) — see the
> top-level repo for the session overview and the sibling demo apps.

A fully **on-device** voice assistant for Android: you speak, your speech is transcribed locally,
answered by an on-device LLM, and spoken back — **no cloud calls, works in airplane mode** once the
models are downloaded.

```
🎙️ mic ──▶ VAD ──▶ STT ──▶ LLM ──▶ sanitizer ──▶ TTS ──▶ 🔊
        (Silero/   (Sherpa-  (MediaPipe        (Android
         energy)    ONNX)     Gemma/Qwen)        TTS)
```

---

> GVP is one of three Android voice apps presented together at **DroidKaigi 2026**,
> each exploring a different point in the design space:
> - **[Cliff](https://github.com/m15-ai/droidkaigi2026-app-cliff)** — cloud streaming pipeline wired by hand (Deepgram + Claude + Deepgram), client owns the orchestration.
> - **GVP** (this app) — fully **on-device**, no network: Sherpa-ONNX STT + MediaPipe LLM + Android TTS.
> - **[Pica](https://github.com/m15-ai/droidkaigi2026-app-pipecat)** — **thin client over a Pipecat server**: the server owns the pipeline, the phone owns the audio.

## Why fully on-device

> Everything below runs on the phone. No server, no API key, no network — verified in **airplane mode**.

**The thesis.** A complete conversational voice loop — *speech in → speech out* — with **zero cloud calls**.
Every stage (VAD, speech-to-text, the LLM, text-to-speech) executes on the device's own CPU/NPU. Once
the models are downloaded the phone can be fully offline.

**Why on-device.**
- **Privacy** — the user's voice and transcripts never leave the handset.
- **Offline** — works on a plane, underground, or with no signal.
- **Latency** — no network round-trip; **622 ms** time-to-first-token on a Pixel 10 (Tensor G5) for a
  short question, measured on the all-AICore path (**Gemini Nano** LLM + **ML Kit GenAI** STT).
- **Cost** — no per-request inference bill; runs on hardware the user already owns.

**What's actually running locally (all four stages):**

| Stage | On-device engine |
|-------|------------------|
| Voice activity | Energy VAD (Silero ONNX is the planned upgrade) |
| Speech-to-text | **Sherpa-ONNX** streaming Zipformer — *or* Google **ML Kit GenAI** ASR (AICore) |
| LLM | **MediaPipe / LiteRT** (Qwen · Phi-4-mini · Gemma) — *or* **Gemini Nano** via AICore |
| Text-to-speech | Android system TTS |

**Headline proof points.**
- **Two independent on-device LLM runtimes**, user-switchable at runtime: MediaPipe/LiteRT (any supported
  device) and Gemini Nano via AICore (Tensor-class devices). Same for STT: Sherpa-ONNX or ML Kit GenAI.
- **User-selectable model catalog** — swap the LLM (Qwen 0.5B/1.5B, Phi-4-mini, Gemma 1B) or the STT model
  in Settings; downloads on-device, persists across launches.
- **Real conversational turn-taking** — live partial transcripts, VAD-bracketed utterances, and
  **speaker-mode barge-in** (interrupt the assistant mid-sentence) — verified end-to-end on a
  **Pixel 10 (Tensor G5)**.
- **Multi-turn memory** with a rolling 5-exchange context window; transcripts persisted locally (Room).

**One-line summary:** *A full voice assistant — STT, a real LLM, and TTS — running
entirely on a stock Android phone, offline, today.*

---

Everything runs locally:

| Stage | Engine | Default model | Source |
|-------|--------|---------------|--------|
| **VAD** | Energy RMS gate (`SileroVad`) | — (heuristic; Silero ONNX is the planned upgrade) | built-in |
| **STT** | Sherpa-ONNX streaming `OnlineRecognizer` (or **ML Kit GenAI** — experimental) | Streaming Zipformer EN 2023-06-26 (int8, ~73 MB) | [HF: csukuangfj/…en-2023-06-26](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26) |
| **LLM** | MediaPipe LLM Inference (LiteRT) | Qwen2.5-0.5B-Instruct (int8 `.task`, ~546 MB) — **user-selectable**, see below | [HF: litert-community/Qwen2.5-0.5B-Instruct](https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct) |
| **TTS** | Android `TextToSpeech` | system English (US) voice | on-device |

The LLM has a fallback chain: **MediaPipe (LiteRT)** → **Gemini Nano via ML Kit GenAI / AICore** →
**stub**. MediaPipe is preferred because it works on any supported device; AICore's Prompt feature is
only provisioned on a subset of devices (see [Device notes](#device-notes)).

The pipeline is also **user-selectable** in Settings → *Inference pipeline* — **Auto** (the fallback
chain above), **MediaPipe** (force LiteRT), or **AICore** (force Gemini Nano). And within the
MediaPipe path the **model** is selectable from a catalog in Settings → *Language model*
(see [Selecting / swapping the LLM model](#selecting--swapping-the-llm-model)).

The **STT engine** is selectable too. The default is **Sherpa-ONNX**; Settings → *ML Kit GenAI STT*
swaps in Google's on-device **ML Kit GenAI Speech Recognition** (AICore) via `SttRouter`, fed from the
app's own capture (`AudioSource.fromPfd`) so the VAD, barge-in, and echo-suppression keep working. It
uses **Basic** mode (the on-device SODA recognizer) by default: **Advanced** mode (the `nano-v3` GenAI
ASR) works but runs on AICore and **contends with the Gemini Nano LLM** (also AICore), slowing Nano
badly — so Basic is preferred until the LLM runs off AICore (flip `PREFER_ADVANCED` in
`MlKitGenAiSttEngine`). The ML Kit API is **alpha** — Sherpa stays the default/fallback. See
[Device notes](#device-notes).

**TTS is the stock Android engine.** `AndroidTtsEngine` uses the framework
`android.speech.tts.TextToSpeech` API — no Gradle dependency, and the app ships **no voice data**.
Voices come from whatever system TTS engine is installed (typically **Speech Services by Google**,
package `com.google.android.tts`); the Settings voice picker just enumerates the engine's installed
voices and persists your choice. For true airplane-mode operation the engine must have an **offline**
English voice downloaded (system Settings → Text-to-speech output) — Android voices can be
network-backed (`Voice.isNetworkConnectionRequired()`), and GVP does not currently filter those out.

---

## Build & run

**Requirements**
- Android Studio (Ladybug or later)
- JDK 21 — note: Gradle 8.13 supports up to Java 23, so if your Android Studio's embedded JBR is
  Java 25 (2025.3+), point Gradle at a JDK 21 (Settings → Build Tools → Gradle → Gradle JDK, or
  `org.gradle.java.home` in your user-level `~/.gradle/gradle.properties`)
- An **arm64-v8a** device on **Android 12+ (API 31+)**. Tested on Galaxy S25 (SM-S931U1, MediaPipe
  only) and Pixel 10 (Tensor G5, AICore Gemini Nano available).
- Toolchain (pinned in `build.gradle.kts` / `gradle.properties`):
  - AGP 8.6.1, Kotlin **2.2.0**, Compose compiler plugin 2.2.0
  - KSP **2.2.0-2.0.2** with `ksp.useKSP2=false` (legacy KSP1 — required: KSP2 hits
    `unexpected jvm signature V` on Room with this toolchain; ML Kit AARs require Kotlin ≥ 2.2)

**Vendored native dependency:** `app/libs/sherpa-onnx-1.13.2.aar` (the Sherpa-ONNX JNI `.so` +
Kotlin API). Only the arm64-v8a `.so` is packaged (`abiFilters = ["arm64-v8a"]`).

```bash
# Build
./gradlew :app:assembleDebug

# Install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Getting the models

The STT and LLM models are **not** bundled (they'd bloat the APK). Two ways to provision them:

**1. In-app download (default).** Launch the app → the Setup screen shows an **LLM** card and an
**STT** card. When a model is missing the card reads *"tap to download"*; tap it and watch progress.
The default URLs are non–auth-gated (Qwen + the Sherpa model), so no Hugging Face token is needed. Use
Wi-Fi — the LLM model is ~546 MB. (Gated models like Gemma need a token — see
[Selecting / swapping the LLM model](#selecting--swapping-the-llm-model).)

**2. adb push (dev escape hatch).** Drop the model files into `/data/local/tmp/` and the engines
pick them up automatically (resolve order: app `filesDir` → `/data/local/tmp`):

```bash
# LLM
adb push Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task /data/local/tmp/

# STT (all four files)
adb push encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx /data/local/tmp/
adb push decoder-epoch-99-avg-1-chunk-16-left-128.onnx       /data/local/tmp/
adb push joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx   /data/local/tmp/
adb push tokens.txt                                          /data/local/tmp/
```

---

## Selecting / swapping the LLM model

The MediaPipe LLM model is chosen at runtime in **Settings → Language model**. Switching a model
points the engine at a different `.task` file (downloading it first if needed); your choice persists
via DataStore (`GvpPrefs.llmModelId`).

| Model | Size (q8) | Gated? | Notes |
|-------|-----------|--------|-------|
| **Qwen2.5-0.5B-Instruct** | ~0.5 GB | no | Default — fast, lowest latency |
| **Qwen2.5-1.5B-Instruct** | ~1.6 GB | no | More coherent than the 0.5B |
| **Phi-4-mini-instruct** | ~4 GB | no | Strongest reasoning; large download / RAM-heavy |
| **Gemma3-1B-IT** | ~1 GB | **yes** | Google open-weight; needs a Hugging Face token (below) |

**Gated models (Gemma).** Gemma `.task` files on Hugging Face require accepting Google's license and
authenticating. To download Gemma in-app: sign in at huggingface.co, accept the license on the
`litert-community/Gemma3-1B-IT` repo, create a **read token** (`hf_…`), then paste it into the
*Hugging Face token* field that appears in Settings when a gated model is selected. The token is sent
as a Bearer header only to `huggingface.co` (never the redirected CDN) by `ModelDownloader`.
Non-gated models (Qwen, Phi) download anonymously — no token needed.

**adb-push alternative (any model, skips the token).** Drop the `.task` into `/data/local/tmp/` and
the engine resolves it automatically (order: app `filesDir` → `/data/local/tmp`):

```bash
adb push Gemma3-1B-IT_multi-prefill-seq_q8_ekv1280.task /data/local/tmp/
```

**Adding a model to the catalog:** append an `LlmModelSpec` to `GvpLlmModel.CATALOG` (id, display
name, file name, HF URL, approx size, `authGated`, and optional per-model backend/sampling). The
Settings picker and download flow pick it up automatically. STT models are still swapped by editing
`SttModel` (URL + file-name constants); note both STT models name their vocab `tokens.txt`, so delete
the old one when switching.

---

## Architecture

Single-Activity Jetpack Compose app. Manual DI via `ServiceLocator`. State in `VoiceAgentViewModel`
(`StateFlow`); streaming via Kotlin `Flow`. Active listening runs in a foreground service.

```
com.m15.gvp
├── MainActivity.kt              Compose host + NavHost (setup / prompt / voice / settings)
├── VoiceAgentViewModel.kt       Pipeline orchestration, UI state, model warm-up
├── BargeInController.kt         Interrupt TTS when the user speaks (debounce + TTS-onset grace)
├── di/ServiceLocator.kt         Manual dependency wiring
├── audio/
│   ├── AudioCapture.kt          AudioRecord (VOICE_COMMUNICATION, AEC/NS/AGC), 16 kHz mono + 8× gain
│   └── SileroVad.kt             Energy VAD: speech start/end + echo-suppression gate for barge-in
├── stt/
│   ├── SttEngine.kt             STT contract (start/sendPcm/events/close + ownsMic)
│   ├── SttRouter.kt             Selects Sherpa ⇄ ML Kit at session start (settings toggle)
│   ├── SherpaSttEngine.kt       Streaming ASR; VAD-gated; finalizes on VAD SPEECH_END
│   ├── MlKitGenAiSttEngine.kt   ML Kit GenAI ASR (AICore) via fromPfd pipe; Advanced→Basic fallback
│   ├── SttModel.kt              Model files / URLs / resolve+download
│   └── SttStatus.kt
├── llm/
│   ├── LlmOrchestrator.kt       Engine selection (honors PipelineMode) + status + active model name
│   ├── MediaPipeLlmEngine.kt    LiteRT LlmInference (streaming) — primary
│   ├── MlKitLlmEngine.kt        Gemini Nano via ML Kit GenAI Prompt API (AICore) — secondary
│   ├── StubLlmEngine.kt         Canned fallback
│   ├── PipelineMode.kt          User-selectable pipeline: AUTO / MEDIAPIPE / AICORE
│   ├── GvpLlmModel.kt           LiteRT model catalog (LlmModelSpec + CATALOG) — selectable models
│   ├── ModelDownloader.kt       Atomic HTTP download (.part + rename) w/ progress (shared by STT)
│   ├── PromptBuilder.kt         Flatten system msg + history → single prompt
│   └── LlmStatus.kt
├── tts/
│   ├── AndroidTtsEngine.kt      Android TextToSpeech + audio focus
│   └── TtsClient.kt
├── settings/GvpPrefs.kt         DataStore: theme, system msg, VAD/barge-in thresholds, TTS voice…
├── util/
│   ├── SpeechSanitizer.kt       Strip markdown/newlines/emoji from LLM text before TTS
│   ├── StringUtils.kt           areSimilar() — Levenshtein near-duplicate filter
│   └── LatencyTracker.kt        Time-to-first-token
├── data/                        Room: sessions + messages (ConversationRepository)
└── service/VoiceAgentService.kt Foreground service (mic) during active sessions
```

### Pipeline flow
1. `AudioCapture` streams 16 kHz mono PCM (hardware AEC/NS/AGC enabled) with a software gain stage
   (some devices, e.g. Tensor G5, deliver the chain far too quiet — see [Device notes](#device-notes)).
2. `SileroVad` brackets utterances; the active STT engine transcribes the PCM and emits live partials.
   `SherpaSttEngine` finalizes on VAD `SPEECH_END`; `MlKitGenAiSttEngine` (fed the same PCM via an
   `fromPfd` pipe) uses ML Kit's own endpointing. Input is **gated while TTS plays** so neither engine
   transcribes the assistant's echo.
3. On VAD `SPEECH_END` the transcript is finalized; `areSimilar()` drops near-duplicates.
4. `LlmOrchestrator` runs the prompt (system msg + last 5 turns + new turn) through the active engine,
   streaming deltas to the UI and a final completion.
5. `sanitizeForSpeech()` cleans the text → `AndroidTtsEngine.speak()`.
6. **Barge-in:** if the user speaks while TTS plays, `BargeInController` cancels the LLM + stops TTS.

---

## Configuration (Settings screen)

| Setting | Notes |
|---------|-------|
| Theme | System / Light / Dark |
| Inference pipeline | Auto / MediaPipe / AICore — which on-device engine runs the LLM; shows the active-engine status |
| Language model | Pick the on-device LiteRT model (Qwen 0.5B/1.5B, Phi-4-mini, Gemma); shows size + download state. Disabled under AICore |
| ML Kit GenAI STT | Experimental — swap Sherpa-ONNX for ML Kit GenAI ASR (AICore); applies on the next session |
| Hugging Face token | Appears for gated models (Gemma); authorizes the download |
| System message | Persisted prompt prefix |
| TTS mute | Transcript-only mode |
| TTS voice | Pick among installed system voices |
| End-of-utterance silence | VAD silence threshold → controls STT finalization timing |
| Mic sensitivity | Idle voiced-energy bar (raise if ambient noise self-triggers) |
| Barge-in threshold | Bar applied **during TTS**; sits between the AEC residual echo and the user's voice (persisted values are clamped to the slider range) |

---

## System message

The default system message (defined in `llm/LlmClient.kt` as `DEFAULT_SYSTEM_MESSAGE`):

> *You are a helpful voice assistant running entirely on-device. Keep responses concise and conversational — they will be spoken aloud via TTS. Limit responses to 1-3 sentences unless the user asks for detail. Never use bullet points, numbered lists, markdown, emojis, or special formatting. Speak in plain, natural sentences like a real conversation.*

Short, formatting-free replies matter doubly here: less text to synthesize keeps TTS latency down,
and on a small on-device LLM the tight length limit also caps generation time. The message is
user-editable — tap the preview card on the Setup screen to open the prompt editor; the value
persists via DataStore (`GvpPrefs.systemMessage`) and is prepended to every turn by `PromptBuilder`.

---

## Logging & debugging

Tags: `GVP.Pipeline`, `GVP.VAD`, `GVP.STT`, `GVP.STT.MLKit`, `GVP.LLM`, `GVP.TTS`, `GVP.BargeIn`, `AudioCapture`.

```bash
adb logcat -s GVP.Pipeline GVP.VAD GVP.STT GVP.STT.MLKit GVP.LLM GVP.TTS GVP.BargeIn
```

Tells:
- `GVP.STT: Sherpa recognizer loaded (…)` — ASR ready.
- `GVP.LLM: LLM ready → MediaPipe` (or `→ Gemini Nano (AICore)` / `→ stub`).
- `GVP.Pipeline: LLM ⇢ sending: <your words>` — what STT transcribed.
- `GVP.Pipeline: LLM completed → …` — completion reached the ViewModel (TTS will speak).
- `GVP.BargeIn: barge-in suppressed (TTS onset grace …)` vs `BARGE-IN TRIGGER` — echo vs real interrupt.

---

## Device notes

- **Capture level / gain is device-specific.** `AudioCapture` applies a fixed **8× software gain**
  because the VOICE_COMMUNICATION + AEC/NS/AGC chain on the **Pixel 10 (Tensor G5)** delivers signal
  ~20–50× quieter than other devices — loud speech peaked ~0.0126 RMS vs. the 0.027 voiced bar, which
  starved both the VAD and the recognizer (no transcripts at all). The gain restores usable levels for
  every consumer; it's a constant tuned for this device and may need adjusting elsewhere.
- **Barge-in on speakerphone** is energy-based and its thresholds are device-specific. On the Pixel 10
  the hardware AEC is excellent (residual echo ~0.002 RMS, well below the user's ~0.04–0.08), so the
  in-TTS bar (`bargeInEnergyThreshold`, default **0.025**, tunable) cleanly separates the two and
  speaker-mode barge-in works. The VAD also uses a short sustained-onset (60 ms, gap-bridged 400 ms to
  ride the AEC's double-talk chopping) and the `BargeInController` adds a 150 ms debounce + ~1.2 s
  TTS-onset grace. On a device with louder residual echo, raise the bar (Settings) or **use a headset**
  (speaker FAB off) — no acoustic loop, no echo.
- **ML Kit GenAI STT** (experimental) uses **Basic** mode (on-device SODA, API 31+) — confirmed working
  on the Pixel 10 and snappy alongside Gemini Nano. **Advanced** mode (Feature 267, `nano-v3`) needs an
  AICore-provisioned GenAI model (downloads in the background on **Wi-Fi** + charging; verified it
  provisions and transcribes once downloaded), but it runs on AICore and **contends with the Gemini
  Nano LLM** — Nano slowed from ~2s to >6s and replies got cancelled. So the engine prefers Basic
  (`PREFER_ADVANCED = false` in `MlKitGenAiSttEngine`); set it true only when the LLM is off AICore
  (e.g. a MediaPipe/LiteRT model). `GVP.STT.MLKit` logs show the resolved mode (`… checkStatus=…`,
  `… mode AVAILABLE`). Basic (SODA) + Nano do **not** contend.
- **Gemini Nano via AICore** requires the device to have provisioned the ML Kit Prompt feature
  (Feature 636). Confirmed `AVAILABLE` on the **Pixel 10 (Tensor G5)**; on many devices (incl. Galaxy
  S25 at time of writing) it returns `606-FEATURE_NOT_FOUND` even though AICore is installed — that's a
  device-provisioning state, not an app bug. The Setup card's debug line surfaces the exact AICore
  status, and its title shows the active engine ("Gemini Nano" when AICore wins). In **Auto** mode
  MediaPipe is preferred when its model is downloaded; pick **AICore** in *Inference pipeline* to force
  Gemini Nano regardless.

---

## Tech stack

Kotlin · Jetpack Compose · Coroutines/Flow · Room (KSP) · DataStore ·
[MediaPipe LLM Inference](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android) `tasks-genai:0.10.27` ·
[ML Kit GenAI Prompt](https://developers.google.com/ml-kit/genai/prompt/android) `genai-prompt:1.0.0-beta2` ·
[ML Kit GenAI Speech Recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android) `genai-speech-recognition:1.0.0-alpha1` ·
[Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) 1.13.2 (vendored AAR).

## Status & roadmap

Working end-to-end on-device: STT → LLM → TTS, multi-turn history, barge-in, latency tracking,
Room-persisted transcripts, a user-selectable LLM model catalog (Qwen / Phi-4 / Gemma), a
user-selectable inference pipeline (Auto / MediaPipe / AICore), and a selectable STT engine
(Sherpa-ONNX or experimental ML Kit GenAI ASR) — full pipeline incl. speaker-mode barge-in verified
on a Tensor G5 Pixel 10.
### Roadmap — toward model-based turn-taking (Flux-class, on-device)

Barge-in and turn detection are the same frontier. Today both are energy-heuristic (`SileroVad`); the
path to model-based, Deepgram-Flux-style behaviour runs entirely on-device, and the building blocks
already exist (the app ships both the ONNX and LiteRT runtimes). In rough order of leverage:

1. **Neural VAD** — replace the energy `SileroVad` with the real **Silero VAD ONNX** (~2 MB, bundled
   with Sherpa-ONNX) behind the same interface. Kills background-noise false `UserStart`s and makes
   barge-in onset real instead of threshold-tuned. Near-term, highest bang-for-buck (~days).
2. **Eager end-of-turn event model** — adopt Flux's *eager → resume → commit* pattern: start the LLM
   on a medium-confidence endpoint and cancel if the user resumes. The retract plumbing already exists
   (`BargeInController.cancelResponse()` + `llmJob.cancel()` ≈ Flux's `TurnResumed`). Architecture
   only, no new model — and it buys back latency that matters *more* with a slower on-device LLM.
3. **Semantic end-of-turn** — run an on-device turn-detection model (e.g. **Smart Turn v2/v3**,
   Wav2Vec2-based, open-source, reads the waveform not the transcript) at candidate endpoints instead
   of the fixed 1.5 s silence timer. Convert to ONNX/LiteRT; it runs intermittently (at endpoints), so
   its size (~360 MB) / mobile latency is tolerable. This is the actual "Flux-like" capability (~weeks).
4. **Reference-based AEC** — the real ceiling for robust *speakerphone* barge-in, and **not** something
   a turn model fixes: feed the TTS PCM as the echo reference (e.g. WebRTC AEC3) instead of relying on
   blind hardware AEC. Orthogonal workstream; only needed on devices whose hardware AEC is worse than
   the Pixel 10's (whose AEC is good enough that energy barge-in already works here).
5. **Adaptive noise-floor threshold** — track the noise floor so the capture gain (`AudioCapture` 8×)
   and barge-in bar stop needing per-device constants.

Matching cloud-Flux quality exactly is the stretch goal (they run large models on a clean network audio
path); a credible on-device approximation is within reach in 2026. Also planned: optional larger STT
models, and flipping `MlKitGenAiSttEngine.PREFER_ADVANCED` once the LLM moves off AICore so ML Kit
Advanced ASR (`nano-v3`) and Gemini Nano stop contending.

**LLM latency — persistent KV-cache session.** `MediaPipeLlmEngine` currently creates a fresh
`LlmInferenceSession` per turn and re-flattens the full rolling history, so every turn re-prefills the
whole transcript — measured latency climbed ~0.8 s → ~2.0 s as the prompt grew (prefill-dominated; the
5-pair history cap plateaus it). Planned fix: keep a **persistent session** (or MediaPipe session
cloning) that retains the KV cache and feeds only the *new* user turn each request, flattening latency
to ~first-turn levels regardless of conversation depth. Requires reworking barge-in/`cancelResponse`,
session teardown, and history trimming around the now-stateful engine.