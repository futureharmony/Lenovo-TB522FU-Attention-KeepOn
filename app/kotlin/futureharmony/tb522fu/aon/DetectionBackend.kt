package futureharmony.tb522fu.aon

import android.service.attention.AttentionService

/** A detection backend provides user-attention state to the DetectionController. */
interface DetectionBackend {
    interface CheckCallback {
        /** @param attentionResult AttentionService.ATTENTION_SUCCESS_PRESENT / _ABSENT */
        fun onResult(attentionResult: Int, reason: String)
        fun onError(failureCode: Int, reason: String)
    }

    /** Begin continuous sensing (AON) or prepare for on-demand checks (camera2). */
    fun start()

    /** Release everything; must tolerate repeated calls. */
    fun stop()

    /** One-shot check; backends with live state answer immediately. */
    fun check(cb: CheckCallback, timeoutMs: Int)

    fun isHealthy(): Boolean

    fun name(): String
}
