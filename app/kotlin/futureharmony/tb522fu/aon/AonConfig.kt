package futureharmony.tb522fu.aon

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Configuration hub. SharedPreferences ("aon_config") is the source of truth;
 * a JSON mirror lives in Settings.Secure key "tb522fu_aon_config" so that
 * adb / Magisk WebUI (root) can change settings live; a ContentObserver picks
 * mirror changes up in real time.
 *
 * Master switch (Plan A): Settings.Secure "tb522fu_aon_enabled" (1/0, written by
 * attention_ctrl on/off and the settings UI) is observed live so the service can
 * protocol-stop the AON stream the instant the feature is turned off.
 */
class AonConfig {

    enum class BackendMode { AUTO, AON_ONLY, CAMERA2_ONLY }

    @Volatile var backendMode = BackendMode.AON_ONLY
    @Volatile var requireGaze = true
    @Volatile var gazeGraceMs = 2000
    @Volatile var aonWarmup = true
    @Volatile var aonServiceType = 1   // 1 = FDPRO (og0ve does not support FD basic)
    // algo 0/1 (160x120/320x240) hit the QSH island FD path, which crash-loops
    // the ADSP on this ROM (qsh_process 0x10000014) and takes USB down with it.
    // algo 2 (480x360, non-island) streams continuously with zero crashes.
    @Volatile var aonAlgoIdx = 2       // 2 = 480x360 non-island (verified stable)
    @Volatile var aonWidth = 480
    @Volatile var aonHeight = 360
    @Volatile var aonDeliveryPerSec = 3 // delivery hint; HAL delivers at its own ~10Hz cadence
    @Volatile var aonEvtMask = 15      // 0xf
    // Demand mode: the AON stream is NOT kept alive while the screen is on.
    // It is lazily started only when the framework fires onCheckAttention
    // (the instant the screen-off idle timeout expires), answered once, and
    // stopped again after demandDwellMs of no further checks. Stock ColorOS
    // adaptive_sleep paces checks this way; idle stream cost drops to ~0.
    @Volatile var demandMode = true
    @Volatile var demandDwellMs = 60_000L // keep stream alive this long after an answer
    // LwKy Plan B handling, applied by the module's service.sh at boot (it reads
    // this secure mirror). "dormant" = archive APK + pm disable-user (Plan B kept,
    // zero conflict); "purge" = archive + uninstall. No "keep": an enabled LwKy
    // fights us for the attention_service_component binding.
    @Volatile var lwkyPolicy = "dormant"
    @Volatile var camera2TimeoutMs = 4000
    @Volatile var camera2IntervalMs = 5000
    @Volatile var simulatePresent = false // debug hook (force-present)
    @Volatile var dtofAux = false         // optional ads610x0 dToF distance logging
    @Volatile var logLevel = AonLog.I
    @Volatile var logMaxLines = 1000
    @Volatile var logToFile = true

    /** Master switch mirror; null = key never written (treat as enabled). */
    @Volatile var masterEnabled: Boolean? = null

    private var prefs: SharedPreferences? = null
    private var resolver: ContentResolver? = null
    private val listeners = CopyOnWriteArrayList<Runnable>()

    private fun notifyListeners() {
        for (l in listeners) {
            try {
                l.run()
            } catch (t: Throwable) {
                AonLog.w("CFG", "listener failed: $t")
            }
        }
    }

