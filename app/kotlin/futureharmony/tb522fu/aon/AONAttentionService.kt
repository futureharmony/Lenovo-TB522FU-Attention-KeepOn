package futureharmony.tb522fu.aon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.attention.AttentionService

/**
 * AttentionService entry point with ColorOS System Settings integration.
 * - Monitors Settings.Secure.adaptive_sleep via ContentObserver
 * - Monitors the module master switch (tb522fu_aon_enabled, Plan A) live
 * - Best-effort FileObserver on the module config.json (works once the module
 *   makes /data/adb/tb522fu_attention readable)
 * - Monitors Screen on/off state to suspend AON during sleep (0% idle power)
 * - Drives DetectionController to keep the display awake when person is present
 *
 * Every "off" path funnels into DetectionController.stop() -> AonHalBackend.stop()
 * -> writeCmd("state=stop") -> daemon's own UnregisterClient branch. The daemon
 * stays alive (warmup preserved); no process killing anywhere.
 */
class AONAttentionService : AttentionService() {
    private lateinit var config: AonConfig
    private var controller: DetectionController? = null
    private var settingsObserver: ContentObserver? = null
    private var enabledObserver: ContentObserver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var moduleConfObserver: FileObserver? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastScreenState = false
    private var lastSettingVal = -1
    private var lastModuleEnabled: Boolean? = null
    private var staleStreamCleared = false

    override fun onCreate() {
        super.onCreate()
        // MUST run startForeground within the FGS timeout (~5s) or Android kills
        // the process silently. Do it before anything that could possibly block
        // (config IO, controller init), and never let a notification failure
        // leave the service without a foreground entry.
        enterForeground()
        config = AonConfig.get(this) {
            AonLog.i("SVC", "config changed; syncing controller state")
            syncState()
        }
        AonLog.init(this, config.logMaxLines, config.logLevel, config.logToFile)
        controller = DetectionController.get(this, config)

        registerObservers()
        syncState()
        AonLog.i("SVC", "AONAttentionService started & synced with System Settings")
    }

