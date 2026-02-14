package com.ikun.monitor.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.ikun.monitor.MainActivity
import com.ikun.monitor.data.MetricSample
import com.ikun.monitor.data.RecordingRepository
import com.ikun.monitor.data.RecordingSession
import com.ikun.monitor.sampler.FrameStatsSampler
import com.ikun.monitor.sampler.SystemSampler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object OverlayController {
    private const val DESKTOP_BRIDGE_CONFIG = "pm_desktop_bridge_config.json"

    private val _isShowingState = mutableStateOf(false)
    val isShowingState: State<Boolean> get() = _isShowingState

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var samplingMs: Int = 1000
    private var retainLimit: Int = 20

    private var targetAppLabel: String? = null
    private var targetAppPackage: String? = null
    private var selectedMetricKeys: List<String> = emptyList()

    private var sampler: SystemSampler? = null
    private var frameSampler: FrameStatsSampler? = null

    private var tvSampling: TextView? = null
    private var tvCpu: TextView? = null
    private var tvCpuApp: TextView? = null
    private var tvMem: TextView? = null
    private var tvAppPss: TextView? = null
    private var tvFps: TextView? = null
    private var tvGpu: TextView? = null
    private var tvNet: TextView? = null
    private var tvBattery: TextView? = null
    private var tvTemp: TextView? = null
    private var tvVoltage: TextView? = null
    private var tvCpuFreq: TextView? = null

    // Minimize/expand state
    private var minimized: Boolean = false
    private var expandedContent: LinearLayout? = null   // holds all metric rows + record button
    private var miniContainer: LinearLayout? = null     // the compact one-line bar
    private var tvMiniInfo: TextView? = null             // inline info text in mini bar
    private var btnRec: Button? = null                   // record button (shared)
    private var btnMiniRec: TextView? = null             // mini bar record/stop button

    private val mainHandler = Handler(Looper.getMainLooper())

    private var recording = false
    private var recStartElapsedMs: Long = 0L
    private var recStartEpochMs: Long = 0L
    private var currentSessionName: String? = null
    private var ticker: Runnable? = null

    private var samplingTicker: Runnable? = null
    private val currentSamples = ArrayList<MetricSample>(256)
    private var currentSessionId: String? = null

    private fun writeDesktopBridgeConfig(enabled: Boolean) {
        val ctx = rootView?.context?.applicationContext ?: return

        // v1: write to BOTH external app dir and internal files dir.
        // - external: compatible with older ADB flows
        // - internal: allows robust access via `adb shell run-as` on newer Android / emulator
        val externalDir = ctx.getExternalFilesDir(null)
        val externalFile = if (externalDir != null) File(externalDir, DESKTOP_BRIDGE_CONFIG) else null
        val internalFile = File(ctx.filesDir, DESKTOP_BRIDGE_CONFIG)

        try {
            val obj = JSONObject()
            obj.put("enabled", enabled)
            obj.put("updatedAtEpochMs", System.currentTimeMillis())
            obj.put("samplingMs", samplingMs)
            obj.put("targetPackage", targetAppPackage ?: JSONObject.NULL)
            obj.put("metricKeys", JSONArray(selectedMetricKeys))
            val text = obj.toString()

            try {
                externalFile?.writeText(text)
            } catch (_: Throwable) {
            }
            try {
                internalFile.writeText(text)
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
            // keep silent; this is best-effort
        }
    }

    fun isShowing(): Boolean = rootView != null

    fun show(
        appContext: Context,
        targetAppLabel: String?,
        targetAppPackage: String?,
        selectedMetricKeys: List<String>,
        samplingMs: Int,
        retainLimit: Int,
    ) {
        if (rootView != null) {
            this.targetAppLabel = targetAppLabel
            this.targetAppPackage = targetAppPackage
            this.selectedMetricKeys = selectedMetricKeys
            sampler?.setTargetApp(targetAppPackage)
            ensureFrameSampler(appContext, selectedMetricKeys.toSet())
            updateSampling(samplingMs)
            updateRetainLimit(retainLimit)
            return
        }

        this.samplingMs = samplingMs
        this.retainLimit = retainLimit
        this.targetAppLabel = targetAppLabel
        this.targetAppPackage = targetAppPackage
        this.selectedMetricKeys = selectedMetricKeys

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setPadding(dp(appContext, 8), dp(appContext, 6), dp(appContext, 8), dp(appContext, 6))
            setBackgroundColor(0x9E000000.toInt()) // black, ~62% alpha
        }

        // ========== Expanded content ==========
        val expandedContent = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        this.expandedContent = expandedContent

        val header = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val tvSampling = TextView(appContext).apply {
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 13f
            text = "iKunMonitor"
        }
        this.tvSampling = tvSampling

        val spacer = View(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }

        // "最小化" button (replaces old "返回" button)
        val btnMinimize = TextView(appContext).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            text = "▼ 最小化"
            setPadding(dp(appContext, 10), dp(appContext, 6), dp(appContext, 10), dp(appContext, 6))
            setOnClickListener { setMinimized(true) }
        }

        header.addView(tvSampling)
        header.addView(spacer)
        header.addView(btnMinimize)

        val btnRec = Button(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            text = "开始录制"
            textSize = 12f
            minHeight = dp(appContext, 36)
            setPadding(dp(appContext, 10), dp(appContext, 8), dp(appContext, 10), dp(appContext, 8))
            setOnClickListener { toggleRecording(this) }
        }
        this.btnRec = btnRec

        val selected = selectedMetricKeys.toSet()
        ensureFrameSampler(appContext, selected)

        // 悬浮窗最小可用：只展示 FPS、CPU、内存、网络、温度（录制仍按勾选指标全量采集）。
        val tvFps = if (selected.contains("fps_app")) metricText(appContext, "FPS：--") else null
        val tvCpu = if (selected.contains("cpu_total_pct")) metricText(appContext, "Total CPU：--") else null
        val tvCpuApp = if (selected.contains("cpu_fg_app_pct")) metricText(appContext, "APP CPU：--") else null
        val tvMem = if (selected.contains("mem_avail_mb") || selected.contains("mem_total_mb")) metricText(appContext, "系统可用内存/系统总内存：--") else null
        val tvNet = if (selected.contains("net_rx_kbps") || selected.contains("net_tx_kbps")) metricText(appContext, "网络接收速率/网络发送速率：--") else null
        val tvTemp = if (selected.contains("battery_temp_c")) metricText(appContext, "温度：--") else null

        this.tvFps = tvFps
        this.tvCpu = tvCpu
        this.tvCpuApp = tvCpuApp
        this.tvMem = tvMem
        this.tvNet = tvNet
        this.tvTemp = tvTemp

        // 其他指标在悬浮窗不展示（避免横屏高度过高导致按钮不可见）
        this.tvAppPss = null
        this.tvCpuFreq = null
        this.tvGpu = null
        this.tvBattery = null
        this.tvVoltage = null

        expandedContent.addView(header)
        expandedContent.addView(space(appContext, 4))

        fun addMetric(v: TextView?) {
            if (v != null) {
                expandedContent.addView(v)
                expandedContent.addView(space(appContext, 4))
            }
        }

        addMetric(tvFps)
        addMetric(tvCpu)
        addMetric(tvCpuApp)
        addMetric(tvMem)
        addMetric(tvNet)
        addMetric(tvTemp)

        expandedContent.addView(space(appContext, 2))
        expandedContent.addView(btnRec)

        container.addView(expandedContent)

        // ========== Minimized bar: FPS + stop button, one line ==========
        val miniBar = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            visibility = View.GONE
        }
        this.miniContainer = miniBar

        val tvMiniInfo = TextView(appContext).apply {
            setTextColor(0xFF00FF88.toInt())
            textSize = 14f
            text = "FPS：--"
            setPadding(dp(appContext, 4), dp(appContext, 4), dp(appContext, 8), dp(appContext, 4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { setMinimized(false) }
        }
        this.tvMiniInfo = tvMiniInfo

        val btnMiniRec = TextView(appContext).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            text = "●"
            setBackgroundColor(0xFF33AA33.toInt()) // green = not recording
            setPadding(dp(appContext, 10), dp(appContext, 6), dp(appContext, 10), dp(appContext, 6))
            gravity = Gravity.CENTER
            setOnClickListener {
                btnRec.performClick()
            }
        }
        this.btnMiniRec = btnMiniRec

        miniBar.addView(tvMiniInfo)
        miniBar.addView(btnMiniRec)

        container.addView(miniBar)

        // Drag to move（尽可能让用户在"非按钮区域"都能拖动）
        attachDrag(container)
        attachDrag(header)
        attachDrag(tvSampling)
        attachDrag(miniBar)
        attachDrag(tvMiniInfo)
        if (tvFps != null) attachDrag(tvFps)
        if (tvCpu != null) attachDrag(tvCpu)
        if (tvCpuApp != null) attachDrag(tvCpuApp)
        if (tvMem != null) attachDrag(tvMem)
        if (tvNet != null) attachDrag(tvNet)
        if (tvTemp != null) attachDrag(tvTemp)

        val screenW = appContext.resources.displayMetrics.widthPixels
        val marginPx = dp(appContext, 12)
        val halfWidthPx = (screenW * 0.5f).toInt().coerceAtLeast(dp(appContext, 180))

        // 默认右上角：用 TOP|START 坐标系更符合"拖动=跟手移动"的直觉
        // 默认左上角
        val initX = marginPx

        val lp = WindowManager.LayoutParams(
            halfWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = marginPx
        }

        params = lp
        rootView = container
        sampler = SystemSampler(appContext).apply { setTargetApp(targetAppPackage) }
        _isShowingState.value = true
        wm.addView(container, lp)

        startSamplingLoop()
    }

    fun hide() {
        stopSamplingLoop()
        stopTicker()
        recording = false
        recStartElapsedMs = 0L
        recStartEpochMs = 0L
        currentSessionId = null
        currentSessionName = null
        currentSamples.clear()

        val v = rootView
        val wm = windowManager
        if (v != null) {
            try {
                wm?.removeView(v)
            } catch (_: Throwable) {
            }
        }

        rootView = null
        windowManager = null
        params = null
        sampler = null
        frameSampler?.stop()
        frameSampler = null
        tvSampling = null
        tvCpu = null
        tvCpuApp = null
        tvMem = null
        tvAppPss = null
        tvFps = null
        tvGpu = null
        tvNet = null
        tvBattery = null
        tvTemp = null
        tvVoltage = null
        tvCpuFreq = null
        expandedContent = null
        miniContainer = null
        tvMiniInfo = null
        btnRec = null
        btnMiniRec = null
        minimized = false

        targetAppLabel = null
        targetAppPackage = null
        selectedMetricKeys = emptyList()

        _isShowingState.value = false
    }

    fun updateSampling(samplingMs: Int) {
        this.samplingMs = samplingMs
    }

    fun updateRetainLimit(retainLimit: Int) {
        this.retainLimit = retainLimit
        RecordingRepository.trim(retainLimit)
        val ctx = rootView?.context?.applicationContext
        if (ctx != null) RecordingRepository.persistAsync(ctx)
    }

    private fun setMinimized(mini: Boolean) {
        minimized = mini
        val wm = windowManager ?: return
        val root = rootView ?: return
        val lp = params ?: return

        if (mini) {
            expandedContent?.visibility = View.GONE
            miniContainer?.visibility = View.VISIBLE
            // Shrink width to wrap content for compact bar
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            expandedContent?.visibility = View.VISIBLE
            miniContainer?.visibility = View.GONE
            // Restore half-screen width
            val screenW = root.context.resources.displayMetrics.widthPixels
            lp.width = (screenW * 0.5f).toInt().coerceAtLeast(dp(root.context, 180))
        }
        try {
            wm.updateViewLayout(root, lp)
        } catch (_: Throwable) {
        }
    }

    private fun ensureFrameSampler(appContext: Context, selected: Set<String>) {
        // 仅当"监控目标就是 iKunMonitor 自身"时，才允许用 Choreographer 采样。
        // 监控其他 App 的 FPS/Jank 在 Android 10+ 上通常需要电脑 ADB（桌面侧执行 dumpsys）。
        val isSelfTarget = !targetAppPackage.isNullOrBlank() && targetAppPackage == appContext.packageName
        val need = isSelfTarget && selected.contains("fps_app")
        if (need) {
            if (frameSampler == null) frameSampler = FrameStatsSampler()
            frameSampler?.start()
        } else {
            frameSampler?.stop()
            frameSampler = null
        }
    }

    private fun toggleRecording(btn: Button) {
        if (!recording) {
            // 将目标 App 拉到前台（不重启，仅 bring-to-front）
            bringTargetAppToFront()

            // 直接开始录制，不再倒计时
            recording = true
            recStartElapsedMs = SystemClock.elapsedRealtime()
            recStartEpochMs = System.currentTimeMillis()
            currentSessionId = RecordingRepository.newSessionId()
            currentSessionName = formatSessionName(recStartEpochMs)
            currentSamples.clear()
            startTicker(btn)
            // Update mini bar button to red (recording)
            btnMiniRec?.setBackgroundColor(0xFFCC3333.toInt())
        } else {
            // 录制结束 == 停止监控
            recording = false
            stopTicker()
            btn.text = "开始录制"
            // Update mini bar button back to green
            btnMiniRec?.setBackgroundColor(0xFF33AA33.toInt())

            val appContext = rootView?.context

            val sid = currentSessionId
            if (sid != null && currentSamples.isNotEmpty()) {
                val endEpoch = System.currentTimeMillis()
                val session = RecordingSession(
                    id = sid,
                    name = currentSessionName ?: formatSessionName(recStartEpochMs),
                    startEpochMs = recStartEpochMs,
                    endEpochMs = endEpoch,
                    samplingMs = samplingMs,
                    samples = currentSamples.toList(),
                    targetAppLabel = targetAppLabel,
                    targetAppPackage = targetAppPackage,
                    metricKeys = selectedMetricKeys,
                )
                RecordingRepository.addSession(appContext?.applicationContext, session, retainLimit)
            }

            // 先隐藏悬浮窗（停止监控），再跳转到录制历史并打开报告
            val openId = sid
            hide()
            val app = appContext?.applicationContext
            if (app != null) {
                if (!openId.isNullOrBlank()) {
                    openHistoryDetail(app, openId)
                } else {
                    openHistoryList(app)
                }
            }
        }
    }

    private fun startTicker(btn: Button) {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                if (!recording) return
                val elapsed = (SystemClock.elapsedRealtime() - recStartElapsedMs).coerceAtLeast(0)
                val totalSec = (elapsed / 1000L).toInt()
                val mm = (totalSec / 60).toString().padStart(2, '0')
                val ss = (totalSec % 60).toString().padStart(2, '0')
                btn.text = "结束录制 $mm:$ss"
                mainHandler.postDelayed(this, 500)
            }
        }
        ticker = r
        mainHandler.post(r)
    }

    private fun stopTicker() {
        val r = ticker ?: return
        mainHandler.removeCallbacks(r)
        ticker = null
    }

    /**
     * 将目标 App 带到前台（不重启 Activity）。
     * 使用 FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP 确保不会重建 Activity。
     */
    private fun bringTargetAppToFront() {
        val pkg = targetAppPackage
        val ctx = rootView?.context ?: return
        if (pkg.isNullOrBlank()) return
        try {
            val pm = ctx.packageManager
            val intent = pm.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ctx.startActivity(intent)
        } catch (_: Throwable) {
            // 静默忽略，不影响录制流程
        }
    }

    private fun startSamplingLoop() {
        stopSamplingLoop()
        // Tell desktop bridge what to sample (best-effort).
        writeDesktopBridgeConfig(enabled = true)
        val r = object : Runnable {
            override fun run() {
                val sampler = sampler
                if (sampler == null || rootView == null) return

                val selected = selectedMetricKeys.toSet()
                val baseValues = sampler.sampleNow(selected)

                val values: Map<String, Double> = run {
                    val fs = frameSampler
                    val isSelfTarget = !targetAppPackage.isNullOrBlank() && targetAppPackage == rootView?.context?.packageName
                    if (isSelfTarget && fs != null && selected.contains("fps_app")) {
                        val (fps, _) = fs.snapshotAndReset()
                        val m = LinkedHashMap(baseValues)
                        if (selected.contains("fps_app") && fps != null) m["fps_app"] = fps
                        m
                    } else {
                        baseValues
                    }
                }

                val cpu = values["cpu_total_pct"]
                val cpuApp = values["cpu_fg_app_pct"]
                val memAvail = values["mem_avail_mb"]
                val memTotal = values["mem_total_mb"]
                val appPss = values["app_pss_mb"]
                val fps = values["fps_app"]
                val rx = values["net_rx_kbps"]
                val tx = values["net_tx_kbps"]
                val batt = values["battery_pct"]
                val temp = values["battery_temp_c"]
                val volt = values["battery_voltage_v"]
                val gpuFreqKHz = values["gpu_freq_khz"]
                val gpuLoadPct = values["gpu_load_pct"]

                val cpuFreqPairs = values
                    .filterKeys { it.startsWith("cpu_freq_khz_") }
                    .toList()
                    .sortedBy { (k, _) -> k.removePrefix("cpu_freq_khz_").toIntOrNull() ?: Int.MAX_VALUE }

                tvCpu?.text = if (cpu == null) "Total CPU：--" else "Total CPU：${format1(cpu)}%"

                // v1: keep overlay clean; do not show desktop/ADB error hints here.
                tvCpuApp?.text = if (cpuApp == null) {
                    "APP CPU：--"
                } else {
                    "APP CPU：${format1(cpuApp)}%"
                }

                tvMem?.text = if (memAvail == null || memTotal == null) {
                    "系统可用内存/系统总内存：--"
                } else {
                    "系统可用内存/系统总内存：${format0(memAvail)}MB / ${format0(memTotal)}MB"
                }
                tvAppPss?.text = if (appPss == null) {
                    "App PSS：--"
                } else {
                    "App PSS：${format0(appPss)}MB"
                }

                tvFps?.text = if (fps == null) {
                    "FPS：--"
                } else {
                    "FPS：${format1(fps)}"
                }

                // Update mini bar info (always, even when expanded — so it's ready when user minimizes)
                tvMiniInfo?.text = if (fps == null) {
                    "FPS：--"
                } else {
                    "FPS：${format1(fps)}"
                }

                tvNet?.text = if (rx == null || tx == null) {
                    "网络接收速率/网络发送速率：--"
                } else {
                    "网络接收速率/网络发送速率：↓${format1(rx)}kbps  ↑${format1(tx)}kbps"
                }

                tvBattery?.text = if (batt == null) "电量：--" else "电量：${format0(batt)}%"
                tvTemp?.text = if (temp == null) "温度：--" else "温度：${format1(temp)}℃"
                tvVoltage?.text = if (volt == null) "电压：--" else "电压：${String.format("%.2f", volt)}V"

                tvGpu?.text = run {
                    val parts = ArrayList<String>(2)
                    if (gpuFreqKHz != null) {
                        val mhz = gpuFreqKHz / 1000.0
                        parts.add("${format0(mhz)}MHz")
                    }
                    if (gpuLoadPct != null) {
                        parts.add("${format1(gpuLoadPct)}%")
                    }
                    if (parts.isEmpty()) "GPU：--" else "GPU：" + parts.joinToString("  ")
                }

                tvCpuFreq?.text = if (cpuFreqPairs.isEmpty()) {
                    "CPU频率：--"
                } else {
                    val shown = cpuFreqPairs.take(4).joinToString("  ") { (k, v) ->
                        val idx = k.removePrefix("cpu_freq_khz_")
                        val mhz = (v / 1000.0)
                        "c$idx ${format0(mhz)}MHz"
                    }
                    val suffix = if (cpuFreqPairs.size > 4) "  …" else ""
                    "CPU频率：$shown$suffix"
                }

                if (recording && currentSessionId != null) {
                    currentSamples.add(
                        MetricSample(
                            timestampEpochMs = System.currentTimeMillis(),
                            values = values,
                        ),
                    )
                }

                mainHandler.postDelayed(this, samplingMs.toLong().coerceAtLeast(50L))
            }
        }

        samplingTicker = r
        mainHandler.post(r)
    }

    private fun stopSamplingLoop() {
        // stop desktop bridge
        writeDesktopBridgeConfig(enabled = false)

        val r = samplingTicker ?: return
        mainHandler.removeCallbacks(r)
        samplingTicker = null
    }

    private fun openHistoryList(appContext: Context) {
        val i = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("open_tab", "history")
        appContext.startActivity(i)
    }

    private fun openHistoryDetail(appContext: Context, sessionId: String) {
        val i = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("open_tab", "history")
            .putExtra("open_session_id", sessionId)
        appContext.startActivity(i)
    }

    private fun formatSessionName(startEpochMs: Long): String {
        // 形如：2026/1/1 12:56（按系统时区）
        val dt = Instant.ofEpochMilli(startEpochMs).atZone(ZoneId.systemDefault())
        return DateTimeFormatter.ofPattern("yyyy/M/d HH:mm").format(dt)
    }

    private fun metricText(ctx: Context, text: String): TextView {
        return TextView(ctx).apply {
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 12f
            this.text = text
        }
    }

    private fun format0(v: Double): String = String.format("%.0f", v)
    private fun format1(v: Double): String = String.format("%.1f", v)

    private fun attachDrag(v: View) {
        var startX = 0
        var startY = 0
        var touchStartRawX = 0f
        var touchStartRawY = 0f
        var isDragging = false
        val touchSlop = 10f // px threshold to distinguish drag from click

        v.setOnTouchListener { view, ev ->
            val lp = params ?: return@setOnTouchListener false
            val wm = windowManager ?: return@setOnTouchListener false
            val root = rootView ?: return@setOnTouchListener false

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchStartRawX = ev.rawX
                    touchStartRawY = ev.rawY
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchStartRawX).toInt()
                    val dy = (ev.rawY - touchStartRawY).toInt()

                    if (!isDragging) {
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                            isDragging = true
                        } else {
                            return@setOnTouchListener true
                        }
                    }

                    val dm = root.context.resources.displayMetrics
                    val viewW = (root.width.takeIf { it > 0 } ?: lp.width).coerceAtLeast(1)
                    val viewH = (root.height.takeIf { it > 0 } ?: 1)

                    val maxX = (dm.widthPixels - viewW).coerceAtLeast(0)
                    val maxY = (dm.heightPixels - viewH).coerceAtLeast(0)

                    lp.x = (startX + dx).coerceIn(0, maxX)
                    lp.y = (startY + dy).coerceIn(0, maxY)

                    try {
                        wm.updateViewLayout(root, lp)
                    } catch (_: Throwable) {
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                    }
                    isDragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun space(ctx: Context, dp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(ctx, dp)).apply {
            width = LinearLayout.LayoutParams.MATCH_PARENT
        }
    }

    private fun dp(ctx: Context, dp: Int): Int {
        val density = ctx.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

}
