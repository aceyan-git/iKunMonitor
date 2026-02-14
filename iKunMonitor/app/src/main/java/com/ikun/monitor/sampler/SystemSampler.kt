package com.ikun.monitor.sampler

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * 轻量系统采样器：先覆盖 Tier-0 的部分系统级指标，便于你在模拟器/真机快速体验闭环。
 *
 * 注意：这里做的是“尽可能通用”的实现；后续会补前台 App 维度（需要 pid/uid 对齐、包可见性等）。
 */
class SystemSampler(private val appContext: Context) {
    private companion object {
        const val DESKTOP_BRIDGE_METRICS = "pm_desktop_bridge_metrics.json"
        const val DESKTOP_BRIDGE_MAX_AGE_MS = 3_000L
    }

    private var prevCpuTotal: Long? = null
    private var prevCpuIdle: Long? = null

    // Fallback: use process CPU time when /proc/stat is not accessible/parsable.
    private var prevProcCpuMs: Long? = null
    private var prevProcAtElapsedMs: Long? = null

    private var prevNetAtElapsedMs: Long? = null
    private var prevRxBytes: Long? = null
    private var prevTxBytes: Long? = null

    // Target app (best-effort)
    private var targetAppPackage: String? = null

    // When targetAppPackage is null, we may try to resolve the current foreground app via UsageStats.
    private var cachedResolvedTargetPackage: String? = null
    private var cachedTargetPid: Int? = null
    private var lastResolvePidAtElapsedMs: Long = 0L

    private var prevTargetCpuTicks: Long? = null
    private var prevTargetCpuAtElapsedMs: Long? = null

    // Target app gfxinfo (for self-target FPS/Jank via FrameStatsSampler)
    private var prevGfxTotalFrames: Long? = null
    private var prevGfxJankyFrames: Long? = null
    private var prevGfxAtElapsedMs: Long? = null

    // Desktop bridge cache (ADB-side sampling -> write file -> app reads)
    private var cachedDesktopAtEpochMs: Long = 0L
    private var cachedDesktopPayloadEpochMs: Long = 0L
    private var cachedDesktopTargetPkg: String? = null
    private var cachedDesktopValues: Map<String, Double> = emptyMap()

    // GPU (best-effort, sysfs varies by vendor/ROM)
    private var cachedGpuDevfreqDir: String? = null
    private var lastResolveGpuDevfreqAtElapsedMs: Long = 0L

    // For GPU load computed from busy_time/total_time
    private var prevGpuBusyTime: Long? = null
    private var prevGpuTotalTime: Long? = null

    fun setTargetApp(packageName: String?) {
        targetAppPackage = packageName?.takeIf { it.isNotBlank() }
        cachedResolvedTargetPackage = null
        cachedTargetPid = null
        lastResolvePidAtElapsedMs = 0L
        prevTargetCpuTicks = null
        prevTargetCpuAtElapsedMs = null
        prevGfxTotalFrames = null
        prevGfxJankyFrames = null
        prevGfxAtElapsedMs = null
    }

