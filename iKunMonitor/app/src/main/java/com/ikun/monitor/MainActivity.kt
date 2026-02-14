@file:OptIn(ExperimentalLayoutApi::class)

package com.ikun.monitor


import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.ikun.monitor.data.RecordingSession
import com.ikun.monitor.overlay.OverlayController
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.ikun.monitor.ui.LineChart
import com.ikun.monitor.ui.LineSpec
import com.ikun.monitor.ui.MultiLineChart
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val latestIntentState = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        latestIntentState.value = intent

        com.ikun.monitor.data.RecordingRepository.init(applicationContext)

        setContent {
            val latestIntent by latestIntentState
            iKunMonitorApp(latestIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        latestIntentState.value = intent
    }
}

private enum class Tab(val label: String) {
    Monitor("开始监控"),
    History("录制历史"),
    Settings("设置"),
}

private data class TargetApp(
    val label: String,
    val packageName: String,
)

private data class Metric(
    val key: String,
    val name: String,
)

/**
 * 指标清单：字段名严格对齐 `metrics.md`（UI 不再展示 tier/分类）。
 */
private val MetricCatalog: List<Metric> = listOf(
    Metric("fps_app", "FPS"),

    Metric("cpu_total_pct", "Total CPU"),
    Metric("cpu_fg_app_pct", "APP CPU"),
    Metric("cpu_freq_khz_*", "CPU频率"),

    Metric("mem_avail_mb", "系统可用内存"),
    Metric("mem_total_mb", "系统总内存"),
    Metric("app_pss_mb", "APP 内存"),

    Metric("net_rx_kbps", "网络接收速率"),
    Metric("net_tx_kbps", "网络发送速率"),

    Metric("battery_temp_c", "温度"),
    Metric("battery_pct", "电量百分比"),
    Metric("battery_voltage_v", "电池电压"),
)

// 这些指标在 Android 10+ 上做"跨 App 采集"通常需要电脑 ADB 配合（桌面采集）。
private val DesktopAdbRequiredMetricKeys: Set<String> = setOf(
    "cpu_fg_app_pct",
    "app_pss_mb",
    "fps_app",
)

private fun isDesktopAdbRequiredMetric(key: String): Boolean {
    return DesktopAdbRequiredMetricKeys.contains(key)
}

