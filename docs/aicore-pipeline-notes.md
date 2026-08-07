# GVP — On-device Voice Pipeline (AICore / ML Kit GenAI path)

> Slide/reference notes. All paths relative to project root.
> Package base: `app/src/main/java/com/m15/gvp/`
> Wiring hub: **`di/ServiceLocator.kt`** — manual DI, constructs one LLM orchestrator + one STT router + one TTS engine.

## The 3 key files

| Leg | File | Engine |
|-----|------|--------|
| **LLM** | `llm/MlKitLlmEngine.kt` | Gemini Nano (ML Kit GenAI, AICore) |
| **STT** | `stt/SttRouter.kt` → `stt/MlKitGenAiSttEngine.kt` | ML Kit GenAI SODA (vs Sherpa-ONNX) |
| **TTS** | `tts/AndroidTtsEngine.kt` | Android native `TextToSpeech` |

All three are assembled in `di/ServiceLocator.kt`.

---

## 1. Where the LLM is defined

- **`llm/MlKitLlmEngine.kt`** → **Gemini Nano** via ML Kit GenAI `Generation` client (Feature 636).
  Fully on-device; model auto-downloads (~1 GB) on first use; device-gated (Tensor G5 / Pixel 10).
- **`llm/LlmOrchestrator.kt`** picks the engine; **`llm/PipelineMode.kt`** is the user toggle
  (`AUTO` / `MEDIAPIPE` / `AICORE`). Selecting **AICORE** forces Gemini Nano.
- ⚠️ AICore is branch #2 — MediaPipe/LiteRT (Qwen/Gemma `.task`) is the *default*.
  The `GvpLlmModel.kt` catalog belongs to the MediaPipe path, **not** AICore.

```kotlin
private val mediaPipe = MediaPipeLlmEngine(appContext)  // preferred (Qwen/Gemma .task)
private val aiCore    = MlKitLlmEngine()                // Gemini Nano, Feature 636
private val stub      = StubLlmEngine()                 // fallback
```

---

## 2. How STT is picked up

- **`stt/SttRouter.kt`** (`SttRouter`) holds both engines and flips at session start via a settings toggle:
  **Sherpa-ONNX by default**, **ML Kit GenAI selectable** (`useMlKit`).
- The AICore STT engine is **`stt/MlKitGenAiSttEngine.kt`** — ML Kit GenAI `SpeechRecognition`,
  PCM fed through a `ParcelFileDescriptor` pipe so the app's Silero VAD stays in the loop for barge-in.
- ⚠️ Defaults to **Basic SODA**, not Advanced GenAI ASR (`PREFER_ADVANCED = false`) — Advanced contends
  with Gemini Nano on AICore (Nano generation slowed 2s → 6s).

```kotlin
class SttRouter(val sherpa: SherpaSttEngine, val mlkit: MlKitGenAiSttEngine) : SttEngine {
    @Volatile var useMlKit: Boolean = false       // settings toggle "ML Kit GenAI STT"
    override fun start() { active = if (useMlKit) mlkit else sherpa; active.start() }
}
```

---

## 3 & 4. TTS — engine, voices, storage

- **`tts/AndroidTtsEngine.kt`** uses **Android's native `android.speech.tts.TextToSpeech`** —
  **not** ML Kit, not Sherpa. (The only leg of the pipeline that isn't ML Kit GenAI.)
- **Many voices?** Yes — enumerated live from the platform TTS engine (`tts.voices`), surfaced in
  `ui/SettingsScreen.kt` dropdown; selection persisted in `GvpPrefs.ttsVoice`, applied via `setVoiceByName`.
- **Are they all on-device? Where stored?** The voices are **not owned or shipped by GVP.** They come
  from whatever system TTS engine is installed — typically **"Speech Services by Google," package
  `com.google.android.tts`.** GVP just lists, selects, and persists a voice name.
- Voice data is **stored and managed by that external engine**, not in the app (no voice-asset dir in
  the project). **"All on-device" is NOT guaranteed** — Android voices can be network-backed
  (`Voice.isNetworkConnectionRequired()`), and GVP doesn't currently filter those out.

```kotlin
fun availableVoices(): List<Voice> = runCatching { tts.voices?.toList().orEmpty() }.getOrDefault(emptyList())
fun setVoiceByName(name: String?) { availableVoices().firstOrNull { it.name == name }?.let { tts.voice = it } }
```

---

## Honest caveat for the talk

A "pure AICore" story is only *partly* true — the **LLM and STT** can run on AICore / ML Kit GenAI,
but **TTS is always plain Android `TextToSpeech`** regardless of pipeline mode.