    fun sampleNow(selectedKeys: Set<String>? = null): Map<String, Double> {
        fun want(key: String): Boolean = selectedKeys == null || selectedKeys.contains(key)

        val out = LinkedHashMap<String, Double>()
        val desktop = readDesktopBridgeMetricsBestEffort()

        if (want("cpu_total_pct")) readCpuTotalPct()?.let { out["cpu_total_pct"] = it }
        if (want("mem_avail_mb") || want("mem_total_mb")) {
            readMemMb()?.let { (avail, total) ->
                if (want("mem_avail_mb")) out["mem_avail_mb"] = avail
                if (want("mem_total_mb")) out["mem_total_mb"] = total
            }
        }

        if (want("net_rx_kbps") || want("net_tx_kbps")) {
            readNetKbps()?.let { (rx, tx) ->
                if (want("net_rx_kbps")) out["net_rx_kbps"] = rx
                if (want("net_tx_kbps")) out["net_tx_kbps"] = tx
            }
        }

        if (want("battery_pct")) readBatteryPct()?.let { out["battery_pct"] = it }
        if (want("battery_temp_c")) readBatteryTempC()?.let { out["battery_temp_c"] = it }
        if (want("battery_current_ma")) readBatteryCurrentMa()?.let { out["battery_current_ma"] = it }
        if (want("battery_voltage_v")) readBatteryVoltageV()?.let { out["battery_voltage_v"] = it }

        // CPU freq per core (Tier-1, best-effort)
        val wantCpuFreq = selectedKeys == null || selectedKeys.any { it == "cpu_freq_khz_*" || it.startsWith("cpu_freq_khz_") }
        if (wantCpuFreq) {
            readCpuFreqPerCoreKHz()?.forEach { (k, v) ->
                out[k] = v
            }
        }

        // GPU (Tier-1, best-effort)
        if (want("gpu_freq_khz")) readGpuFreqKHz()?.let { out["gpu_freq_khz"] = it }
        if (want("gpu_load_pct")) readGpuLoadPct()?.let { out["gpu_load_pct"] = it }

        // Target app metrics (best-effort)
        if (want("app_pss_mb") || want("cpu_fg_app_pct") || want("fps_app")) {
            val pkg = resolveTargetPackageBestEffort()
            val isSelf = !pkg.isNullOrBlank() && pkg == appContext.packageName

            // 0) Prefer desktop bridge values for cross-app metrics.
            if (!isSelf && pkg != null && desktop != null && desktop.targetPackage == pkg) {
                if (want("cpu_fg_app_pct")) desktop.values["cpu_fg_app_pct"]?.let { out["cpu_fg_app_pct"] = it }
                if (want("app_pss_mb")) desktop.values["app_pss_mb"]?.let { out["app_pss_mb"] = it }
                if (want("fps_app")) desktop.values["fps_app"]?.let { out["fps_app"] = it }
            }

            // 1) Local best-effort (may be blocked on newer Android)
            val pid = if (want("app_pss_mb") || want("cpu_fg_app_pct")) resolveTargetPidBestEffort(pkg) else null

            if (pid != null) {
                if (want("app_pss_mb") && !out.containsKey("app_pss_mb")) readAppPssMb(pid, pkg)?.let { out["app_pss_mb"] = it }
                if (want("cpu_fg_app_pct") && !out.containsKey("cpu_fg_app_pct")) readTargetCpuPct(pid, pkg)?.let { out["cpu_fg_app_pct"] = it }
            }

            if (pkg != null && want("fps_app") && isSelf) {
                readTargetFpsAndJank(pkg)?.let { (fps, jankPct) ->
                    if (want("fps_app") && fps != null) out["fps_app"] = fps
                }
            }
        }

        return out
    }

    private fun readCpuTotalPct(): Double? {
        // Primary: system-wide CPU load from /proc/stat.
        val statPct = readSystemCpuTotalPctFromProcStat()
        if (statPct != null) return statPct

        // Fallback: approximate system load by process CPU usage normalized by core count.
        return readProcessCpuPctFallback()
    }

    private fun readSystemCpuTotalPctFromProcStat(): Double? {
        val line = readFirstLine("/proc/stat") ?: return null
        val parts = line.trim().split(Regex("\\s+"))
        // Some devices use tabs, so don't rely on startsWith("cpu ").
        if (parts.firstOrNull() != "cpu") return null

        // cpu user nice system idle iowait irq softirq steal guest guest_nice
        val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 5) return null

        val idle = nums.getOrNull(3) ?: return null
        val iowait = nums.getOrNull(4) ?: 0L
        val idleAll = idle + iowait
        val total = nums.sum()

        val prevTotal = prevCpuTotal
        val prevIdle = prevCpuIdle
        prevCpuTotal = total
        prevCpuIdle = idleAll

        if (prevTotal == null || prevIdle == null) return null

        val deltaTotal = (total - prevTotal).coerceAtLeast(0)
        val deltaIdle = (idleAll - prevIdle).coerceAtLeast(0)
        if (deltaTotal <= 0) return null

