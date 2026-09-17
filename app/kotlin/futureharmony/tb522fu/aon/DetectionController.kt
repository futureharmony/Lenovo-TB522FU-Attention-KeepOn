package futureharmony.tb522fu.aon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.attention.AttentionService

/**
 * Backend state machine: AON preferred, Camera2 automatic fallback.
 * - Manages hardware ads610x0 dToF sensor.
 * - Handles active screen keep-on via dynamic bright wakelock & userActivity.
 */
class DetectionController(
    private val context: Context,
    private val config: AonConfig,
) : AonHalBackend.Delegate {

    private val handler = Handler(Looper.getMainLooper())

    private var aon: AonHalBackend? = null
    private var cam2: Camera2Backend? = null
    private var usingAon = false
    private var started = false
    private var lastAonAttemptAt = 0L
    private var startedAt = 0L

    private var screenWakeLock: PowerManager.WakeLock? = null
    private var lastPokeAt = 0L
    private var wakeLockLogged = false

    private val watchdog = object : Runnable {
        override fun run() {
            watchdogTick()
        }
    }

    /** Demand mode: stops the stream after a dwell window with no further checks. */
    private val dwellStop = Runnable {
        if (config.demandMode && started) {
            AonLog.i(TAG, "demand dwell expired -> stopping stream")
            stop()
        }
    }

    override fun onAonRegistered(ok: Boolean, detail: String) {
        if (!ok) {
            AonLog.w(TAG, "AON register failed: $detail")
            if (config.backendMode == AonConfig.BackendMode.AUTO) {
                aon?.stop()
                startCamera2()
            }
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        startedAt = SystemClock.uptimeMillis()
        val mode = config.backendMode
        AonLog.i(TAG, "start mode=$mode")
        if (mode != AonConfig.BackendMode.CAMERA2_ONLY) startAon() else startCamera2()
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(dwellStop)
        releaseScreenWakeLock()
        stopBackends()
        AonLog.i(TAG, "controller stopped")
    }

    /**
     * Unconditional stream teardown, used once at service startup in demand mode.
     * A previous process (or an old app version) may have left the daemon
     * registered after this process died — the daemon's registration survives
     * app death, which would keep the ADSP stream running with no consumer.
     * stop() alone can't do this because it early-returns when !started.
     */
    @Synchronized
    fun hardStop() {
        AonLog.i(TAG, "hard stop: clearing any stale stream registration")
        if (config.backendMode != AonConfig.BackendMode.CAMERA2_ONLY && aon == null) {
            aon = AonHalBackend(context, config, this)
            usingAon = true
        } else if (config.backendMode == AonConfig.BackendMode.CAMERA2_ONLY && cam2 == null) {
            cam2 = Camera2Backend(context, config)
            usingAon = false
        }
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(dwellStop)
        releaseScreenWakeLock()
        stopBackends()
        started = false
    }

    val isStarted: Boolean get() = started

    private fun startAon() {
        lastAonAttemptAt = SystemClock.uptimeMillis()
        if (aon == null) aon = AonHalBackend(context, config, this)
        usingAon = true
        AonLog.i(TAG, "starting AON backend")
        aon?.start()
    }

    private fun startCamera2() {
        if (cam2 == null) cam2 = Camera2Backend(context, config)
        usingAon = false
        AonLog.i(TAG, "switching to Camera2 fallback")
        cam2?.start()
    }

    private fun stopBackends() {
        aon?.stop()
        cam2?.stop()
        usingAon = false
    }

    private fun watchdogTick() {
        try {
            if (!started) return
            if (config.demandMode) return // demand mode has no continuous stream to supervise
            val mode = config.backendMode
            val startupGrace = SystemClock.uptimeMillis() - startedAt < 30_000 && !procWasRegistered()
            if (usingAon && aon != null && !aon!!.isHealthy() && !startupGrace) {
                if (mode != AonConfig.BackendMode.AON_ONLY) {
                    AonLog.w(TAG, "AON unhealthy -> fallback to camera2 (${aon!!.stateSummary()})")
                    aon?.stop()
                    startCamera2()
                }
            } else if (!usingAon && mode == AonConfig.BackendMode.AUTO &&
                SystemClock.uptimeMillis() - lastAonAttemptAt > AON_RETRY_MS
            ) {
                AonLog.i(TAG, "retrying AON after fallback period")
                cam2?.stop()
                startAon()
            }
        } finally {
            handler.postDelayed(watchdog, WATCHDOG_MS)
        }
    }

    private fun procWasRegistered(): Boolean = aon?.everRegistered() == true

    /** Continuous AON state change (arrives ~3/s while registered). */
    override fun onAttentionChanged(attentive: Boolean, mask: Int, source: String) {
        AonLog.d(TAG, "attention $attentive $source")
        if (config.demandMode) return // keep-on is driven by the framework's own check cadence
        if (attentive) {
            acquireScreenWakeLock()
            pokeUserActivity()
        } else {
            releaseScreenWakeLock()
        }
    }

    @Synchronized
    private fun acquireScreenWakeLock() {
        if (!started) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isInteractive) {
                releaseScreenWakeLock()
                return
            }
            if (screenWakeLock == null) {
                screenWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "futureharmony:aon_keepon",
                ).apply { setReferenceCounted(false) }
            }
            screenWakeLock?.acquire(10_000) // Hold for 10s per pulse, renewed continuously while present
            // Log on transition only: this runs once per AON event (~3-10/s) and used to
            // flood aon.log with identical lines, hiding the real log.
            if (!wakeLockLogged) {
                wakeLockLogged = true
                AonLog.i(TAG, "Screen keep-on: WakeLock ACTIVE (fallback; framework contract is primary)")
            }
        } catch (t: Throwable) {
            AonLog.w(TAG, "acquireScreenWakeLock error: ${t.message}")
        }
    }

    @Synchronized
    private fun releaseScreenWakeLock() {
        try {
            screenWakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.release()
                    if (wakeLockLogged) {
                        wakeLockLogged = false
                        AonLog.i(TAG, "Screen keep-on: WakeLock RELEASED (user absent, timeout resumed)")
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun pokeUserActivity() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPokeAt < 2500) return
        lastPokeAt = now

        // 1. Direct PowerManager.userActivity via reflection (boosted by LSPosed hook)
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val m = PowerManager::class.java.getMethod(
                "userActivity",
                Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            m.invoke(pm, now, 2 /* USER_ACTIVITY_EVENT_OTHER */, 0)
        } catch (_: Throwable) {
        }
    }

    /** One-shot check used by onCheckAttention. */
    fun check(cb: DetectionBackend.CheckCallback, timeoutMs: Int) {
        if (config.simulatePresent) {
            AonLog.i(TAG, "simulate-present debug hook")
            cb.onResult(AttentionService.ATTENTION_SUCCESS_PRESENT, "simulated")
            return
        }
        if (usingAon && aon != null) aon!!.check(cb, timeoutMs)
        else if (cam2 != null) cam2!!.check(cb, timeoutMs)
        else cb.onError(AttentionService.ATTENTION_FAILURE_UNKNOWN, "no backend")
    }

    /**
     * Demand-mode check (the onCheckAttention path when demandMode is on):
     * lazily starts the backend on the first check, answers once, then keeps
     * the stream alive for config.demandDwellMs of quiet before stopping it.
     * Rapid consecutive checks reset the dwell window instead of churning
     * register/unregister cycles on the ADSP.
     */
    @Synchronized
    fun demandCheck(cb: DetectionBackend.CheckCallback, timeoutMs: Int) {
        if (!config.demandMode) {
            check(cb, timeoutMs)
            return
        }
        // A pending dwell-stop must not fire while a check is in flight.
        handler.removeCallbacks(dwellStop)

        if (config.simulatePresent) {
            AonLog.i(TAG, "simulate-present debug hook")
            cb.onResult(AttentionService.ATTENTION_SUCCESS_PRESENT, "simulated")
            scheduleDwellStop()
            return
        }

        if (!started) {
            started = true
            startedAt = SystemClock.uptimeMillis()
            val mode = config.backendMode
            AonLog.i(TAG, "demand check: lazy start mode=$mode")
            if (mode != AonConfig.BackendMode.CAMERA2_ONLY) startAon() else startCamera2()
            // No watchdog in demand mode: health/fallback supervision is a
            // continuous-mode concern, and isHealthy() is false while idle.
        }

        val wrapped = object : DetectionBackend.CheckCallback {
            override fun onResult(attentionResult: Int, reason: String) {
                cb.onResult(attentionResult, reason)
                scheduleDwellStop()
            }

            override fun onError(failureCode: Int, reason: String) {
                cb.onError(failureCode, reason)
                scheduleDwellStop()
            }
        }

        if (usingAon && aon != null) aon!!.check(wrapped, timeoutMs)
        else if (cam2 != null) cam2!!.check(wrapped, timeoutMs)
        else {
            cb.onError(AttentionService.ATTENTION_FAILURE_UNKNOWN, "no backend")
            scheduleDwellStop()
        }
    }

    @Synchronized
    private fun scheduleDwellStop() {
        if (!config.demandMode) return
        handler.removeCallbacks(dwellStop)
        handler.postDelayed(dwellStop, config.demandDwellMs)
    }

    fun summary(): String =
        "mode=${config.backendMode} usingAon=$usingAon" +
            (if (usingAon && aon != null) " [${aon!!.stateSummary()}]" else "")

    companion object {
        private const val TAG = "CTRL"
        private const val WATCHDOG_MS = 5_000L
        private const val AON_RETRY_MS = 60_000L

        /** Process-wide shared controller so the service and the settings UI drive one backend. */
        @Volatile private var sInstance: DetectionController? = null

        @JvmStatic
        @Synchronized
        fun get(ctx: Context, cfg: AonConfig): DetectionController =
            sInstance ?: DetectionController(ctx, cfg).also { sInstance = it }
    }
}