@Composable
private fun AdbChip() {
    Box(
        modifier = Modifier
            .background(Color(0xFFFFF3CD), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "ADB",
            color = Color(0xFFB54708),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun OverlayPermissionGateScreen() {
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(id = R.drawable.ikun_logo),
            contentDescription = "iKunMonitor",
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(14.dp))
        Text("iKunMonitor 需要开启悬浮窗权限", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("未开启将无法进入。请在系统设置中允许“在其他应用上层显示”。", color = Color(0xFF666666), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { openOverlayPermissionSettings(ctx) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text("去开启悬浮窗权限") }
    }
}

@Composable
private fun iKunMonitorApp(latestIntent: Intent?) {
    // WeChat-like flat light theme (simplified)
    val colorScheme = MaterialTheme.colorScheme.copy(
        primary = Color(0xFF07C160),
        secondary = Color(0xFF10AEFF),
        surface = Color.White,
        background = Color(0xFFF6F7F9),
    )

    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppScaffold(latestIntent)
        }
    }
}

@Composable
private fun AppScaffold(latestIntent: Intent?) {
    var currentTab by remember { mutableStateOf(Tab.Monitor) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }

    // 启动门禁：缺少任何一个必要权限则不允许进入 App。
    // 需要在从系统设置返回后“自动重新检查”，否则会出现授权完仍提示需授权的问题。
    val gateCtx = LocalContext.current
    val lifecycleOwner = remember(gateCtx) { gateCtx as? androidx.lifecycle.LifecycleOwner }

    var overlayGranted by remember { mutableStateOf(hasOverlayPermission(gateCtx)) }

    DisposableEffect(lifecycleOwner, gateCtx) {
        val owner = lifecycleOwner
        if (owner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    overlayGranted = hasOverlayPermission(gateCtx)
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    if (!overlayGranted) {
        OverlayPermissionGateScreen()
        return
    }


    LaunchedEffect(
        latestIntent?.getStringExtra("open_tab"),
        latestIntent?.getStringExtra("open_session_id"),
    ) {
        val openTab = latestIntent?.getStringExtra("open_tab")
        val openSessionId = latestIntent?.getStringExtra("open_session_id")

        if (openTab == "monitor") {
            currentTab = Tab.Monitor
            selectedSessionId = null
        }
        if (openTab == "history") {
            currentTab = Tab.History
        }
        if (!openSessionId.isNullOrBlank()) {
            currentTab = Tab.History
            selectedSessionId = openSessionId
        }
    }

    // v1 state (later move into ViewModel + DataStore)
    var selectedApp by remember { mutableStateOf<TargetApp?>(null) }
    val selectedMetrics = remember { mutableStateListOf<String>() }

    // 默认勾选所有指标（没数据也会在报告里展示空图）
    LaunchedEffect(Unit) {
        if (selectedMetrics.isEmpty()) {
            MetricCatalog
                .map { it.key }
                .distinct()
                .forEach { selectedMetrics.add(it) }
        }
    }

    var samplingMs by remember { mutableStateOf(1000) }
    var retainLimit by remember { mutableStateOf(20) }
    val isMonitoring by OverlayController.isShowingState

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = currentTab == Tab.Monitor,
                    onClick = { currentTab = Tab.Monitor },
                    label = { Text(Tab.Monitor.label) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = Tab.Monitor.label,
                            tint = if (currentTab == Tab.Monitor) MaterialTheme.colorScheme.primary else Color(0xFF888888),
                        )
                    },
                )
                NavigationBarItem(
                    selected = currentTab == Tab.History,
                    onClick = { currentTab = Tab.History },
                    label = { Text(Tab.History.label) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = Tab.History.label,
                            tint = if (currentTab == Tab.History) MaterialTheme.colorScheme.primary else Color(0xFF888888),
                        )
                    },
                )
                NavigationBarItem(
                    selected = currentTab == Tab.Settings,
                    onClick = { currentTab = Tab.Settings },
                    label = { Text(Tab.Settings.label) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = Tab.Settings.label,
                            tint = if (currentTab == Tab.Settings) MaterialTheme.colorScheme.primary else Color(0xFF888888),
                        )
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            when (currentTab) {
                Tab.Monitor -> StartMonitorScreen(
                    selectedApp = selectedApp,
                    onSelectedAppChange = { selectedApp = it },
                    selectedMetricKeys = selectedMetrics,
                    onToggleMetric = { key ->
                        if (selectedMetrics.contains(key)) selectedMetrics.remove(key) else selectedMetrics.add(key)
                    },
                    onSelectAllMetrics = {
                        selectedMetrics.clear()
                        MetricCatalog
                            .map { it.key }
                            .distinct()
                            .forEach { selectedMetrics.add(it) }
                    },
                    isMonitoring = isMonitoring,
                    onToggleMonitoring = { ctx ->
                        if (!isMonitoring) {
                            if (!hasOverlayPermission(ctx)) {
                                currentTab = Tab.Settings
                                openOverlayPermissionSettings(ctx)
                                return@StartMonitorScreen
                            }
                            OverlayController.show(
                                ctx.applicationContext,
                                targetAppLabel = selectedApp?.label,
                                targetAppPackage = selectedApp?.packageName,
                                selectedMetricKeys = selectedMetrics.toList(),
                                samplingMs = samplingMs,
                                retainLimit = retainLimit,
                            )
                            selectedApp?.packageName?.let { launchTargetApp(ctx, it) }
                            (ctx as? android.app.Activity)?.moveTaskToBack(true)
                        } else {
                            OverlayController.hide()
                        }
                    },
                )

                Tab.History -> HistoryScreen(
                    selectedSessionId = selectedSessionId,
                    onOpenSession = { selectedSessionId = it },
                    onBackToList = { selectedSessionId = null },
                )

                Tab.Settings -> SettingsScreen(
                    retainLimit = retainLimit,
                    onRetainLimitChange = {
                        retainLimit = it
                        if (isMonitoring) OverlayController.updateRetainLimit(it)
                    },
                    overlayGranted = overlayGranted,
                    onOpenOverlaySettings = { openOverlayPermissionSettings(it) },
                )
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    selectedSessionId: String?,
    onOpenSession: (String) -> Unit,
    onBackToList: () -> Unit,
) {
    val sessions = com.ikun.monitor.data.RecordingRepository.sessions
    val session = selectedSessionId?.let { com.ikun.monitor.data.RecordingRepository.findById(it) }

    val ctx = LocalContext.current
    var exporting by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf<RecordingSession?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri ->
            val target = exportTarget
            if (uri != null && target != null) {
                exporting = true
                exportSessionCsvAsync(ctx.applicationContext, uri, target) { ok, err ->
                    exporting = false
                    exportTarget = null
                    if (ok) {
                        Toast.makeText(ctx, "已导出 CSV", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, err ?: "导出失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                exportTarget = null
            }
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.ikun_logo),
                    contentDescription = "iKunMonitor",
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.size(10.dp))
                Text("录制历史", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
            }
            if (session != null) {
                TextButton(
                    enabled = !exporting,
                    onClick = {
                        exportTarget = session
                        exportLauncher.launch(buildSessionCsvFileName(session))
                    },
                ) {
                    Text(if (exporting) "导出中…" else "导出 CSV")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (selectedSessionId == null) {
            if (sessions.isEmpty()) {
                Text("暂无录制记录。请在悬浮窗点击「开始录制」。", color = Color(0xFF666666))
                return
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sessions) { s ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clickable { onOpenSession(s.id) },
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = s.name,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            val durSec = ((s.endEpochMs - s.startEpochMs).coerceAtLeast(0) / 1000.0)
                            val appName = s.targetAppLabel ?: s.targetAppPackage ?: "未知App"
                            val metricCount = if (s.metricKeys.isNotEmpty()) s.metricKeys.size else (s.samples.firstOrNull()?.values?.size ?: 0)
                            Text(
                                text = "$appName  ·  采样：${if (s.samplingMs < 1000) "${s.samplingMs}ms" else "${s.samplingMs / 1000}s"}  ·  时长：${String.format("%.1f", durSec)}s  ·  指标：${metricCount}项  ·  样本：${s.samples.size}",
                                color = Color(0xFF666666),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        } else {
            if (session == null) {
                Text("会话不存在或已被清理。", color = Color(0xFF666666))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBackToList) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("返回列表")
                }
                return
            }

            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBackToList) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                            }
                            Text("会话详情 ${session.name}", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    val durSec = ((session.endEpochMs - session.startEpochMs).coerceAtLeast(0) / 1000.0)
                    val appName = session.targetAppLabel ?: session.targetAppPackage ?: "未知App"
                    val metricCount = if (session.metricKeys.isNotEmpty()) session.metricKeys.size else (session.samples.firstOrNull()?.values?.size ?: 0)
                    Text(
                        text = "$appName  ·  时长：${String.format("%.1f", durSec)}s  ·  指标：${metricCount}项  ·  样本：${session.samples.size}",
                        color = Color(0xFF666666),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll),
            ) {
                var selectedKeys: Set<String> = if (session.metricKeys.isNotEmpty()) {
                    session.metricKeys.toSet()
                } else {
                    session.samples.firstOrNull()?.values?.keys ?: emptySet()
                }
                // 兼容旧会话：如果落盘时没有写入 metricKeys，但样本里带有 cpu_freq_khz_0..n，则认为勾选了 cpu_freq_khz_*
                if (selectedKeys.any { it.startsWith("cpu_freq_khz_") }) {
                    selectedKeys = selectedKeys + "cpu_freq_khz_*"
                }

                val nameByKey = remember { MetricCatalog.associate { it.key to it.name } }
                fun displayName(key: String): String = nameByKey[key] ?: key

                fun series(key: String): List<Double> = session.samples.map { it.values[key] ?: Double.NaN }
                fun finite(vals: List<Double>): List<Double> = vals.filter { it.isFinite() }

                val orderedKeys = MetricCatalog
                    .map { it.key }
                    .filter { selectedKeys.contains(it) }

                orderedKeys.forEach { key ->
                    when (key) {
                        "cpu_freq_khz_*" -> {
                            val coreKeys = session.samples
                                .flatMap { it.values.keys }
                                .filter { it.startsWith("cpu_freq_khz_") }
                                .distinct()

                            val sortedCoreKeys = coreKeys.sortedBy { it.removePrefix("cpu_freq_khz_").toIntOrNull() ?: Int.MAX_VALUE }
                            val palette = listOf(
                                Color(0xFF10AEFF),
                                Color(0xFF07C160),
                                Color(0xFFFA9D3B),
                                Color(0xFFFA5151),
                                Color(0xFF8E59FF),
                                Color(0xFF00B7C2),
                                Color(0xFFE91E63),
                                Color(0xFF795548),
                                Color(0xFF009688),
                                Color(0xFFFF5722),
                                Color(0xFF3F51B5),
                                Color(0xFFCDDC39),
                            )

                            val n = session.samples.size.coerceAtLeast(2)
                            val lines = if (sortedCoreKeys.isNotEmpty()) {
                                sortedCoreKeys.mapIndexed { idx, k ->
                                    val label = "c" + (k.removePrefix("cpu_freq_khz_").toIntOrNull() ?: idx)
                                    val pointsMHz = session.samples.map { (it.values[k] ?: Double.NaN).let { v -> if (v.isFinite()) v / 1000.0 else Double.NaN } }
                                    LineSpec(label, pointsMHz, palette[idx % palette.size])
                                }
                            } else {
                                listOf(LineSpec("c0", List(n) { Double.NaN }, palette[0]))
                            }

                            val all = lines.flatMap { it.points }.filter { it.isFinite() }
                            val avg = all.takeIf { it.isNotEmpty() }?.average()
                            val peak = all.maxOrNull()

                            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(displayName(key), fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = "Avg ${avg?.let { String.format("%.0f", it) } ?: "--"}MHz  Peak ${peak?.let { String.format("%.0f", it) } ?: "--"}MHz",
                                            color = Color(0xFF777777),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    MultiLineChart(lines = lines, xStepMs = session.samplingMs)
                                    Spacer(Modifier.height(6.dp))
                                    // Legend
                                    FlowRow(
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        lines.forEach { spec ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(Modifier.size(10.dp).background(spec.color, RoundedCornerShape(2.dp)))
                                                Spacer(Modifier.width(4.dp))
                                                Text(spec.label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF555555))
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        "gpu_freq_khz" -> {
                            val raw = series(key)
                            val pointsMHz = raw.map { if (it.isFinite()) it / 1000.0 else Double.NaN }
                            val vals = finite(pointsMHz)
                            val avg = vals.takeIf { it.isNotEmpty() }?.average()
                            val peak = vals.maxOrNull()

                            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(displayName(key), fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "Avg ${avg?.let { String.format("%.0f", it) } ?: "--"}MHz  Peak ${peak?.let { String.format("%.0f", it) } ?: "--"}MHz",
                                        color = Color(0xFF777777),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    LineChart(points = pointsMHz, strokeColor = Color(0xFF10AEFF), xStepMs = session.samplingMs)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        else -> {
                            val points = series(key)
                            val vals = finite(points)
                            val avg = vals.takeIf { it.isNotEmpty() }?.average()
                            val peak = vals.maxOrNull()

                            val (unit, fmt) = when (key) {
                                "cpu_total_pct", "cpu_fg_app_pct", "gpu_load_pct", "battery_pct" -> Pair("%", "%.1f")
                                "battery_temp_c" -> Pair("℃", "%.1f")
                                "battery_voltage_v" -> Pair("V", "%.2f")
                                "mem_avail_mb", "mem_total_mb", "app_pss_mb" -> Pair("MB", "%.0f")
                                "net_rx_kbps", "net_tx_kbps" -> Pair("kbps", "%.1f")
                                "fps_app" -> Pair("", "%.1f")
                                else -> Pair("", "%.1f")
                            }

                            val summary = run {
                                if (avg == null && peak == null) return@run "Avg --  Peak --"
                                val a = avg?.let { String.format(fmt, it) } ?: "--"
                                val p = peak?.let { String.format(fmt, it) } ?: "--"
                                "Avg $a$unit  Peak $p$unit"
                            }

                            val color = when (key) {
                                "cpu_total_pct", "gpu_freq_khz", "mem_avail_mb", "net_rx_kbps", "battery_voltage_v" -> Color(0xFF10AEFF)
                                "cpu_fg_app_pct", "gpu_load_pct", "battery_temp_c" -> Color(0xFFFA9D3B)
                                "net_tx_kbps", "battery_pct" -> Color(0xFF07C160)
                                else -> Color(0xFF10AEFF)
                            }

                            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(displayName(key), fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(6.dp))
                                    Text(summary, color = Color(0xFF777777), style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(8.dp))
                                    LineChart(points = points, strokeColor = color, xStepMs = session.samplingMs)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

            }

        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartMonitorScreen(
    selectedApp: TargetApp?,
    onSelectedAppChange: (TargetApp) -> Unit,
    selectedMetricKeys: List<String>,
    onToggleMetric: (String) -> Unit,
    onSelectAllMetrics: () -> Unit,
    isMonitoring: Boolean,
    onToggleMonitoring: (Context) -> Unit,
) {
    val ctx = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.ikun_logo),
                contentDescription = "iKunMonitor",
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.size(10.dp))
            Text("开始监控", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("步骤 1：选择被测 App", fontWeight = FontWeight.SemiBold)
                        Text("支持搜索与滚动", color = Color(0xFF777777))
                    }
                    Button(
                        onClick = { showPicker = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) { Text("选择") }
                }
                Spacer(Modifier.height(10.dp))
                Divider(color = Color(0xFFEDEDED))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("被测 App", color = Color(0xFF666666))
                    Text(
                        text = selectedApp?.let { "${it.label}（${it.packageName}）" } ?: "未选择",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val canStart = selectedApp != null && selectedMetricKeys.isNotEmpty()

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("步骤 2：选择采集指标", fontWeight = FontWeight.SemiBold)
                        Text("勾选需要采集的指标", color = Color(0xFF777777))
                    }
                    Button(
                        enabled = if (isMonitoring) true else canStart,
                        onClick = { onToggleMonitoring(ctx) },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isMonitoring) Color(0xFFFA5151) else MaterialTheme.colorScheme.primary),
                    ) {
                        Text(if (isMonitoring) "停止监控" else "开始监控")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AdbChip()
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "ADB标识的指标是指需要用iKunMonitor Activator 激活后即可采集。",
                        color = Color(0xFF777777),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("指标列表", color = Color(0xFF777777), fontWeight = FontWeight.Medium)
                    TextButton(onClick = onSelectAllMetrics) { Text("全选") }
                }

                Spacer(Modifier.height(6.dp))
                MetricCatalog.forEach { m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleMetric(m.key) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, fontWeight = FontWeight.Medium)
                                if (isDesktopAdbRequiredMetric(m.key)) {
                                    Spacer(Modifier.size(8.dp))
                                    AdbChip()
                                }
                            }
                            Text("字段：${m.key}", color = Color(0xFF888888), style = MaterialTheme.typography.bodySmall)
                        }
                        val checked = selectedMetricKeys.contains(m.key)
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(
                                    if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(3.dp)
                                .background(
                                    if (checked) MaterialTheme.colorScheme.primary else Color(0xFFDDDDDD),
                                    RoundedCornerShape(99.dp),
                                ),
                        )
                    }
                    Divider(color = Color(0xFFF1F1F1))
                }
            }
        }


    }

    if (showPicker) {
        AppPickerBottomSheet(
            onDismiss = { showPicker = false },
            onPicked = {
                onSelectedAppChange(it)
                showPicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerBottomSheet(
    onDismiss: () -> Unit,
    onPicked: (TargetApp) -> Unit,
) {
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val lifecycleOwner = remember(ctx) { ctx as? androidx.lifecycle.LifecycleOwner }
    var refreshNonce by remember { mutableStateOf(0) }

    // 授权（如使用情况访问）后返回时，自动刷新一次；同时也支持手动刷新。
    DisposableEffect(lifecycleOwner) {
        val owner = lifecycleOwner
        if (owner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshNonce++
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    // 从手机已安装应用中取“第三方 App”（Android 11+ 需要包可见性权限）。
    val allApps = remember(refreshNonce) { loadThirdPartyApps(ctx) }

    val filtered = remember(query, allApps) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) allApps
        else allApps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("选择被测 App", fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { refreshNonce++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索应用名称或包名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))

            if (allApps.isEmpty() && query.trim().isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F7F9))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("应用列表未授权", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "当前无法读取已安装应用列表。请到系统设置中允许 iKunMonitor 访问应用列表（部分系统称：应用列表访问/应用可见性）。",
                            color = Color(0xFF666666),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = { refreshNonce++ }) { Text("刷新") }
                            TextButton(
                                onClick = {
                                    val i = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${ctx.packageName}"),
                                    )
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ctx.startActivity(i)
                                },
                            ) { Text("去设置") }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
            ) {
                items(filtered) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPicked(app) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.Medium)
                            Text(app.packageName, color = Color(0xFF888888), style = MaterialTheme.typography.bodySmall)
                        }
                        Text("选择", color = MaterialTheme.colorScheme.secondary)
                    }
                    Divider(color = Color(0xFFF2F2F2))
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "说明：默认仅展示已安装的第三方应用（可启动的应用）。",
                color = Color(0xFF777777),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsScreen(
    retainLimit: Int,
    onRetainLimitChange: (Int) -> Unit,
    overlayGranted: Boolean,
    onOpenOverlaySettings: (Context) -> Unit,
) {
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.ikun_logo),
                contentDescription = "iKunMonitor",
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.size(10.dp))
            Text("设置", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(12.dp)) {
                Text("CSV 导出", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("导出时系统会弹出文件选择器，可自行选择保存位置。", color = Color(0xFF777777))
                Spacer(Modifier.height(4.dp))
                Text("默认导出目录：Downloads（下载）", color = Color(0xFF555555))
                Spacer(Modifier.height(4.dp))
                Text("文件名格式：ikun_<应用名>_<时间>.csv", color = Color(0xFF777777), style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(12.dp)) {
                Text("清理策略", fontWeight = FontWeight.SemiBold)
                Text("默认保留最近 20 条录制记录", color = Color(0xFF777777))
                Spacer(Modifier.height(10.dp))

                val options = listOf(10, 20, 50, 100)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    options.forEach { n ->
                        val active = retainLimit == n
                        Box(
                            modifier = Modifier
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color(0xFFF1F2F3),
                                    RoundedCornerShape(999.dp),
                                )
                                .clickable { onRetainLimitChange(n) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "${n}条",
                                color = if (active) MaterialTheme.colorScheme.primary else Color(0xFF666666),
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(12.dp)) {
                Text("权限设置", fontWeight = FontWeight.SemiBold)
                Text("以下为已授权的权限列表", color = Color(0xFF777777))
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenOverlaySettings(ctx) }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("悬浮窗权限", color = Color(0xFF666666))
                    Text(
                        if (overlayGranted) "已开启" else "未开启（去开启）",
                        color = if (overlayGranted) Color(0xFF07C160) else Color(0xFF10AEFF),
                        fontWeight = FontWeight.Medium,
                    )
                }

                val lifecycleOwner = remember(ctx) { ctx as? androidx.lifecycle.LifecycleOwner }
                var canListApps by remember { mutableStateOf(loadThirdPartyApps(ctx).isNotEmpty()) }

                DisposableEffect(lifecycleOwner) {
                    val owner = lifecycleOwner
                    if (owner == null) {
                        onDispose { }
                    } else {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                canListApps = loadThirdPartyApps(ctx).isNotEmpty()
                            }
                        }
                        owner.lifecycle.addObserver(observer)
                        onDispose { owner.lifecycle.removeObserver(observer) }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val i = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${ctx.packageName}"),
                            )
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(i)
                        }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("读取应用列表", color = Color(0xFF666666))
                    Text(
                        if (canListApps) "已授权" else "未授权（去设置）",
                        color = if (canListApps) Color(0xFF07C160) else Color(0xFF10AEFF),
                        fontWeight = FontWeight.Medium,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("ADB 指标", color = Color(0xFF666666))
                    Text(
                        "需电脑激活",
                        color = Color(0xFF10AEFF),
                        fontWeight = FontWeight.Medium,
                    )
                }

                Text(
                    "ADB指标需要电脑用iKunMonitor Activator一键激活。",
                    color = Color(0xFF777777),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

    }
}

private fun hasOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

private fun openOverlayPermissionSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun loadThirdPartyApps(context: Context): List<TargetApp> {
    return try {
        val pm = context.packageManager
        val myPkg = context.packageName

        val apps = pm.getInstalledApplications(PackageManager.MATCH_ALL)

        apps
            .asSequence()
            .filter { it.packageName != myPkg }
            // third-party only: exclude system & preloaded apps
            .filter { ai ->
                val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                !isSystem && !isUpdatedSystem
            }
            // only those with a launcher intent (so we can start it after monitoring begins)
            .filter { ai -> pm.getLaunchIntentForPackage(ai.packageName) != null }
            .map { ai ->
                val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName)
                TargetApp(label = label.takeIf { it.isNotBlank() } ?: ai.packageName, packageName = ai.packageName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
            .toList()
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun launchTargetApp(context: Context, packageName: String) {
    val pm = context.packageManager
    val launch = pm.getLaunchIntentForPackage(packageName)
    if (launch == null) {
        Toast.makeText(context, "无法启动：$packageName", Toast.LENGTH_SHORT).show()
        return
    }
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    context.startActivity(launch)
}

private fun buildSessionCsvFileName(session: RecordingSession): String {
    val safeName = (session.targetAppLabel ?: session.name)
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifBlank { session.id.take(8) }
    val dt = Instant.ofEpochMilli(session.startEpochMs).atZone(ZoneId.systemDefault())
    val ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(dt)
    return "ikun_${safeName}_$ts.csv"
}

private fun exportSessionCsvAsync(
    appContext: Context,
    uri: Uri,
    session: RecordingSession,
    onDone: (ok: Boolean, err: String?) -> Unit,
) {
    val main = Handler(Looper.getMainLooper())
    Thread {
        try {
            writeSessionCsvToUri(appContext, uri, session)
            main.post { onDone(true, null) }
        } catch (t: Throwable) {
            main.post { onDone(false, t.message) }
        }
    }.start()
}

private fun writeSessionCsvToUri(appContext: Context, uri: Uri, session: RecordingSession) {
    val cr = appContext.contentResolver
    cr.openOutputStream(uri, "w").use { os ->
        requireNotNull(os) { "openOutputStream returned null" }
        BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { w ->
            val metricColumns = resolveExportMetricColumns(session)

            val deviceManufacturer = Build.MANUFACTURER ?: ""
            val deviceModel = Build.MODEL ?: ""
            val osRelease = Build.VERSION.RELEASE ?: ""
            val sdkInt = Build.VERSION.SDK_INT
            val appVersion = getAppVersionString(appContext)

            val startMs = session.startEpochMs
            val endMs = session.endEpochMs
            val durationMs = (endMs - startMs).coerceAtLeast(0L)
            val secondsCount = (durationMs / 1000L).toInt() + 1
            val totalSamples = session.samples.size

            val fmtLocal = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val zone = ZoneId.systemDefault()
            val startLocal = fmtLocal.format(Instant.ofEpochMilli(startMs).atZone(zone))
            val endLocal = fmtLocal.format(Instant.ofEpochMilli(endMs).atZone(zone))

            val durationSec = durationMs / 1000L
            val durationStr = if (durationSec >= 3600) {
                String.format(Locale.US, "%d:%02d:%02d", durationSec / 3600, (durationSec % 3600) / 60, durationSec % 60)
            } else {
                String.format(Locale.US, "%d:%02d", durationSec / 60, durationSec % 60)
            }

            val selectedMetricKeysStr = if (session.metricKeys.isNotEmpty()) session.metricKeys.joinToString("; ") else ""

            // ── Meta info rows above the data table ──
            w.write("测试应用,${csvEscape(session.targetAppLabel ?: "")}"); w.newLine()
            w.write("包名,${csvEscape(session.targetAppPackage ?: "")}"); w.newLine()
            w.write("设备,${csvEscape("$deviceManufacturer $deviceModel")}"); w.newLine()
            w.write("系统,${csvEscape("Android $osRelease (API $sdkInt)")}"); w.newLine()
            w.write("测试时间,${csvEscape("$startLocal ~ $endLocal")}"); w.newLine()
            w.write("时长,$durationStr"); w.newLine()
            w.write("采样间隔,${session.samplingMs}ms"); w.newLine()
            w.write("样本数,$totalSamples"); w.newLine()
            w.write("指标,${csvEscape(selectedMetricKeysStr)}"); w.newLine()
            w.write("会话名称,${csvEscape(session.name)}"); w.newLine()
            w.write("iKunMonitor,$appVersion"); w.newLine()
            w.newLine() // blank line separator

            // ── Data table header ──
            val header = buildList {
                add("sec")
                add("timestamp")
                addAll(metricColumns)
            }
            w.write(header.joinToString(",") { csvEscape(it) })
            w.newLine()

            // Group samples by second
            val buckets: Map<Int, List<com.ikun.monitor.data.MetricSample>> = session.samples
                .groupBy { smp -> ((smp.timestampEpochMs - startMs).coerceAtLeast(0L) / 1000L).toInt() }

            for (sec in 0 until secondsCount) {
                val rowTs = startMs + sec * 1000L
                val localTs = fmtLocal.format(Instant.ofEpochMilli(rowTs).atZone(zone))
                val samples = buckets[sec].orEmpty()

                fun avgOf(key: String): String {
                    val vals = samples.mapNotNull { it.values[key] }.filter { it.isFinite() }
                    if (vals.isEmpty()) return ""
                    return formatDoubleForCsv(vals.average())
                }

                val row = buildList {
                    add(sec.toString())
                    add(localTs)
                    metricColumns.forEach { k ->
                        add(avgOf(k))
                    }
                }

                w.write(row.joinToString(",") { csvEscape(it) })
                w.newLine()
            }

            w.flush()
        }
    }
}

private fun resolveExportMetricColumns(session: RecordingSession): List<String> {
    val fromSession = if (session.metricKeys.isNotEmpty()) session.metricKeys.toSet() else emptySet()
    val fromSamples = session.samples.flatMap { it.values.keys }.toSet()

    val base = (fromSession + fromSamples).toMutableSet()

    // Expand cpu_freq_khz_* wildcard into actual core keys if present
    val wantCpuFreqWildcard = base.contains("cpu_freq_khz_*")
    base.remove("cpu_freq_khz_*")

    val cpuCoreKeys = if (wantCpuFreqWildcard) {
        fromSamples.filter { it.startsWith("cpu_freq_khz_") }.sortedBy { it.removePrefix("cpu_freq_khz_").toIntOrNull() ?: Int.MAX_VALUE }
    } else {
        emptyList()
    }

    // Keep a stable-ish order: known keys first, then the rest, then cpu core keys
    val preferred = listOf(
        "cpu_total_pct",
        "cpu_fg_app_pct",
        "mem_avail_mb",
        "mem_total_mb",
        "app_pss_mb",
        "fps_app",
        "gpu_freq_khz",
        "gpu_load_pct",
        "net_rx_kbps",
        "net_tx_kbps",
        "battery_temp_c",
        "battery_pct",
        "battery_voltage_v",
    )

    val out = ArrayList<String>()
    preferred.forEach { k ->
        if (base.remove(k)) out.add(k)
    }

    out.addAll(base.sorted())
    out.addAll(cpuCoreKeys)

    return out.distinct()
}

private fun getAppVersionString(context: Context): String {
    return try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        val name = info.versionName ?: ""
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        if (name.isBlank()) code.toString() else "$name($code)"
    } catch (_: Throwable) {
        ""
    }
}

private fun csvEscape(raw: String): String {
    val s = raw
    val needQuote = s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')
    if (!needQuote) return s
    return "\"" + s.replace("\"", "\"\"") + "\""
}

private fun formatDoubleForCsv(v: Double): String {
    if (!v.isFinite()) return ""
    val s = String.format(Locale.US, "%.6f", v)
    return s.trimEnd('0').trimEnd('.').ifBlank { "0" }
}