        val usage = (deltaTotal - deltaIdle).toDouble() / deltaTotal.toDouble() * 100.0
        return usage.coerceIn(0.0, 100.0)
    }

    private fun readProcessCpuPctFallback(): Double? {
        val nowElapsed = SystemClock.elapsedRealtime()
        val cpuMs = android.os.Process.getElapsedCpuTime()

        val prevAt = prevProcAtElapsedMs
        val prevCpu = prevProcCpuMs

        prevProcAtElapsedMs = nowElapsed
        prevProcCpuMs = cpuMs

        if (prevAt == null || prevCpu == null) return null

        val dtWallMs = (nowElapsed - prevAt).coerceAtLeast(1L)
        val dtCpuMs = (cpuMs - prevCpu).coerceAtLeast(0L)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        val pct = dtCpuMs.toDouble() / (dtWallMs.toDouble() * cores.toDouble()) * 100.0
        return pct.coerceIn(0.0, 100.0)
    }

    private fun readMemMb(): Pair<Double, Double>? {
        // 优先读 /proc/meminfo，兼容性最好
        val memInfo = File("/proc/meminfo")
        if (!memInfo.exists()) return null

        var totalKb: Long? = null
        var availKb: Long? = null

        BufferedReader(FileReader(memInfo)).use { br ->
            while (true) {
                val line = br.readLine() ?: break
                when {
                    line.startsWith("MemTotal:") -> totalKb = extractKb(line)
                    line.startsWith("MemAvailable:") -> availKb = extractKb(line)
                }
                if (totalKb != null && availKb != null) break
            }
        }

        val t = totalKb ?: return null
        val a = availKb ?: return null
        return Pair(a / 1024.0, t / 1024.0)
    }

    private fun readNetKbps(): Pair<Double, Double>? {
        val nowElapsed = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx < 0 || tx < 0) return null

        val prevAt = prevNetAtElapsedMs
        val prevRx = prevRxBytes
        val prevTx = prevTxBytes

        prevNetAtElapsedMs = nowElapsed
        prevRxBytes = rx
        prevTxBytes = tx

        if (prevAt == null || prevRx == null || prevTx == null) return null

        val dtSec = ((nowElapsed - prevAt).coerceAtLeast(1)).toDouble() / 1000.0
        val drx = (rx - prevRx).coerceAtLeast(0)
        val dtx = (tx - prevTx).coerceAtLeast(0)

        val rxKbps = drx.toDouble() * 8.0 / 1000.0 / dtSec
        val txKbps = dtx.toDouble() * 8.0 / 1000.0 / dtSec

        return Pair(rxKbps, txKbps)
    }

    private fun readBatteryPct(): Double? {
        // Prefer sticky ACTION_BATTERY_CHANGED
        val i = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i != null) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                val pct = level.toDouble() * 100.0 / scale.toDouble()
                return pct.coerceIn(0.0, 100.0)
            }
        }

        // Fallback
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) pct.toDouble() else null
    }

    private fun readBatteryTempC(): Double? {
        val i = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (t == Int.MIN_VALUE) return null
        // Unit: 0.1 °C
        return t.toDouble() / 10.0
    }

    private fun readBatteryCurrentMa(): Double? {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        // Many devices return microamps (µA). Convert to mA.
        if (ua == Int.MIN_VALUE) return null
        return ua.toDouble() / 1000.0
    }

    private fun readBatteryVoltageV(): Double? {
        val i = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
        if (mv == Int.MIN_VALUE || mv <= 0) return null
        return mv.toDouble() / 1000.0
    }

    private fun readCpuFreqPerCoreKHz(): Map<String, Double>? {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val out = LinkedHashMap<String, Double>()

        for (i in 0 until cores) {
            val v = readCpuCoreCurFreqKHz(i) ?: continue
            if (v > 0) out["cpu_freq_khz_$i"] = v.toDouble()
        }

        return out.takeIf { it.isNotEmpty() }
    }

    private fun readCpuCoreCurFreqKHz(coreIndex: Int): Long? {
        val base = "/sys/devices/system/cpu/cpu$coreIndex/cpufreq"
        return readFirstLine("$base/scaling_cur_freq")?.trim()?.toLongOrNull()
            ?: readFirstLine("$base/cpuinfo_cur_freq")?.trim()?.toLongOrNull()
    }

    private fun readGpuFreqKHz(): Double? {
        // Qualcomm KGSL (most common)
        readFirstLineBestEffort("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")
            ?.trim()
            ?.toLongOrNull()
            ?.let { hzOrKhz ->
                val khz = if (hzOrKhz > 1_000_000L) (hzOrKhz / 1000L) else hzOrKhz
                if (khz > 0) return khz.toDouble()
            }

        // Generic devfreq
        val dir = resolveGpuDevfreqDirBestEffort() ?: return null
        readFirstLineBestEffort("$dir/cur_freq")
            ?.trim()
            ?.toLongOrNull()
            ?.let { hzOrKhz ->
                val khz = if (hzOrKhz > 1_000_000L) (hzOrKhz / 1000L) else hzOrKhz
                if (khz > 0) return khz.toDouble()
            }

        return null
    }

    private fun readGpuLoadPct(): Double? {
        // Qualcomm KGSL: direct percent
        readFirstLineBestEffort("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            ?.trim()
            ?.toIntOrNull()
            ?.let { pct ->
                return pct.toDouble().coerceIn(0.0, 100.0)
            }

        val dir = resolveGpuDevfreqDirBestEffort() ?: return null

        // Some devfreq nodes expose utilization/load directly.
        val util = readFirstLineBestEffort("$dir/utilization")?.trim()?.toIntOrNull()
            ?: readFirstLineBestEffort("$dir/load")?.trim()?.toIntOrNull()

        if (util != null) {
            val pct = when {
                util in 0..100 -> util.toDouble()
                util in 0..255 -> util.toDouble() / 255.0 * 100.0
                else -> null
            }
            if (pct != null) return pct.coerceIn(0.0, 100.0)
        }

        // Otherwise compute from busy_time/total_time (delta ratio)
        val busy = readFirstLineBestEffort("$dir/busy_time")?.trim()?.toLongOrNull()
        val total = readFirstLineBestEffort("$dir/total_time")?.trim()?.toLongOrNull()
        if (busy != null && total != null && busy >= 0 && total > 0) {
            val prevBusy = prevGpuBusyTime
            val prevTotal = prevGpuTotalTime
            prevGpuBusyTime = busy
            prevGpuTotalTime = total

            if (prevBusy != null && prevTotal != null) {
                val dBusy = (busy - prevBusy).coerceAtLeast(0L)
                val dTotal = (total - prevTotal).coerceAtLeast(0L)
                if (dTotal > 0L) {
                    return (dBusy.toDouble() / dTotal.toDouble() * 100.0).coerceIn(0.0, 100.0)
                }
            }
        }

        return null
    }

    private fun resolveGpuDevfreqDirBestEffort(): String? {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedGpuDevfreqDir
        if (!cached.isNullOrBlank() && (now - lastResolveGpuDevfreqAtElapsedMs) < 10_000L) {
            return cached
        }
        lastResolveGpuDevfreqAtElapsedMs = now

        fun isGpuLike(name: String): Boolean {
            val n = name.lowercase()
            return n.contains("gpu") || n.contains("kgsl") || n.contains("mali") || n.contains("g3d") || n.contains("3d")
        }

        // Local scan
        try {
            val base = File("/sys/class/devfreq")
            val dirs = base.listFiles()?.filter { it.isDirectory }?.map { it.absolutePath } ?: emptyList()
            val pick = dirs.firstOrNull { isGpuLike(File(it).name) }
            if (!pick.isNullOrBlank()) {
                cachedGpuDevfreqDir = pick
                return pick
            }
        } catch (_: Throwable) {
        }

        cachedGpuDevfreqDir = null
        return null
    }

    private data class DesktopBridgeSnapshot(
        val targetPackage: String,
        val epochMs: Long,
        val values: Map<String, Double>,
    )

    private fun readDesktopBridgeMetricsBestEffort(): DesktopBridgeSnapshot? {
        val now = System.currentTimeMillis()
        if ((now - cachedDesktopAtEpochMs) < 100L) {
            val pkg = cachedDesktopTargetPkg
            if (pkg != null && (now - cachedDesktopPayloadEpochMs) <= DESKTOP_BRIDGE_MAX_AGE_MS) {
                return DesktopBridgeSnapshot(pkg, cachedDesktopPayloadEpochMs, cachedDesktopValues)
            }
            return null
        }
        cachedDesktopAtEpochMs = now

        fun parse(file: File): DesktopBridgeSnapshot? {
            if (!file.exists()) return null

            // IMPORTANT:
            // The desktop sampler may stamp payload time with the computer's clock.
            // If device time differs, (now - t) may look extremely large and cause valid data to be dropped.
            // Use file.lastModified() as the freshness source (device-local time) instead of trusting payload "t".
            val lastModified = runCatching { file.lastModified() }.getOrDefault(0L)
            if (lastModified > 0L && (now - lastModified) > DESKTOP_BRIDGE_MAX_AGE_MS) return null

            return try {
                val text = file.readText()
                val obj = JSONObject(text)
                val pkg = obj.optString("pkg").takeIf { it.isNotBlank() } ?: return null
                val payloadT = obj.optLong("t")
                val vObj = obj.optJSONObject("v") ?: JSONObject()
                val map = LinkedHashMap<String, Double>()
                val itKeys = vObj.keys()
                while (itKeys.hasNext()) {
                    val k = itKeys.next()
                    map[k] = vObj.optDouble(k)
                }

                val ts = if (lastModified > 0L) lastModified else payloadT
                DesktopBridgeSnapshot(pkg, ts, map)
            } catch (_: Throwable) {
                null
            }
        }

        // Prefer internal bridge file (works well with `adb shell run-as`), fallback to external.
        val internal = parse(File(appContext.filesDir, DESKTOP_BRIDGE_METRICS))
        val external = run {
            val dir = appContext.getExternalFilesDir(null) ?: return@run null
            parse(File(dir, DESKTOP_BRIDGE_METRICS))
        }

        val snap = listOfNotNull(internal, external).maxByOrNull { it.epochMs } ?: return null

        cachedDesktopTargetPkg = snap.targetPackage
        cachedDesktopPayloadEpochMs = snap.epochMs
        cachedDesktopValues = snap.values

        return snap
    }

    private fun readFirstLineBestEffort(path: String): String? {
        // Android 10+ 上很多节点/进程信息对普通 App 不可读；这里保持 best-effort，读不到就返回 null。
        return readFirstLine(path)
    }

    private fun resolveTargetPackageBestEffort(): String? {
        // Prefer explicit target from UI; otherwise try current foreground app (UsageStats best-effort).
        val resolvedPkg = (targetAppPackage ?: resolveForegroundPackageByUsageEvents())
            ?.takeIf { it.isNotBlank() }
            ?: return null

        // If target package changes, drop cached pid and diff state.
        if (cachedResolvedTargetPackage != resolvedPkg) {
            cachedResolvedTargetPackage = resolvedPkg
            cachedTargetPid = null
            prevTargetCpuTicks = null
            prevTargetCpuAtElapsedMs = null
            prevGfxTotalFrames = null
            prevGfxJankyFrames = null
            prevGfxAtElapsedMs = null
        }

        return resolvedPkg
    }

    private fun resolveTargetPidBestEffort(resolvedPkg: String?): Int? {
        val pkg = resolvedPkg?.takeIf { it.isNotBlank() } ?: return null

        // Throttle resolving to reduce overhead.
        val now = SystemClock.elapsedRealtime()
        if (cachedTargetPid != null && (now - lastResolvePidAtElapsedMs) < 3_000L) {
            return cachedTargetPid
        }
        lastResolvePidAtElapsedMs = now

        // Same-process fast path.
        if (pkg == appContext.packageName) {
            cachedTargetPid = Process.myPid()
            return cachedTargetPid
        }

        // 1) Try ActivityManager.runningAppProcesses (best-effort; ROM/版本差异很大)
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val procs = am?.runningAppProcesses
        val match = procs?.firstOrNull { it.processName == pkg || it.processName.startsWith("$pkg:") }
        val pidFromAm = match?.pid
        if (pidFromAm != null) {
            cachedTargetPid = pidFromAm
            return pidFromAm
        }

        // 3) Fallback: scan /proc/*/cmdline (may be blocked on newer Android / some ROMs)
        val pidFromProc = findPidByProcfsCmdline(pkg)
        cachedTargetPid = pidFromProc
        if (pidFromProc == null) {
            prevTargetCpuTicks = null
            prevTargetCpuAtElapsedMs = null
        }
        return pidFromProc
    }


    private fun resolveForegroundPackageByUsageEvents(): String? {
        if (!hasUsageStatsPermission()) return null
        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val begin = now - 10_000L

        return try {
            val ev = UsageEvents.Event()
            val events = usm.queryEvents(begin, now)
            var lastPkg: String? = null
            var lastTs: Long = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val type = ev.eventType
                val ts = ev.timeStamp
                val pkg = ev.packageName
                if (pkg.isNullOrBlank()) continue

                val isResume = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                        type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    else ->
                        type == UsageEvents.Event.MOVE_TO_FOREGROUND
                }

                if (isResume && ts >= lastTs) {
                    lastTs = ts
                    lastPkg = pkg
                }
            }

            lastPkg
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun findPidByProcfsCmdline(pkg: String): Int? {
        return try {
            val procDir = File("/proc")
            val pidDirs = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return null

            for (f in pidDirs) {
                val pid = f.name.toIntOrNull() ?: continue
                val cmdline = readFirstLine("/proc/$pid/cmdline") ?: continue
                // cmdline is NUL-separated, keep first token
                val name = cmdline.substringBefore('\u0000').trim()
                if (name == pkg || name.startsWith("$pkg:")) return pid
            }

            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun readAppPssMb(pid: Int?, pkg: String?): Double? {
        // Fast path: current process is always readable.
        if (pid != null && pid == Process.myPid()) {
            return try {
                val pssKb = Debug.getPss()
                if (pssKb <= 0) null else pssKb.toDouble() / 1024.0
            } catch (_: Throwable) {
                null
            }
        }

        // Best-effort fallback: ActivityManager (may be blocked / return 0 on newer Android).
        if (pid != null) {
            return try {
                val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
                val infos = am.getProcessMemoryInfo(intArrayOf(pid))
                val pssKb = infos.firstOrNull()?.totalPss ?: return null
                if (pssKb <= 0) return null
                pssKb.toDouble() / 1024.0
            } catch (_: Throwable) {
                null
            }
        }

        return null
    }


    private fun readTargetCpuPct(pid: Int, pkg: String?): Double? {
        val nowElapsed = SystemClock.elapsedRealtime()

        val ticks = readProcCpuTicksLocal(pid) ?: return null

        val prevAt = prevTargetCpuAtElapsedMs
        val prevTicks = prevTargetCpuTicks

        prevTargetCpuAtElapsedMs = nowElapsed
        prevTargetCpuTicks = ticks

        if (prevAt == null || prevTicks == null) return null

        val dtWallMs = (nowElapsed - prevAt).coerceAtLeast(1L)
        val dtTicks = (ticks - prevTicks).coerceAtLeast(0L)
        if (dtTicks <= 0) return null

        val hz = try {
            Os.sysconf(OsConstants._SC_CLK_TCK).toDouble().coerceAtLeast(1.0)
        } catch (_: Throwable) {
            100.0
        }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        val cpuSec = dtTicks.toDouble() / hz
        val wallSec = dtWallMs.toDouble() / 1000.0
        val pct = cpuSec / (wallSec * cores.toDouble()) * 100.0
        return pct.coerceIn(0.0, 100.0)
    }

    private fun readProcCpuTicksLocal(pid: Int): Long? {
        // /proc/[pid]/stat fields: 14=utime, 15=stime (clock ticks)
        val line = readFirstLine("/proc/$pid/stat") ?: return null
        return parseProcStatTicks(line)
    }


    private fun parseProcStatTicks(line: String): Long? {
        // The second field (comm) is inside parentheses and may contain spaces.
        val rParen = line.lastIndexOf(')')
        if (rParen <= 0) return null

        val after = line.substring(rParen + 1).trim()
        val parts = after.split(Regex("\\s+"))
        // `after` starts from field3(state), so utime(field14) => index 11, stime(field15) => index 12
        if (parts.size <= 12) return null

        val utime = parts.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = parts.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    private fun readTargetFpsAndJank(pkg: String): Pair<Double?, Double?>? {
        // 跨 App FPS/Jank 在 Android 10+ 上通常需要电脑 ADB（桌面侧执行 dumpsys 并回传）。
        // 端侧 App 这里保持 best-effort：暂不直接采集。
        return null
    }

    private fun extractKb(line: String): Long? {
        // format: "MemTotal:       123456 kB"
        val parts = line.trim().split(Regex("\\s+"))
        return parts.getOrNull(1)?.toLongOrNull()
    }

    private fun readFirstLine(path: String): String? {
        return try {
            BufferedReader(FileReader(File(path))).use { it.readLine() }
        } catch (_: Throwable) {
            null
        }
    }
}
