package futureharmony.tb522fu.aon

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Settings + live status/log viewer. Runs in the :attention process.
 *
 * Design: restrained dark UI — flat cards, one accent color, no effects.
 * Built programmatically (no resource pipeline).
 */
class SettingsActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var config: AonConfig
    private var controller: DetectionController? = null

    private lateinit var stateDot: View
    private lateinit var stateText: TextView
    private lateinit var detailText: TextView
    private lateinit var masterSwitch: Switch
    private lateinit var testResult: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private var autoScroll = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        config = AonConfig.get(this) { ui.post { refreshStatus() } }
        AonLog.init(this, config.logMaxLines, config.logLevel, config.logToFile)
        try {
            startService(Intent(this, AONAttentionService::class.java))
        } catch (e: Exception) {
            AonLog.w("UI", "cannot start service: $e")
        }
        controller = DetectionController.get(this, config)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        scroll.addView(root)

        root.addView(buildHeader())
        root.addView(buildStatusCard())
        root.addView(gap())
        root.addView(buildTestCard())
        root.addView(gap())
        root.addView(buildSettingsCard())
        root.addView(gap())
        root.addView(buildAdvancedCard())
        root.addView(gap())
        root.addView(buildLogCard())

        setContentView(scroll)
        ui.post { refreshStatus() }
        ui.post(refreshLoop)

        if (intent?.getBooleanExtra("check", false) == true) {
            ui.postDelayed({ triggerManualCheck() }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        if (config.demandMode) {
            // Demand mode: the stream must stay down while idle. onResume used
            // to force start() here, which kept the stream alive just by
            // opening the settings page — the exact leak Plan A guards against.
            controller?.stop()
        } else {
            controller?.start()
        }
        refreshStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("check", false)) {
            ui.postDelayed({ triggerManualCheck() }, 300)
        }
    }

    // ------------------------------------------------------------------ cards

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(16))
        }
        val title = TextView(this).apply {
            text = "注视保持"
            setTextColor(TEXT_PRIMARY)
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(title)
        stateDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
            background = circle(COLOR_OFF)
        }
        row.addView(stateDot)
        stateText = TextView(this).apply {
            text = "…"
            setTextColor(TEXT_SECONDARY)
            textSize = 13f
        }
        row.addView(stateText)
        return row
    }

    private fun buildStatusCard(): View {
        return card().apply {
            masterSwitch = switchRow("功能开关", config.masterEnabled ?: true) { checked ->
                val ok = config.setMasterEnabled(this@SettingsActivity, checked)
                AonLog.i("UI", "master switch -> $checked (mirror write ok=$ok)")
                refreshStatus()
            }.second

            detailText = TextView(this@SettingsActivity).apply {
                setTextColor(TEXT_SECONDARY)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(12), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
                setHorizontallyScrolling(false) // always wrap, never clip wide lines
                setSingleLine(false)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            addView(detailText)
        }
    }

    private fun buildTestCard(): View {
        return card().apply {
            addView(sectionLabel("检测测试"))
            testResult = TextView(this@SettingsActivity).apply {
                text = "点击「检测」立即感知一次人脸"
                setTextColor(TEXT_SECONDARY)
                textSize = 13f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundRect(SURFACE_2, dp(10))
            }
            addView(testResult)
            addView(gap(10))
            val buttons = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            buttons.addView(button("检测", COLOR_ACCENT) { triggerManualCheck() },
                rowWeight())
            buttons.addView(gap(10, vertical = false))
            buttons.addView(button("停止", SURFACE_3, textColor = TEXT_PRIMARY) {
                controller?.stop()
                AonLog.i("UI", "detection stopped")
                testResult.text = "已停止检测"
                testResult.setTextColor(TEXT_SECONDARY)
            }, rowWeight())
            addView(buttons)
        }
    }

    private fun buildSettingsCard(): View {
        return card().apply {
            addView(sectionLabel("设置"))

            val modeRow = selectorRow(
                "后端模式",
                arrayOf("AUTO", "仅 AON", "仅 Camera2"),
                arrayOf("AUTO（AON 优先，可回退 Camera2）", "仅 AON（ADSP 低功耗通道）", "仅 Camera2（调试用）"),
                config.backendMode.ordinal,
            ) { pos ->
                if (config.backendMode.ordinal != pos) {
                    config.backendMode = AonConfig.BackendMode.values()[pos]
                    persist()
                }
            }
            addView(modeRow)
            addView(gap(10))

            addView(switchRow("按需判定（息屏前才检测，省电推荐）", config.demandMode) { checked ->
                config.demandMode = checked
                // Flip live: demand stops the stream now; continuous resumes it.
                if (checked) controller?.stop() else controller?.start()
                persist()
            }.first)
            addView(gap(8))

            val dwellLabel = label("判定后流滞留：${config.demandDwellMs / 1000} s（按需模式）")
            addView(dwellLabel)
            val dwell = SeekBar(this@SettingsActivity).apply {
                max = 17 // 10s .. 180s, step 10s
                progress = (config.demandDwellMs / 10000).toInt() - 1
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                        config.demandDwellMs = (value + 1) * 10_000L
                        dwellLabel.text = "判定后流滞留：${config.demandDwellMs / 1000} s（按需模式）"
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) = persist()
                })
            }
            addView(dwell)
            addView(gap(8))

            val lwkyRow = selectorRow(
                "LwKy Plan B 处置",
                arrayOf("休眠保留", "彻底清除"),
                arrayOf("休眠保留（归档 APK + 禁用，可复活）", "彻底清除（归档后卸载）"),
                if (config.lwkyPolicy == "purge") 1 else 0,
                labelOverride = "LwKy 处置",
            ) { pos ->
                val newPolicy = if (pos == 1) "purge" else "dormant"
                if (config.lwkyPolicy != newPolicy) {
                    config.lwkyPolicy = config.normalizeLwkyPolicy(newPolicy)
                    persist()
                    AonLog.i("UI", "lwky policy -> ${config.lwkyPolicy}")
                }
            }
            addView(lwkyRow)
            addView(gap(10))

            addView(switchRow("必须注视才续屏（严格模式）", config.requireGaze) { checked ->
                config.requireGaze = checked
                persist()
            }.first)
            addView(gap(8))

            val graceLabel = label("注视丢失宽限：${config.gazeGraceMs} ms")
            addView(graceLabel)
            val grace = SeekBar(this@SettingsActivity).apply {
                max = 8000
                progress = config.gazeGraceMs
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                        config.gazeGraceMs = maxOf(200, value)
                        graceLabel.text = "注视丢失宽限：${config.gazeGraceMs} ms"
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) = persist()
                })
            }
            addView(grace)
            addView(gap(8))

            addView(switchRow("注册前 Camera 3 预热（推荐）", config.aonWarmup) { checked ->
                config.aonWarmup = checked
                persist()
            }.first)
        }
    }

    private fun buildAdvancedCard(): View {
        return card().apply {
            addView(sectionLabel("高级 · AON 参数"))
            addView(label("evtMask / algo / width / height / dps"))
            val fields = arrayOf(
                config.aonEvtMask, config.aonAlgoIdx, config.aonWidth,
                config.aonHeight, config.aonDeliveryPerSec,
            ).map { v ->
                EditText(this@SettingsActivity).apply {
                    setText(v.toString())
                    setTextColor(TEXT_PRIMARY)
                    setHintTextColor(TEXT_HINT)
                    textSize = 13f
                    inputType = InputType.TYPE_CLASS_NUMBER
                    background = roundRect(SURFACE_2, dp(8))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = dp(8) }
                }
            }
            val row = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            fields.forEach { row.addView(it) }
            addView(row)
            addView(gap(10))
            addView(button("应用参数", SURFACE_3, textColor = COLOR_ACCENT) {
                config.aonEvtMask = fields[0].text.toString().toIntOrNull() ?: config.aonEvtMask
                config.aonAlgoIdx = fields[1].text.toString().toIntOrNull() ?: config.aonAlgoIdx
                config.aonWidth = fields[2].text.toString().toIntOrNull() ?: config.aonWidth
                config.aonHeight = fields[3].text.toString().toIntOrNull() ?: config.aonHeight
                config.aonDeliveryPerSec = fields[4].text.toString().toIntOrNull() ?: config.aonDeliveryPerSec
                persist()
                Toast.makeText(this@SettingsActivity, "参数已应用（重启感知后生效）", Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun buildLogCard(): View {
        return card().apply {
            // Section label + level selector share one row (styled, readable).
            val head = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val headLabel = sectionLabel("日志（实时）").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setPadding(0, 0, 0, 0) }
            }
            head.addView(headLabel)
            head.addView(makeSpinner(
                arrayOf("DEBUG", "INFO", "WARN", "ERROR"),
                arrayOf("DEBUG", "INFO", "WARN", "ERROR"),
                maxOf(0, config.logLevel),
            ) { pos ->
                config.logLevel = pos
                persist()
            })
            addView(head)

            addView(gap(4))
            addView(switchRow("自动滚动到最新日志", autoScroll) { checked -> autoScroll = checked }.first)
            addView(gap(8))

            logView = TextView(this@SettingsActivity).apply {
                setTextColor(LOG_TEXT)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setHorizontallyScrolling(false)
                setSingleLine(false)
            }
            logScroll = ScrollView(this@SettingsActivity).apply {
                background = roundRect(SURFACE_2, dp(10))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(logView)
            }
            addView(logScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320)))

            addView(gap(10))
            val buttons = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(button("复制", SURFACE_3, textColor = TEXT_PRIMARY) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("aon.log", logView.text))
                AonLog.i("UI", "log copied to clipboard")
            }, rowWeight())
            buttons.addView(gap(8, vertical = false))
            buttons.addView(button("导出", SURFACE_3, textColor = TEXT_PRIMARY) {
                val f = AonLog.file()
                sendLog(if (f != null && f.exists()) readFullFile(f) else "(无文件)", full = true)
            }, rowWeight())
            buttons.addView(gap(8, vertical = false))
            buttons.addView(button("清空", SURFACE_3, textColor = TEXT_PRIMARY) {
                AonLog.clear()
                refreshLog()
            }, rowWeight())
            addView(buttons)
        }
    }

    // ----------------------------------------------------------------- logic

    private val refreshLoop = object : Runnable {
        override fun run() {
            refreshLog()
            refreshStatus()
            ui.postDelayed(this, 1000)
        }
    }

    private fun refreshLog() {
        logView.text = AonLog.snapshot()
        if (autoScroll) {
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun refreshStatus() {
        val enabled = config.masterEnabled ?: true
        val running = controller?.isStarted == true
        val dotColor = when {
            !enabled -> COLOR_OFF
            running -> COLOR_ON
            else -> COLOR_PENDING
        }
        stateDot.background = circle(dotColor)
        stateText.text = when {
            !enabled -> "已关闭"
            running -> "感知中"
            else -> "待机"
        }
        stateText.setTextColor(if (enabled) TEXT_PRIMARY else TEXT_SECONDARY)
        masterSwitch.isChecked = enabled

        val svc = AonConfig.get(this)
        val sb = StringBuilder()
        sb.append("mode=").append(svc.backendMode)
            .append("  gaze=").append(if (svc.requireGaze) "strict" else "any")
            .append("  grace=").append(svc.gazeGraceMs).append("ms\n")
        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            sb.append("v").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n")
        } catch (_: Exception) {
        }
        sb.append(controller?.summary() ?: "")
        detailText.text = sb.toString()
    }

    private fun persist() {
        val mirrored = config.save(this)
        AonLog.i("UI", "settings saved (mirror write ok=$mirrored)")
        refreshStatus()
    }

    private fun triggerManualCheck() {
        val dc = controller ?: return
        testResult.text = "正在唤醒感知硬件并检测人脸… 请正对屏幕"
        testResult.setTextColor(COLOR_PENDING)
        AonLog.i("UI", "manual check started")
        // Route through demandCheck in both modes: it lazy-starts the backend
        // when needed and (in demand mode) schedules the dwell stop, so a
        // manual test can no longer leave the stream running forever.
        dc.demandCheck(object : DetectionBackend.CheckCallback {
            override fun onResult(r: Int, reason: String) {
                ui.post {
                    if (r == 1) { // ATTENTION_SUCCESS_PRESENT
                        testResult.text = "检测成功：检测到注视人脸 (PRESENT)\n$reason"
                        testResult.setTextColor(COLOR_OK)
                        Toast.makeText(this@SettingsActivity, "检测到注视人脸", Toast.LENGTH_SHORT).show()
                    } else {
                        testResult.text = "未检测到人脸 (ABSENT)\n$reason"
                        testResult.setTextColor(COLOR_BAD)
                        Toast.makeText(this@SettingsActivity, "未检测到人脸", Toast.LENGTH_SHORT).show()
                    }
                }
                AonLog.i("UI", "manual check result=$r ($reason)")
            }

            override fun onError(code: Int, reason: String) {
                ui.post {
                    testResult.text = "检测异常/超时: code=$code\n$reason"
                    testResult.setTextColor(COLOR_WARN)
                    Toast.makeText(this@SettingsActivity, "检测未完成: $reason", Toast.LENGTH_SHORT).show()
                }
                AonLog.w("UI", "manual check error=$code ($reason)")
            }
        }, 8000)
    }

    private fun sendLog(text: String, full: Boolean) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "aon.log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(i, if (full) "导出完整日志" else "分享日志"))
    }

    private fun readFullFile(f: File): String = try {
        String(java.nio.file.Files.readAllBytes(f.toPath()), Charsets.UTF_8)
    } catch (e: Exception) {
        "(读取失败: $e)"
    }

    // ------------------------------------------------------------- ui helpers

    /**
     * One-row selector: title on the left, styled spinner on the right.
     * `closed` texts show in the closed spinner (kept short); `full` texts
     * show in the dropdown. Returns the row container.
     */
    private fun selectorRow(
        title: String,
        closed: Array<String>,
        full: Array<String>,
        selected: Int,
        labelOverride: String? = null,
        onSelect: (Int) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = labelOverride ?: title
            setTextColor(TEXT_PRIMARY)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(makeSpinner(closed, full, selected) { onSelect(it) })
        return row
    }

    /**
     * Spinner with programmatic views: readable light text on our dark
     * surfaces (the stock adapter inherits the system theme and renders
     * near-black text), rounded closed pill, dark dropdown popup, and a
     * highlighted row for the current selection.
     */
    private fun makeSpinner(
        closed: Array<String>,
        full: Array<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): Spinner {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, full) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                spinText(closed[position] + "  ▾", dropdown = false, selected = false)

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val sel = (parent as? Spinner)?.selectedItemPosition ?: -1
                return spinText(full[position], dropdown = true, selected = position == sel)
            }
        }
        return Spinner(this).apply {
            this.adapter = adapter
            setSelection(selected, false) // no initial onItemSelected fire
            background = roundRect(SURFACE_3, dp(10))
            setPopupBackgroundDrawable(roundRect(SURFACE_1, dp(12)))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (pos != selected) onSelect(pos)
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
    }

    private fun spinText(text: String, dropdown: Boolean, selected: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(if (selected) COLOR_ACCENT else if (dropdown) TEXT_PRIMARY else TEXT_PRIMARY)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, if (dropdown) Typeface.NORMAL else Typeface.BOLD)
            setPadding(dp(12), dp(if (dropdown) 10 else 5), dp(12), dp(if (dropdown) 10 else 5))
            if (dropdown) setSingleLine(false)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun gap(h: Int = 14, vertical: Boolean = true): View =
        View(this).apply {
            layoutParams = if (vertical) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
            } else {
                LinearLayout.LayoutParams(dp(h), dp(1))
            }
        }

    private fun rowWeight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(SURFACE_1, dp(18))
        setPadding(dp(18), dp(16), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(TEXT_SECONDARY)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(10))
        letterSpacing = 0.05f
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(TEXT_PRIMARY)
        textSize = 14f
        setPadding(0, dp(6), 0, dp(4))
    }

    /** Returns (row, switch) so callers can keep a handle on the switch itself. */
    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit):
        Pair<LinearLayout, Switch> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val t = TextView(this).apply {
            this.text = text
            setTextColor(TEXT_PRIMARY)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val s = Switch(this).apply {
            isChecked = checked
            setOnClickListener { onChange((it as Switch).isChecked) }
        }
        row.addView(t)
        row.addView(s)
        return row to s
    }

    private fun button(text: String, bg: Int, textColor: Int = Color.WHITE, onClick: (View) -> Unit): Button =
        Button(this).apply {
            this.text = text
            this.setTextColor(textColor)
            textSize = 14f
            isAllCaps = false
            background = roundRect(bg, dp(12))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            stateListAnimator = null
            setOnClickListener(onClick)
        }

    private fun roundRect(color: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        shape = GradientDrawable.OVAL
    }

    companion object {
        // restrained dark palette
        private val BG = Color.parseColor("#0F1115")
        private val SURFACE_1 = Color.parseColor("#171B22")
        private val SURFACE_2 = Color.parseColor("#10131A")
        private val SURFACE_3 = Color.parseColor("#232935")
        private val TEXT_PRIMARY = Color.parseColor("#E6E9EF")
        private val TEXT_SECONDARY = Color.parseColor("#8A93A3")
        private val TEXT_HINT = Color.parseColor("#5A6373")
        private val LOG_TEXT = Color.parseColor("#9FB29A")

        private val COLOR_ACCENT = Color.parseColor("#5B8DEF")
        private val COLOR_ON = Color.parseColor("#57C773")
        private val COLOR_PENDING = Color.parseColor("#E0B25A")
        private val COLOR_OFF = Color.parseColor("#4A5160")
        private val COLOR_OK = COLOR_ON
        private val COLOR_BAD = Color.parseColor("#E56A6A")
        private val COLOR_WARN = COLOR_PENDING
    }
}
