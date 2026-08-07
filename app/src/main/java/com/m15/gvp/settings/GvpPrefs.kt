package com.m15.gvp.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.m15.gvp.llm.GvpLlmModel
import com.m15.gvp.llm.LlmClient
import com.m15.gvp.llm.PipelineMode
import com.m15.gvp.stt.GvpSttModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "gvp_prefs")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * App settings backed by Jetpack DataStore (Preferences). Replaces Cliff's SharedPreferences-based
 * CliffLocalPrefs. Keeps system message + speaker on/off, and adds theme, TTS mute, TTS voice, and
 * the VAD silence threshold.
 */
class GvpPrefs(context: Context) {

    private val ds = context.applicationContext.dataStore

    val systemMessage: Flow<String> = ds.data.map { it[KEY_SYSTEM_MESSAGE] ?: DEFAULT_SYSTEM_MESSAGE }
    val speakerOn: Flow<Boolean> = ds.data.map { it[KEY_SPEAKER_ON] ?: DEFAULT_SPEAKER_ON }
    val ttsMuted: Flow<Boolean> = ds.data.map { it[KEY_TTS_MUTED] ?: false }
    val ttsVoice: Flow<String?> = ds.data.map { it[KEY_TTS_VOICE] }
    val vadSilenceMs: Flow<Long> = ds.data.map { it[KEY_VAD_SILENCE_MS] ?: DEFAULT_VAD_SILENCE_MS }
    val micEnergyThreshold: Flow<Float> =
        ds.data.map { it[KEY_MIC_ENERGY_THRESHOLD] ?: DEFAULT_MIC_ENERGY_THRESHOLD }
    val bargeInEnergyThreshold: Flow<Float> =
        // Clamp to the tuning slider's range: a value saved by the old wider slider (e.g. 0.292) would
        // otherwise sit above the max, invisibly override the default, and block barge-in entirely.
        ds.data.map { (it[KEY_BARGE_IN_ENERGY_THRESHOLD] ?: DEFAULT_BARGE_IN_ENERGY_THRESHOLD)
            .coerceIn(BARGE_IN_THRESHOLD_MIN, BARGE_IN_THRESHOLD_MAX) }
    val themeMode: Flow<ThemeMode> = ds.data.map {
        runCatching { ThemeMode.valueOf(it[KEY_THEME] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }
    /** Selected on-device LiteRT model id (see [GvpLlmModel.CATALOG]). */
    val llmModelId: Flow<String> = ds.data.map { it[KEY_LLM_MODEL_ID] ?: GvpLlmModel.DEFAULT.id }
    /** Selected on-device inference pipeline (Auto / MediaPipe / AICore). */
    val pipelineMode: Flow<PipelineMode> =
        ds.data.map { PipelineMode.fromName(it[KEY_PIPELINE_MODE]) }
    /** Selected on-device STT (Sherpa) model id (see [GvpSttModel.CATALOG]). */
    val sttModelId: Flow<String> = ds.data.map { it[KEY_STT_MODEL_ID] ?: GvpSttModel.DEFAULT.id }
    /** Hugging Face access token for gated (Gemma) model downloads; "" when unset. */
    val hfToken: Flow<String> = ds.data.map { it[KEY_HF_TOKEN] ?: "" }
    /** EXPERIMENT(stt-eval): use ML Kit GenAI STT instead of Sherpa-ONNX (takes effect next session). */
    val useMlKitStt: Flow<Boolean> = ds.data.map { it[KEY_USE_MLKIT_STT] ?: false }

    suspend fun setSystemMessage(value: String) = ds.edit { it[KEY_SYSTEM_MESSAGE] = value }
    suspend fun setSpeakerOn(value: Boolean) = ds.edit { it[KEY_SPEAKER_ON] = value }
    suspend fun setTtsMuted(value: Boolean) = ds.edit { it[KEY_TTS_MUTED] = value }
    suspend fun setTtsVoice(value: String) = ds.edit { it[KEY_TTS_VOICE] = value }
    suspend fun setVadSilenceMs(value: Long) = ds.edit { it[KEY_VAD_SILENCE_MS] = value }
    suspend fun setMicEnergyThreshold(value: Float) = ds.edit { it[KEY_MIC_ENERGY_THRESHOLD] = value }
    suspend fun setBargeInEnergyThreshold(value: Float) =
        ds.edit { it[KEY_BARGE_IN_ENERGY_THRESHOLD] = value }
    suspend fun setThemeMode(value: ThemeMode) = ds.edit { it[KEY_THEME] = value.name }
    suspend fun setLlmModelId(value: String) = ds.edit { it[KEY_LLM_MODEL_ID] = value }
    suspend fun setPipelineMode(value: PipelineMode) = ds.edit { it[KEY_PIPELINE_MODE] = value.name }
    suspend fun setSttModelId(value: String) = ds.edit { it[KEY_STT_MODEL_ID] = value }
    suspend fun setHfToken(value: String) = ds.edit { it[KEY_HF_TOKEN] = value }
    suspend fun setUseMlKitStt(value: Boolean) = ds.edit { it[KEY_USE_MLKIT_STT] = value }

    companion object {
        const val DEFAULT_SYSTEM_MESSAGE = LlmClient.DEFAULT_SYSTEM_MESSAGE
        const val DEFAULT_SPEAKER_ON = true
        const val DEFAULT_VAD_SILENCE_MS = 1_500L
        // Idle voiced-energy bar; raised above the test device's ~0.026 noise floor.
        const val DEFAULT_MIC_ENERGY_THRESHOLD = 0.03f
        // Bar applied while TTS plays. Calibrated on the Pixel 10 (8× gain): echo ~0.002, user "STOP"
        // ~0.04–0.07, so 0.025 sits safely between. See SileroVad.bargeInEnergyThreshold.
        const val DEFAULT_BARGE_IN_ENERGY_THRESHOLD = 0.025f
        // Tuning slider range (also enforced on persisted values, see bargeInEnergyThreshold).
        const val BARGE_IN_THRESHOLD_MIN = 0.01f
        const val BARGE_IN_THRESHOLD_MAX = 0.20f

        private val KEY_SYSTEM_MESSAGE = stringPreferencesKey("system_message")
        private val KEY_SPEAKER_ON = booleanPreferencesKey("speaker_on")
        private val KEY_TTS_MUTED = booleanPreferencesKey("tts_muted")
        private val KEY_TTS_VOICE = stringPreferencesKey("tts_voice")
        private val KEY_VAD_SILENCE_MS = longPreferencesKey("vad_silence_ms")
        private val KEY_MIC_ENERGY_THRESHOLD = floatPreferencesKey("mic_energy_threshold")
        private val KEY_BARGE_IN_ENERGY_THRESHOLD = floatPreferencesKey("barge_in_energy_threshold")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_LLM_MODEL_ID = stringPreferencesKey("llm_model_id")
        private val KEY_PIPELINE_MODE = stringPreferencesKey("pipeline_mode")
        private val KEY_STT_MODEL_ID = stringPreferencesKey("stt_model_id")
        private val KEY_HF_TOKEN = stringPreferencesKey("hf_token")
        private val KEY_USE_MLKIT_STT = booleanPreferencesKey("use_mlkit_stt")
    }
}