    private fun enterForeground() {
        initNotificationChannel()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notification = try {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("注视不熄屏保护中")
                .setContentText("硬件传感器实时感知注视状态")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        } catch (t: Throwable) {
            // Channel creation failed (rare) — build a channel-less legacy notification.
            AonLog.w("SVC", "notification build fallback: ${t.message}")
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("注视不熄屏保护中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        }
        try {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (t: Throwable) {
            AonLog.w("SVC", "startForeground(specialUse) failed: ${t.message}; retrying without type")
            try {
                startForeground(NOTIF_ID, notification)
            } catch (t2: Throwable) {
                AonLog.e("SVC", "startForeground failed entirely", t2)
            }
        }
    }

    private fun initNotificationChannel() {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "注视不熄屏感知服务",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "维持硬件感知与防息屏同步"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        } catch (t: Throwable) {
            AonLog.w("SVC", "notification channel: ${t.message}")
        }
    }

    private fun registerObservers() {
        val handler = mainHandler

        // 1. Settings.Secure.adaptive_sleep observer
        try {
            val obs = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    AonLog.i("SVC", "adaptive_sleep setting changed")
                    syncState()
                }
            }
            contentResolver.registerContentObserver(Settings.Secure.getUriFor("adaptive_sleep"), false, obs)
            settingsObserver = obs
        } catch (t: Throwable) {
            AonLog.w("SVC", "registerContentObserver failed: ${t.message}")
        }

        // 2. Module master switch (tb522fu_aon_enabled) — also observed by AonConfig;
        //    kept here too so the service reacts even when config reload is a no-op.
        try {
            val obs = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    AonLog.i("SVC", "tb522fu_aon_enabled changed")
                    syncState()
                }
            }
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(AonConfig.ENABLED_KEY), false, obs)
            enabledObserver = obs
        } catch (t: Throwable) {
            AonLog.w("SVC", "master switch observer failed: ${t.message}")
        }

        // 3. Screen on / off receiver
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                AonLog.i("SVC", "screen event: ${intent?.action}")
                syncState()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
            AonLog.i("SVC", "screenReceiver registered with RECEIVER_EXPORTED")
        } catch (t: Throwable) {
            AonLog.e("SVC", "registerReceiver failed", t)
        }

        // 4. Module config.json FileObserver (Plan A best-effort channel).
        //    /data/adb is 0700 root; this only starts delivering events if the
        //    module dir was made readable. Harmless when unreadable.
        try {
            val obs = object : FileObserver(MODULE_CONF, CLOSE_WRITE or MODIFY or DELETE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    AonLog.i("SVC", "module config.json event: $event")
                    mainHandler.post { syncState() }
                }
            }
            obs.startWatching()
            moduleConfObserver = obs
            AonLog.i("SVC", "module config.json FileObserver registered")
        } catch (t: Throwable) {
            AonLog.w("SVC", "module config.json observer unavailable: ${t.message}")
        }

        // 5. DisplayManager display listener (hardware-level screen on/off)
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            dm?.registerDisplayListener(object : android.hardware.display.DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                        syncState()
                    }
                }
            }, handler)
            AonLog.i("SVC", "DisplayListener registered")
        } catch (t: Throwable) {
            AonLog.e("SVC", "registerDisplayListener failed", t)
        }

        // 6. Periodic sync (every 3 seconds) — fallback poll for channels without observers
        val periodic = object : Runnable {
            override fun run() {
                syncState()
                mainHandler.postDelayed(this, 3000)
            }
        }
        mainHandler.postDelayed(periodic, 3000)
    }

    /** Best-effort read of the module config.json "enabled" field. Unreadable -> null. */
    private fun readModuleEnabled(): Boolean? {
        return try {
            val text = java.io.FileInputStream(java.io.File(MODULE_CONF)).use { inp ->
                inp.readBytes().toString(Charsets.UTF_8)
            }
            when {
                text.contains(Regex("\"enabled\"\\s*:\\s*false")) -> false
                text.contains(Regex("\"enabled\"\\s*:\\s*true")) -> true
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    private fun syncState() {
        val settingVal = try {
            Settings.Secure.getInt(contentResolver, "adaptive_sleep", 1)
        } catch (_: Throwable) {
            1
        }
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenInteractive = pm == null || pm.isInteractive
        val masterEnabled = readModuleEnabled()
        val secureEnabled = config.masterEnabled ?: true

        if (settingVal != lastSettingVal || screenInteractive != lastScreenState ||
            masterEnabled != lastModuleEnabled
        ) {
            AonLog.i("SVC", "syncState: adaptive_sleep=$settingVal, screenOn=$screenInteractive" +
                ", moduleEnabled=$masterEnabled, secureEnabled=$secureEnabled")
            lastSettingVal = settingVal
            lastScreenState = screenInteractive
            lastModuleEnabled = masterEnabled
        }

        val effectiveOn = settingVal == 1 && screenInteractive &&
            masterEnabled != false && secureEnabled
        if (effectiveOn) {
            if (config.demandMode) {
                // Demand mode: do NOT keep a stream running while the screen is
                // on. The backend lazy-starts inside demandCheck() when the
                // framework fires onCheckAttention at the screen-off timeout,
                // and stops itself again after the dwell window.
                if (!staleStreamCleared) {
                    // First sync of this process: a previous process (or an old
                    // app version) may have left the daemon registered — its
                    // registration survives app death. Tear it down once.
                    staleStreamCleared = true
                    controller?.hardStop()
                }
                AonLog.d("SVC", "demand mode: stream stays down until onCheckAttention")
            } else {
                controller?.start()
            }
        } else {
            controller?.stop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncState()
        return START_STICKY
    }

    override fun onCheckAttention(callback: AttentionCallback) {
        val t0 = SystemClock.uptimeMillis()
        AonLog.i("SVC", "onCheckAttention (demand=${config.demandMode})")
        // Demand mode lazily starts the stream here; continuous mode answers
        // from the already-running stream. Same callback contract either way.
        controller?.demandCheck(object : DetectionBackend.CheckCallback {
            override fun onResult(attentionResult: Int, reason: String) {
                AonLog.i("SVC", "attention result=$attentionResult reason=$reason" +
                    " elapsedMs=${SystemClock.uptimeMillis() - t0}")
                callback.onSuccess(attentionResult, SystemClock.elapsedRealtime())
            }

            override fun onError(failureCode: Int, reason: String) {
                AonLog.w("SVC", "attention failure=$failureCode reason=$reason")
                callback.onFailure(failureCode)
            }
        }, 4000)
    }

    override fun onCancelAttentionCheck(callback: AttentionCallback) {
        AonLog.i("SVC", "onCancelAttentionCheck")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AonLog.i("SVC", "onUnbind")
        return false
    }

    override fun onDestroy() {
        AonLog.i("SVC", "service destroyed")
        settingsObserver?.let { try { contentResolver.unregisterContentObserver(it) } catch (_: Throwable) {} }
        enabledObserver?.let { try { contentResolver.unregisterContentObserver(it) } catch (_: Throwable) {} }
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) {} }
        try { moduleConfObserver?.stopWatching() } catch (_: Throwable) {}
        controller?.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "tb522fu_aon_channel"
        private const val NOTIF_ID = 2026
        private const val MODULE_CONF = "/data/adb/tb522fu_attention/config.json"
    }
}
