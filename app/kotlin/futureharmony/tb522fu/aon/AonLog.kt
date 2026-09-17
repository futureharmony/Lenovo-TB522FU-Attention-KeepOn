package futureharmony.tb522fu.aon

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Dual-channel logger for the :attention process.
 * In-memory ring + append-only file (files/aon.log, rotated at 512KB) + logcat mirror.
 * The file is also readable by adb/Magisk WebUI (root) for debugging.
 */
object AonLog {
    const val D = 0
    const val I = 1
    const val W = 2
    const val E = 3
    private const val TAG = "AonKeepOn"
    private const val ROTATE_BYTES = 512L * 1024
    private val TS = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val ring = ArrayDeque<String>()
    private var maxLines = 1000
    private var level = I
    private var logFile: File? = null
    private var bytesWritten = 0L

    @JvmStatic
    @Synchronized
    fun init(ctx: Context?, maxLinesCfg: Int, levelCfg: Int, toFile: Boolean) {
        maxLines = maxOf(200, maxLinesCfg)
        level = levelCfg
        if (toFile && ctx != null) {
            logFile = File(ctx.filesDir, "aon.log")
        }
        i("LOG", "init maxLines=$maxLines level=$level file=$logFile")
    }

    @JvmStatic fun d(tag: String, msg: String) = log(D, tag, msg)
    @JvmStatic fun i(tag: String, msg: String) = log(I, tag, msg)
    @JvmStatic fun w(tag: String, msg: String) = log(W, tag, msg)
    @JvmStatic fun e(tag: String, msg: String) = log(E, tag, msg)

    @JvmStatic
    fun e(tag: String, msg: String, t: Throwable?) {
        log(E, tag, msg + " :: " + t)
        t?.stackTrace?.take(4)?.forEach { st -> log(E, tag, "  at $st") }
    }

    @Synchronized
    private fun log(lvl: Int, tag: String, msg: String) {
        if (lvl < level) return
        val line = "${TS.format(Date())} ${"DIWE"[lvl]}/$tag: $msg"
        if (ring.size >= maxLines) ring.pollFirst()
        ring.addLast(line)
        Log.println(2 + lvl, TAG, msg)
        appendFile(line)
    }

    private fun appendFile(line: String) {
        val f = logFile ?: return
        try {
            OutputStreamWriter(FileOutputStream(f, true), StandardCharsets.UTF_8).use { w ->
                w.write(line)
                w.write('\n'.code)
            }
            bytesWritten += line.length + 1
            if (bytesWritten > ROTATE_BYTES) {
                val old = File(f.parentFile, "aon.log.old")
                old.delete()
                f.renameTo(old)
                bytesWritten = 0
            }
        } catch (_: Exception) {
            // file logging is best-effort; ring + logcat remain authoritative
        }
    }

    /** Snapshot of the ring, oldest first. */
    @JvmStatic
    @Synchronized
    fun snapshot(): String {
        val sb = StringBuilder()
        for (l in ring) sb.append(l).append('\n')
        return sb.toString()
    }

    @JvmStatic
    @Synchronized
    fun clear() = ring.clear()

    @JvmStatic fun file(): File? = logFile

    @JvmStatic
    @Synchronized
    fun size(): Int = ring.size
}
