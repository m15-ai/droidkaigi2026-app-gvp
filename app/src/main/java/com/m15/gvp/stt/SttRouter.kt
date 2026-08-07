package com.m15.gvp.stt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * EXPERIMENT(stt-eval): selects the active STT engine at session start so Sherpa-ONNX and ML Kit
 * GenAI can be A/B'd at runtime without rewiring the ViewModel. The VM holds one stable [SttEngine]
 * reference (this router); [useMlKit] (driven by the settings toggle) decides which delegate the next
 * [start] binds to.
 *
 * [events] merges both delegates' streams — only the active one emits, so the merge is safe and the
 * VM's collector never changes. [ownsMic] reflects the active delegate so the VM knows whether to
 * start its own mic capture ([MlKitGenAiSttEngine] owns the mic; [SherpaSttEngine] is fed via PCM).
 */
class SttRouter(
    val sherpa: SherpaSttEngine,
    val mlkit: MlKitGenAiSttEngine
) : SttEngine {

    /** Toggled from settings; takes effect on the next [start] (i.e. next session). */
    @Volatile var useMlKit: Boolean = false

    @Volatile private var active: SttEngine = sherpa

    override val ownsMic: Boolean get() = active.ownsMic

    override fun start() {
        active = if (useMlKit) mlkit else sherpa
        active.start()
    }

    override fun sendPcm(pcm: ShortArray) = active.sendPcm(pcm)

    override fun events(): Flow<SttEvent> = merge(sherpa.events(), mlkit.events())

    override fun close() = active.close()
}