    private fun init(ctx: Context) {
        // The service is started by the module's service.sh at boot, before the
        // user unlocks the device; credential-encrypted SharedPreferences throw
        // in that state and crash the service. Store config in device-protected
        // storage (available from direct-boot) and migrate any existing CE file.
        prefs = try {
            val deCtx = ctx.createDeviceProtectedStorageContext()
            deCtx.moveSharedPreferencesFrom(ctx, "aon_config")
            deCtx.getSharedPreferences("aon_config", Context.MODE_PRIVATE)
        } catch (e: Throwable) {
            AonLog.w("CFG", "DE prefs unavailable, falling back to CE: $e")
            ctx.getSharedPreferences("aon_config", Context.MODE_PRIVATE)
        }
        resolver = ctx.contentResolver
        load()
        migrate()
        loadMasterEnabled()
        val handler = Handler(ctx.mainLooper)
        val r = resolver ?: return

        // 1. JSON mirror observer (adb / WebUI overlay)
        try {
            r.registerContentObserver(Settings.Secure.getUriFor(SECURE_KEY), false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        val before = toJson()
                        load()
                        if (toJson() != before) {
                            AonLog.i("CFG", "secure mirror changed, reloading")
                            notifyListeners()
                        }
                    }
                })
        } catch (e: Throwable) {
            AonLog.w("CFG", "mirror observer failed: $e")
        }

        // 2. Master switch observer (Plan A: instant stop channel)
        try {
            r.registerContentObserver(Settings.Secure.getUriFor(ENABLED_KEY), false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        loadMasterEnabled()
                        AonLog.i("CFG", "master switch changed: enabled=$masterEnabled")
                        notifyListeners()
                    }
                })
        } catch (e: Throwable) {
            AonLog.w("CFG", "master switch observer failed: $e")
        }
    }

    /** Read the master switch; absent/unreadable key means enabled. */
    private fun loadMasterEnabled() {
        val r = resolver ?: return
        masterEnabled = try {
            when (Settings.Secure.getString(r, ENABLED_KEY)) {
                null -> null
                "0", "false" -> false
                else -> true
            }
        } catch (_: Exception) {
            null
        }
    }

    /** One-time migration. Older installs persisted aon_dps=15 (and sometimes
     *  require_gaze=false) in the secure mirror, which overrides the new defaults
     *  on every load; bump the version to force the low-load values through. */
    private fun migrate() {
        val p = prefs ?: return
        val v = p.getInt("config_version", 1)
        if (v >= CONFIG_VERSION) return
        aonDeliveryPerSec = 3
        requireGaze = true
        // v3: algo 0/1 island modes crash-loop the ADSP; force the verified
        // non-island mode through over any persisted pre-v3 values.
        if (v < 3) {
            aonAlgoIdx = 2
            aonWidth = 480
            aonHeight = 360
        }
        p.edit().putInt("config_version", CONFIG_VERSION).apply()
        // Persist the migrated fields themselves, not just the version marker:
        // load() reads the old values back on every start otherwise.
        save(null)
        AonLog.i("CFG", "migrated config to v$CONFIG_VERSION (aon_dps=3, require_gaze=true, algo=2 480x360)")
    }

    @Synchronized
    fun load() {
        prefs?.let { p ->
            backendMode = try {
                BackendMode.valueOf(p.getString("backend_mode", backendMode.name)!!)
            } catch (_: IllegalArgumentException) {
                backendMode
            }
            requireGaze = p.getBoolean("require_gaze", requireGaze)
            gazeGraceMs = p.getInt("gaze_grace_ms", gazeGraceMs)
            aonWarmup = p.getBoolean("aon_warmup", aonWarmup)
            aonServiceType = p.getInt("aon_service_type", aonServiceType)
            aonAlgoIdx = p.getInt("aon_algo_idx", aonAlgoIdx)
            aonWidth = p.getInt("aon_width", aonWidth)
            aonHeight = p.getInt("aon_height", aonHeight)
            aonDeliveryPerSec = p.getInt("aon_dps", aonDeliveryPerSec)
            aonEvtMask = p.getInt("aon_evt_mask", aonEvtMask)
            demandMode = p.getBoolean("demand_mode", demandMode)
            demandDwellMs = p.getInt("demand_dwell_ms", demandDwellMs.toInt()).toLong()
            lwkyPolicy = p.getString("lwky_policy", lwkyPolicy) ?: lwkyPolicy
            camera2TimeoutMs = p.getInt("camera2_timeout_ms", camera2TimeoutMs)
            camera2IntervalMs = p.getInt("camera2_interval_ms", camera2IntervalMs)
            simulatePresent = p.getBoolean("simulate_present", simulatePresent)
            dtofAux = p.getBoolean("dtof_aux", dtofAux)
            logLevel = p.getInt("log_level", logLevel)
            logMaxLines = p.getInt("log_max_lines", logMaxLines)
            logToFile = p.getBoolean("log_to_file", logToFile)
        }
        // overlay from secure mirror (adb / WebUI can set this without touching prefs)
        try {
            val json = resolver?.let { Settings.Secure.getString(it, SECURE_KEY) }
            if (!json.isNullOrEmpty()) applyJson(json, log = false)
        } catch (e: Exception) {
            AonLog.w("CFG", "mirror read failed: $e")
        }
    }

    /** Apply a JSON blob (from the secure mirror or the settings UI). */
    @Synchronized
    fun applyJson(json: String, log: Boolean = true) {
        val o: JSONObject = try {
            JSONObject(json)
        } catch (e: Exception) {
            AonLog.w("CFG", "bad json, ignored: $json")
            return
        }
        backendMode = try {
            BackendMode.valueOf(o.optString("backend_mode", backendMode.name))
        } catch (_: IllegalArgumentException) {
            backendMode
        }
        requireGaze = o.optBoolean("require_gaze", requireGaze)
        gazeGraceMs = o.optInt("gaze_grace_ms", gazeGraceMs)
        aonWarmup = o.optBoolean("aon_warmup", aonWarmup)
        aonServiceType = o.optInt("aon_service_type", aonServiceType)
        aonAlgoIdx = o.optInt("aon_algo_idx", aonAlgoIdx)
        aonWidth = o.optInt("aon_width", aonWidth)
        aonHeight = o.optInt("aon_height", aonHeight)
        aonDeliveryPerSec = o.optInt("aon_dps", aonDeliveryPerSec)
        aonEvtMask = o.optInt("aon_evt_mask", aonEvtMask)
        demandMode = o.optBoolean("demand_mode", demandMode)
        demandDwellMs = o.optLong("demand_dwell_ms", demandDwellMs)
        lwkyPolicy = normalizeLwkyPolicy(o.optString("lwky_policy", lwkyPolicy))
        camera2TimeoutMs = o.optInt("camera2_timeout_ms", camera2TimeoutMs)

        camera2IntervalMs = o.optInt("camera2_interval_ms", camera2IntervalMs)
        simulatePresent = o.optBoolean("simulate_present", simulatePresent)
        dtofAux = o.optBoolean("dtof_aux", dtofAux)
        logLevel = o.optInt("log_level", logLevel)
        logMaxLines = o.optInt("log_max_lines", logMaxLines)
        logToFile = o.optBoolean("log_to_file", logToFile)
        if (log) AonLog.i("CFG", "applied: $json")
    }

    /** Persist to prefs + secure mirror. Returns false if the mirror write was denied. */
    @Synchronized
    fun save(ctx: Context?): Boolean {
        val e = prefs!!.edit()
        e.putString("backend_mode", backendMode.name)
        e.putBoolean("require_gaze", requireGaze)
        e.putInt("gaze_grace_ms", gazeGraceMs)
        e.putBoolean("aon_warmup", aonWarmup)
        e.putInt("aon_service_type", aonServiceType)
        e.putInt("aon_algo_idx", aonAlgoIdx)
        e.putInt("aon_width", aonWidth)
        e.putInt("aon_height", aonHeight)
        e.putInt("aon_dps", aonDeliveryPerSec)
        e.putInt("aon_evt_mask", aonEvtMask)
        e.putBoolean("demand_mode", demandMode)
        e.putInt("demand_dwell_ms", demandDwellMs.toInt())
        e.putString("lwky_policy", lwkyPolicy)
        e.putInt("camera2_timeout_ms", camera2TimeoutMs)
        e.putInt("camera2_interval_ms", camera2IntervalMs)
        e.putBoolean("simulate_present", simulatePresent)
        e.putBoolean("dtof_aux", dtofAux)
        e.putInt("config_version", CONFIG_VERSION)
        e.putInt("log_level", logLevel)
        e.putInt("log_max_lines", logMaxLines)
        e.putBoolean("log_to_file", logToFile)
        e.apply()
        return try {
            Settings.Secure.putString(resolver, SECURE_KEY, toJson())
            true
        } catch (ex: Exception) {
            AonLog.w("CFG", "mirror write denied (grant WRITE_SECURE_SETTINGS): $ex")
            false
        }
    }

    /** Write the master switch into Settings.Secure so the service reacts instantly. */
    fun setMasterEnabled(ctx: Context, enabled: Boolean): Boolean {
        masterEnabled = enabled
        return try {
            Settings.Secure.putString(ctx.contentResolver, ENABLED_KEY, if (enabled) "1" else "0")
            // keep the legacy adaptive_sleep key in lockstep (system settings toggle)
            try {
                Settings.Secure.putInt(ctx.contentResolver, "adaptive_sleep", if (enabled) 1 else 0)
            } catch (_: Throwable) {
            }
            true
        } catch (ex: Exception) {
            AonLog.w("CFG", "master switch write denied (grant WRITE_SECURE_SETTINGS): $ex")
            false
        }
    }

    fun toJson(): String {
        val o = JSONObject()
        try {
            o.put("backend_mode", backendMode.name)
            o.put("require_gaze", requireGaze)
            o.put("gaze_grace_ms", gazeGraceMs)
            o.put("aon_warmup", aonWarmup)
            o.put("aon_service_type", aonServiceType)
            o.put("aon_algo_idx", aonAlgoIdx)
            o.put("aon_width", aonWidth)
            o.put("aon_height", aonHeight)
            o.put("aon_dps", aonDeliveryPerSec)
            o.put("aon_evt_mask", aonEvtMask)
            o.put("demand_mode", demandMode)
            o.put("demand_dwell_ms", demandDwellMs)
            o.put("lwky_policy", lwkyPolicy)
            o.put("camera2_timeout_ms", camera2TimeoutMs)
            o.put("camera2_interval_ms", camera2IntervalMs)
            o.put("simulate_present", simulatePresent)
            o.put("dtof_aux", dtofAux)
            o.put("log_level", logLevel)
            o.put("log_max_lines", logMaxLines)
            o.put("log_to_file", logToFile)
        } catch (_: Exception) {
        }
        return o.toString()
    }

    /** Only dormant/purge exist; anything else (incl. a stale "keep") falls back to dormant. */
    fun normalizeLwkyPolicy(raw: String?): String =
        if (raw == "purge") "purge" else "dormant"

    companion object {
        const val SECURE_KEY = "tb522fu_aon_config"
        const val ENABLED_KEY = "tb522fu_aon_enabled"
        private const val CONFIG_VERSION = 3

        @Volatile private var sInstance: AonConfig? = null

        /**
         * Process-wide shared instance (:attention hosts both the service and the
         * UI), so the master switch state and change listeners stay in one place.
         */
        @JvmStatic
        @Synchronized
        fun get(ctx: Context, changeCallback: Runnable? = null): AonConfig {
            val inst = sInstance ?: AonConfig().also {
                sInstance = it
                it.init(ctx)
            }
            changeCallback?.let { inst.listeners.add(it) }
            return inst
        }
    }
}
