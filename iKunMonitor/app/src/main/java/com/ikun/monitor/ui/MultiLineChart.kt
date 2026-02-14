package com.ikun.monitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

data class LineSpec(
    val label: String,
    val points: List<Double>,
    val color: Color,
)

@Composable
fun MultiLineChart(
    lines: List<LineSpec>,
    modifier: Modifier = Modifier,
    xStepMs: Int = 1000,
) {
    if (lines.isEmpty()) {
        Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) { /* empty */ }
        return
    }

    val cleaned = lines.map { spec ->
        spec.copy(points = spec.points.map { if (it.isFinite()) it else Double.NaN })
    }

    val all = cleaned.flatMap { it.points }.filter { it.isFinite() }

    var min = all.minOrNull() ?: 0.0
    var max = all.maxOrNull() ?: 1.0
    var span = (max - min)

    // Even if there's no/too few data, still draw axes (use default range).
    if (span <= 1e-9) {
        val pad = maxOf(1.0, kotlin.math.abs(min) * 0.05)
        min -= pad
        max += pad
        span = (max - min).coerceAtLeast(1e-9)
    }

    // use max length among series
    val n = cleaned.maxOfOrNull { it.points.size } ?: 0
    if (n < 2) {
        Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) { /* empty */ }
        return
    }

    var selectedIndex by remember(cleaned) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
            .pointerInput(cleaned, xStepMs) {
                detectTapGestures { pos ->
                    val w = size.width
                    val left = 54f
                    val right = 10f
                    val cw = (w - left - right).coerceAtLeast(1f)
                    val t = ((pos.x - left) / cw).coerceIn(0f, 1f)
                    selectedIndex = (t * (n - 1)).roundToInt().coerceIn(0, n - 1)
                }
            }
            .pointerInput(cleaned, xStepMs) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val w = size.width
                        val left = 54f
                        val right = 10f
                        val cw = (w - left - right).coerceAtLeast(1f)
                        val t = ((pos.x - left) / cw).coerceIn(0f, 1f)
                        selectedIndex = (t * (n - 1)).roundToInt().coerceIn(0, n - 1)
                    },
                    onDrag = { change, _ ->
                        val w = size.width
                        val left = 54f
                        val right = 10f
                        val cw = (w - left - right).coerceAtLeast(1f)
                        val t = ((change.position.x - left) / cw).coerceIn(0f, 1f)
                        selectedIndex = (t * (n - 1)).roundToInt().coerceIn(0, n - 1)
                        change.consume()
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height

        val left = 54f
        val top = 10f
        val right = 10f
        val bottom = 34f

        val cw = (w - left - right).coerceAtLeast(1f)
        val ch = (h - top - bottom).coerceAtLeast(1f)

        val gridColor = Color(0xFFE0E0E0)
        val axisColor = Color(0xFFBDBDBD)
        val hintColor = Color(0xFF999999)

        val labelColor = android.graphics.Color.argb(180, 80, 80, 80)
        val yPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = labelColor
            textSize = 24f
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        val xPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = labelColor
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
        }

        val decimals = when {
            span >= 20 -> 0
            span >= 2 -> 1
            else -> 2
        }

        fun formatTimeLabel(idx: Int): String {
            val ms = idx.toLong() * xStepMs.toLong().coerceAtLeast(1L)
            val sec = ms.toDouble() / 1000.0
            return if (kotlin.math.abs(sec - sec.roundToInt().toDouble()) < 1e-9) {
                "${sec.roundToInt()}s"
            } else {
                String.format("%.1fs", sec)
            }
        }

        val xAxisY = top + ch
        drawLine(axisColor, Offset(left, top), Offset(left, xAxisY), strokeWidth = 1f)
        drawLine(axisColor, Offset(left, xAxisY), Offset(left + cw, xAxisY), strokeWidth = 1f)

        // Y grid + labels
        for (i in 0..3) {
            val frac = i / 3f
            val y = top + ch * frac
            drawLine(gridColor, Offset(left, y), Offset(left + cw, y), strokeWidth = 1f)

            val v = (max - span * frac)
            val label = String.format("%.${decimals}f", v)
            drawContext.canvas.nativeCanvas.drawText(label, left - 6f, y + 8f, yPaint)
        }

        // X ticks + labels
        for (i in 0..3) {
            val frac = i / 3f
            val idx = (frac * (n - 1)).roundToInt().coerceIn(0, n - 1)
            val x = left + cw * (idx / (n - 1f))
            drawLine(axisColor, Offset(x, xAxisY), Offset(x, xAxisY + 6f), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(formatTimeLabel(idx), x, xAxisY + 26f, xPaint)
        }

        // lines
        cleaned.forEach { spec ->
            val path = Path()
            var hasStart = false

            for (idx in 0 until n) {
                val v = spec.points.getOrNull(idx) ?: Double.NaN
                if (!v.isFinite()) {
                    hasStart = false
                    continue
                }

                val x = left + cw * (idx / (n - 1f))
                val yn = ((v - min) / span).toFloat().coerceIn(0f, 1f)
                val y = top + ch * (1f - yn)

                if (!hasStart) {
                    path.moveTo(x, y)
                    hasStart = true
                } else {
                    path.lineTo(x, y)
                }
            }

            if (hasStart) {
                drawPath(
                    path = path,
                    color = spec.color,
                    style = Stroke(width = 4f, cap = StrokeCap.Round),
                )
            }
        }

        // selection tooltip
        val sel = selectedIndex
        if (sel != null && sel in 0 until n) {
            val sx = left + cw * (sel / (n - 1f))
            drawLine(hintColor, Offset(sx, top), Offset(sx, xAxisY), strokeWidth = 2f)

            // circles on each line
            cleaned.forEach { spec ->
                val v = spec.points.getOrNull(sel) ?: Double.NaN
                if (v.isFinite()) {
                    val yn = ((v - min) / span).toFloat().coerceIn(0f, 1f)
                    val sy = top + ch * (1f - yn)
                    drawCircle(color = spec.color, radius = 7f, center = Offset(sx, sy))
                }
            }

            val time = formatTimeLabel(sel)
            val rows = ArrayList<String>(cleaned.size + 1)
            rows.add("t=$time")
            cleaned.forEach { spec ->
                val v = spec.points.getOrNull(sel) ?: Double.NaN
                val vv = if (v.isFinite()) String.format("%.${decimals}f", v) else "--"
                rows.add("${spec.label}: $vv")
            }

            val tipPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(220, 20, 20, 20)
            }
            val tipTextPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(235, 255, 255, 255)
                textSize = 26f
                textAlign = android.graphics.Paint.Align.LEFT
            }

            val padX = 12f
            val padY = 10f
            val lineH = 34f
            val tw = rows.maxOf { tipTextPaint.measureText(it) }
            val th = rows.size * lineH

            var bx = (sx + 10f).coerceAtMost(w - tw - padX * 2 - 6f)
            bx = bx.coerceAtLeast(6f)
            val by = top + 6f

            val rect = Rect(bx, by, bx + tw + padX * 2, by + th + padY * 2)
            drawContext.canvas.nativeCanvas.drawRoundRect(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                14f,
                14f,
                tipPaint,
            )

            rows.forEachIndexed { i, s ->
                val y = rect.top + padY + lineH * (i + 1)
                drawContext.canvas.nativeCanvas.drawText(s, rect.left + padX, y, tipTextPaint)
            }
        }
    }
}
