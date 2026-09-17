package futureharmony.tb522fu.aon

import android.content.Context
import android.os.Looper
import android.os.SystemClock

/**
 * Headless test entry: runs the exact AON chain used by the service
 * (warmup -> RegisterClient -> event parsing -> attention decision)
 * without needing the system to bind the AttentionService.
 *
 * Usage (root): CLASSPATH=/data/app/.../base.apk app_process64 / \
 *   futureharmony.tb522fu.aon.ManualMain [seconds]
 */
class ManualMain {
    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun main(args: Array<String>) {
            val seconds = if (args.isNotEmpty()) args[0].toInt() else 30
            Looper.prepareMainLooper()

            // minimal context stand-in: AonConfig/AonLog need files dir + resolver
            val at = Class.forName("android.app.ActivityThread")
                .getMethod("systemMain")
                .invoke(null)
            val ctx = at.javaClass.getMethod("getSystemContext").invoke(at) as Context
            AonLog.init(ctx, 2000, AonLog.I, false)
            // system context has no data dir; use in-memory defaults (identical to service defaults)
            val cfg = AonConfig()
            AonLog.i("MANUAL", "start seconds=$seconds cfg=${cfg.toJson()}")

            var lastMask = 0
            val backend = AonHalBackend(ctx, cfg, object : AonHalBackend.Delegate {
                override fun onAonRegistered(ok: Boolean, detail: String) {
                    AonLog.i("MANUAL", "registered ok=$ok $detail")
                }

                override fun onAttentionChanged(att: Boolean, mask: Int, src: String) {
                    lastMask = mask
                    AonLog.i("MANUAL", "ATTENTIVE=$att $src")
                }
            })
            backend.start()

            val end = SystemClock.uptimeMillis() + seconds * 1000L
            var lastPrinted = -1
            while (SystemClock.uptimeMillis() < end) {
                Thread.sleep(1000)
                if (lastMask != lastPrinted) {
                    lastPrinted = lastMask
                    println(
                        "[ManualMain] mask=0x" + Integer.toHexString(lastMask) +
                            " attentive=" + backend.isAttentive() +
                            " healthy=" + backend.isHealthy()
                    )
                }
            }
            println("[ManualMain] final mask=0x" + Integer.toHexString(lastMask))
            backend.stop()
            Thread.sleep(3000)
            AonLog.i("MANUAL", "done")
            System.exit(0)
        }
    }
}
