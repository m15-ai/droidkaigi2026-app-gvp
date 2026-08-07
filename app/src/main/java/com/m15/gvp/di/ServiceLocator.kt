package com.m15.gvp

import android.content.Context
import android.media.AudioManager
import com.m15.gvp.audio.DefaultAudioCapture
import com.m15.gvp.audio.SileroVad
import com.m15.gvp.data.db.AppDatabase
import com.m15.gvp.data.repo.ConversationRepository
import com.m15.gvp.llm.LlmClient
import com.m15.gvp.llm.LlmOrchestrator
import com.m15.gvp.settings.GvpPrefs
import com.m15.gvp.stt.MlKitGenAiSttEngine
import com.m15.gvp.stt.SherpaSttEngine
import com.m15.gvp.stt.SttEngine
import com.m15.gvp.stt.SttRouter
import com.m15.gvp.tts.AndroidTtsEngine
import com.m15.gvp.tts.TtsClient

/**
 * Manual dependency injection. Mirrors Cliff's ServiceLocator pattern but wires GVP's on-device
 * engines instead of cloud clients — no OkHttp, no backend auth, no API keys.
 */
object ServiceLocator {
    private var initialized = false

    lateinit var repo: ConversationRepository
    lateinit var prefs: GvpPrefs
    lateinit var vad: SileroVad
    lateinit var stt: SttEngine
    /** EXPERIMENT(stt-eval): the router behind [stt], exposed so the VM can flip Sherpa ⇄ ML Kit. */
    lateinit var sttRouter: SttRouter
    lateinit var llm: LlmClient
    lateinit var tts: TtsClient
    lateinit var audio: DefaultAudioCapture
    lateinit var barge: BargeInController
    lateinit var audioManager: AudioManager
    lateinit var appContext: Context

    fun init(ctx: Context) {
        if (initialized) return

        appContext = ctx.applicationContext
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val db = AppDatabase.get(appContext)
        repo = ConversationRepository(db)

        prefs = GvpPrefs(appContext)

        vad = SileroVad()
        sttRouter = SttRouter(
            sherpa = SherpaSttEngine(vad, appContext),
            mlkit = MlKitGenAiSttEngine(vad, appContext)
        )
        stt = sttRouter
        llm = LlmOrchestrator(appContext)
        tts = AndroidTtsEngine(appContext)

        audio = DefaultAudioCapture(appContext)

        barge = BargeInController(llm = llm, tts = tts)

        initialized = true
    }
}
