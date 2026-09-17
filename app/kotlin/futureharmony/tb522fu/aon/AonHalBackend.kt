package futureharmony.tb522fu.aon

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.attention.AttentionService
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * AON ULP backend: Camera 3 (OG0VE) Qualcomm AON FDPRO Hardware Sensing.
 * - Communicates with native aon_daemon (IAONService FDPRO pipeline) over the
 *   aon_cmd / aon_evt file protocol.
 * - Delivers continuous face presence (mask 0x1) and gaze detection (mask 0x4 / gaze=1).
 * - Auxiliary dToF (ads610x0) distance tracking.
 *
 * Stop path (Plan A): stop() writes state=stop into aon_cmd with an app-owned
 * monotonic seq; the daemon's own unregister branch (transact 3) tears the
 * FDPRO use case down. No kill(2) is ever needed.
 */
class AonHalBackend(
    private val context: Context,
    private val config: AonConfig,
    private val delegate: Delegate,
) : DetectionBackend {

    interface Delegate {
        fun onAonRegistered(ok: Boolean, detail: String)
        fun onAttentionChanged(attentive: Boolean, mask: Int, source: String)
    }

    private val handler: Handler
    private val cmdFile: File
    private val evtFile: File

    private var sensorManager: SensorManager? = null
    @Volatile private var lastSensorDist = -1.0f
    @Volatile private var lastSensorAt = 0L

    @Volatile private var started = false
    @Volatile private var registered = false
    @Volatile private var everRegistered = false
    @Volatile private var lastMask = 0
    @Volatile private var absentStreak = 0
    @Volatile private var lastEventAt = 0L
    @Volatile private var lastGazeAt = 0L
    @Volatile private var lastHbtAt = 0L
    @Volatile private var tailGen = 0
    private var cmdSeq = 0L

    private val sensorListener = object : SensorEventListener {
        private var lastLogAt = 0L
        override fun onSensorChanged(event: SensorEvent?) {
            val v = event?.values ?: return
            if (v.isEmpty()) return
            val now = SystemClock.uptimeMillis()
            lastSensorAt = now
            lastSensorDist = v[0]
            if (now - lastLogAt > 2000) {
                lastLogAt = now
                AonLog.d(TAG, "dToF dist=${v[0].toInt()}mm")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    init {
        val thread = HandlerThread("aon-ul")
        thread.start()
        handler = Handler(thread.looper)
        val dir = context.filesDir
        cmdFile = File(dir, "aon_cmd")
        evtFile = File(dir, "aon_evt")
    }

    override fun name() = "aon"

    private fun daemonAlive(): Boolean =
        lastHbtAt > 0 && SystemClock.uptimeMillis() - lastHbtAt < 20_000

    override fun isHealthy(): Boolean {
        val now = SystemClock.uptimeMillis()
        val daemon = registered && daemonAlive()
        val freshEvent = lastEventAt > 0 && now - lastEventAt < 10_000
        return daemon || freshEvent
    }

    fun lastEventAgeMs(): Long =
        if (lastEventAt == 0L) -1 else SystemClock.uptimeMillis() - lastEventAt

    fun lastMask(): Int = lastMask

    fun everRegistered(): Boolean = everRegistered

    fun stateSummary(): String =
        "registered=$registered daemonHbt=${daemonAlive()} dist=${lastSensorDist.toInt()}mm" +
            " mask=0x" + Integer.toHexString(lastMask) +
            " lastEventAgeMs=${lastEventAgeMs()}"

    override fun start() {
        if (started) return
        started = true
        handler.post { doStart() }
    }

    private fun doStart() {
        if (sensorManager == null) {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        }
        if (config.dtofAux) {
            sensorManager?.let { sm ->
                for (s in sm.getSensorList(Sensor.TYPE_ALL)) {
                    val n = s.name.lowercase()
                    val t = s.stringType.lowercase()
                    if (s.type == 65618 || s.type == 65619 ||
                        n.contains("wakeup_aon") || t.contains("wakeup_aon") ||
                        n.contains("presence_wakeup")
                    ) {
                        sm.registerListener(sensorListener, s, SensorManager.SENSOR_DELAY_NORMAL, handler)
                        AonLog.i(TAG, "Auxiliary dToF sensor bound: ${s.name}")
                        break
                    }
                }
            }
        }

        val startOffset = if (evtFile.exists()) evtFile.length() else 0L
        writeCmd(
            "state=start 0 ${config.aonServiceType} ${config.aonEvtMask} " +
                "${config.aonAlgoIdx} ${config.aonWidth} ${config.aonHeight} ${config.aonDeliveryPerSec}"
        )
        AonLog.i(TAG, "start requested (Camera3 AON daemon dps=${config.aonDeliveryPerSec})")
        val gen = ++tailGen
        Thread({ tailLoop(gen, startOffset) }, "aon-tail").apply { isDaemon = true }.start()
    }

    private fun tailLoop(gen: Int, startOffset: Long) {
        try {
            while (started && gen == tailGen && !evtFile.exists()) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return
                }
            }
            if (!started || gen != tailGen) return
            java.io.RandomAccessFile(evtFile, "r").use { raf ->
                raf.seek(startOffset)
                while (started && gen == tailGen) {
                    val curLen = raf.length()
                    if (curLen < raf.filePointer) {
                        raf.seek(0) // daemon truncated/rotated the event file
                    }
                    var line = raf.readLine()
                    while (line != null) {
                        if (line.isNotEmpty()) handleLine(line)
                        line = raf.readLine()
                    }
                    try {
                        Thread.sleep(120)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        } catch (e: Throwable) {
            AonLog.w(TAG, "tail error: $e")
        }
    }

    private fun handleLine(line: String) {
        try {
            when {
                line.startsWith("HBT ") -> lastHbtAt = SystemClock.uptimeMillis()
                line.startsWith("REG ") -> {
                    if (line.contains("ok=-1")) {
                        registered = false
                        AonLog.i(TAG, "Camera3 AON unregistered $line")
                    } else {
                        val ok = line.contains("ok=1")
                        registered = ok
                        if (ok) {
                            everRegistered = true
                            AonLog.i(TAG, "Camera3 AON register SUCCESS $line")
                            delegate.onAonRegistered(true, line)
                        } else {
                            AonLog.w(TAG, "Camera3 AON register FAILED $line")
                            delegate.onAonRegistered(false, line)
                        }
                    }
                }
                line.startsWith("EVT pres=0") -> {
                    lastEventAt = SystemClock.uptimeMillis()
                    lastMask = 0
                    absentStreak++
                    delegate.onAttentionChanged(false, 0, "Camera3 AON (no face)")
                }
                line.startsWith("EVT b=1 ") -> {
                    val vi = line.indexOf("vals=")
                    if (vi < 0) return
                    val parts = line.substring(vi + 5).split(",")
                    val vals = IntArray(parts.size) { parts[it].trim().toInt() }
                    val mask = if (vals.isNotEmpty()) vals[0] else 0
                    lastEventAt = SystemClock.uptimeMillis()
                    lastMask = mask
                    val hasGaze = (mask and 0x4) != 0 || (vals.size >= 17 && vals[16] == 1)
                    if (hasGaze) lastGazeAt = lastEventAt
                    val att = isAttentive()
                    absentStreak = if (att) 0 else absentStreak + 1
                    AonLog.d(TAG, "event mask=0x" + Integer.toHexString(mask) +
                        " hasGaze=$hasGaze attentive=$att")
                    delegate.onAttentionChanged(att, mask, "Camera3 AON (gaze=$hasGaze)")
                }
                line.startsWith("DAEMON ") || line.startsWith("STARTING") || line.startsWith("CFG ") ->
                    AonLog.i(TAG, line)
            }
        } catch (t: Throwable) {
            AonLog.w(TAG, "line parse failed: $line")
        }
    }

    @Synchronized
    private fun writeCmd(cmd: String) {
        cmdSeq = maxOf(cmdSeq + 1, SystemClock.uptimeMillis())
        val bytes = "$cmd seq=$cmdSeq\n".toByteArray(StandardCharsets.UTF_8)
        val tmp = File(cmdFile.parentFile, cmdFile.name + ".tmp")
        try {
            FileOutputStream(tmp, false).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
        } catch (e: Throwable) {
            AonLog.e(TAG, "writeCmd tmp failed: $e")
            return
        }
        if (!tmp.renameTo(cmdFile)) {
            try {
                FileOutputStream(cmdFile, false).use { it.write(bytes) }
            } catch (e: Throwable) {
                AonLog.e(TAG, "writeCmd rename failed: $e")
            }
        }
    }

    fun isAttentive(): Boolean {
        if ((lastMask and 0x1) == 0) return false
        if (!config.requireGaze) return true
        if ((lastMask and 0x4) != 0) return true
        return SystemClock.uptimeMillis() - lastGazeAt < config.gazeGraceMs
    }

    override fun check(cb: DetectionBackend.CheckCallback, timeoutMs: Int) {
        // Demand mode: check() may arrive with the stream stopped. Lazily start
        // it (state=start + tail loop) so the wait below actually sees events;
        // the caller (DetectionController) stops the stream again after its
        // dwell window. Mirrors Camera2Backend.check()'s "if (!running) start()".
        if (!started) start()
        val deadline = SystemClock.uptimeMillis() + minOf(timeoutMs, 8000)
        // Runs on its own thread so a slow/blocking wait never delays stop()
        // (which must unregister the hardware the instant the screen goes off).
        Thread({
            if (lastEventAt > 0 && SystemClock.uptimeMillis() - lastEventAt < 3000) {
                answer(cb)
                return@Thread
            }

            // Confidence policy (matters when the stream was just lazy-started):
            //  - PRESENT evidence answers immediately.
            //  - ABSENT needs `ABSENT_STREAK_CONFIRM` consecutive no-attention
            //    events; the first events after a cold register can race face
            //    convergence, and a premature ABSENT kills the screen with no
            //    follow-up check possible.
            //  - Inconclusive at deadline -> PRESENT: keeps the screen on and
            //    guarantees another check at the next idle timeout (self-healing).
            val startWait = SystemClock.uptimeMillis()
            var sawEvents = false
            while (SystemClock.uptimeMillis() < deadline) {
                if (lastEventAt >= startWait ||
                    (lastEventAt > 0 && SystemClock.uptimeMillis() - lastEventAt < 2000)
                ) {
                    sawEvents = true
                    if (isAttentive() || absentStreak >= ABSENT_STREAK_CONFIRM) {
                        answer(cb)
                        return@Thread
                    }
                }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }

            if (sawEvents) {
                answer(cb) // inconclusive: answer PRESENT, next check re-evaluates
                return@Thread
            }

            cb.onError(AttentionService.ATTENTION_FAILURE_TIMED_OUT, "no aon events yet")
        }, "aon-check").apply { isDaemon = true }.start()
    }

    private fun answer(cb: DetectionBackend.CheckCallback) {
        val desc = if (lastSensorDist > 0) "AON 在场 (距离 ${lastSensorDist.toInt()}mm)" else "aon live state"
        if (isAttentive()) cb.onResult(AttentionService.ATTENTION_SUCCESS_PRESENT, desc)
        else cb.onResult(AttentionService.ATTENTION_SUCCESS_ABSENT, desc)
    }

    override fun stop() {
        started = false
        tailGen++ // invalidate any in-flight tail loop
        writeCmd("state=stop") // synchronous: unregister the hardware immediately
        sensorManager?.let { sm ->
            try {
                sm.unregisterListener(sensorListener)
            } catch (_: Throwable) {}
        }
        registered = false
        AonLog.i(TAG, "backend stopped (Camera3 AON unregistered)")
    }

    companion object {
        private const val TAG = "AONULP"

        /** Consecutive no-attention events required before a demand-mode check answers ABSENT. */
        private const val ABSENT_STREAK_CONFIRM = 3
    }
}
