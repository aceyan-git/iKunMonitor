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

@Composable
fun LineChart(
    points: List<Double>,
    modifier: Modifier = Modifier,
    strokeColor: Color = Color(0xFF10AEFF),
    xStepMs: Int = 1000,
) {
    val finite = points.filter { it.isFinite() }
    if (points.size < 2) {
        Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) { /* empty */ }
        return
    }

    var min = finite.minOrNull() ?: 0.0
    var max = finite.maxOrNull() ?: 1.0
    var span = (max - min)

    // If value is (almost) constant, add padding so the line doesn't stick to the X-axis.
    if (span <= 1e-9) {
        val pad = maxOf(1.0, kotlin.math.abs(min) * 0.05)
        min -= pad
        max += pad
        span = (max - min).coerceAtLeast(1e-9)
    }

    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(175.dp)
            .pointerInput(points, xStepMs) {
                detectTapGestures { pos ->
                    // map x to nearest sample index
                    val w = size.width
                    val left = 54f
                    val right = 10f
                    val cw = (w - left - right).coerceAtLeast(1f)
                    val n = points.size
                    val t = ((pos.x - left) / cw).coerceIn(0f, 1f)
                    selectedIndex = (t * (n - 1)).roundToInt().coerceIn(0, n - 1)
                }
            }
            .pointerInput(points, xStepMs) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val w = size.width
                        val left = 54f
                        val right = 10f
                        val cw = (w - left - right).coerceAtLeast(1f)
                        val n = points.size
                        val t = ((pos.x - left) / cw).coerceIn(0f, 1f)
                        selectedIndex = (t * (n - 1)).roundToInt().coerceIn(0, n - 1)
                    },
                    onDrag = { change, _ ->
                        val w = size.width
                        val left = 54f
                        val right = 10f
                        val cw = (w - left - right).coerceAtLeast(1f)
                        val n = points.size
                        val t = ((change.position.x - left) / cw).coerceIn(0f, 1f)
                        selectedIndex = (t * (n - 1)).roundToInt().coerceIn(0, n - 1)
                        change.consume()
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height

        // padding (reserve left for Y labels, bottom for X labels)
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
        val textPaint = android.graphics.Paint().apply {
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

        // axes
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
            drawContext.canvas.nativeCanvas.drawText(label, left - 6f, y + 8f, textPaint)
        }

        // X ticks + labels
        val n = points.size
        for (i in 0..3) {
            val frac = i / 3f
            val idx = (frac * (n - 1)).roundToInt().coerceIn(0, n - 1)
            val x = left + cw * (idx / (n - 1f))
            drawLine(axisColor, Offset(x, xAxisY), Offset(x, xAxisY + 6f), strokeWidth = 1f)
            val label = formatTimeLabel(idx)
            drawContext.canvas.nativeCanvas.drawText(label, x, xAxisY + 26f, xPaint)
        }

        // line path (keep index mapping; allow gaps)
        val path = Path()
        var hasStart = false
        for (idx in 0 until n) {
            val v = points[idx]
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

        drawPath(
            path = path,
            color = strokeColor,
            style = Stroke(width = 4f, cap = StrokeCap.Round),
        )

        // interactive selection
        val sel = selectedIndex
        if (sel != null && sel in 0 until n) {
            val sx = left + cw * (sel / (n - 1f))
            drawLine(hintColor, Offset(sx, top), Offset(sx, xAxisY), strokeWidth = 2f)

            val v = points[sel]
            val timeLabel = formatTimeLabel(sel)
            val valueLabel = if (v.isFinite()) String.format("%.${decimals}f", v) else "--"
            val tip = "t=$timeLabel  v=$valueLabel"

            if (v.isFinite()) {
                val yn = ((v - min) / span).toFloat().coerceIn(0f, 1f)
                val sy = top + ch * (1f - yn)
                drawCircle(color = strokeColor, radius = 7f, center = Offset(sx, sy))
                drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = 10f, center = Offset(sx, sy))
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

            val tw = tipTextPaint.measureText(tip)
            val th = 34f
            val padX = 12f
            val padY = 10f

            var bx = (sx + 10f).coerceAtMost(w - tw - padX * 2 - 6f)
            bx = bx.coerceAtLeast(6f)
            val by = (top + 6f)

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
            drawContext.canvas.nativeCanvas.drawText(tip, rect.left + padX, rect.top + padY + th, tipTextPaint)
        }
    }
}
