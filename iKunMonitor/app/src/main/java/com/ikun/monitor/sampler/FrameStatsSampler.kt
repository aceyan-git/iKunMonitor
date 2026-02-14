package com.ikun.monitor.sampler

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer

/**
 * App 内帧率采样器：通过 Choreographer 统计本进程 UI 线程的帧回调。
 *
 * 注意：这只能反映“iKunMonitor 自身页面/自身进程”的 FPS，无法直接测量其他 App 的 FPS。
 */
class FrameStatsSampler {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var running = false

    private var windowStartElapsedMs: Long = 0L
    private var lastFrameNs: Long = 0L

    private var frameCount: Int = 0
    private var jankCount: Int = 0

    private val frameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return

            if (windowStartElapsedMs == 0L) {
                windowStartElapsedMs = SystemClock.elapsedRealtime()
            }

            if (lastFrameNs != 0L) {
                val dtMs = (frameTimeNanos - lastFrameNs).toDouble() / 1_000_000.0
                // 粗略 jank 判定：大于 2 帧预算（约 34ms）记为一次 jank。
                if (dtMs > 34.0) jankCount++
            }

            lastFrameNs = frameTimeNanos
            frameCount++

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true

        windowStartElapsedMs = SystemClock.elapsedRealtime()
        lastFrameNs = 0L
        frameCount = 0
        jankCount = 0

        if (Looper.myLooper() == Looper.getMainLooper()) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            mainHandler.post { Choreographer.getInstance().postFrameCallback(frameCallback) }
        }
    }

    fun stop() {
        if (!running) return
        running = false

        if (Looper.myLooper() == Looper.getMainLooper()) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        } else {
            mainHandler.post { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        }
    }

    /**
     * 返回上一采样窗口的 (fps, jankPct)，并重置窗口计数。
     */
    fun snapshotAndReset(): Pair<Double?, Double?> {
        if (!running) return Pair(null, null)

        val now = SystemClock.elapsedRealtime()
        val dtMs = (now - windowStartElapsedMs).coerceAtLeast(1L)

        val fps = frameCount.toDouble() * 1000.0 / dtMs.toDouble()
        val jankPct = if (frameCount <= 0) 0.0 else (jankCount.toDouble() / frameCount.toDouble() * 100.0)

        windowStartElapsedMs = now
        frameCount = 0
        jankCount = 0
        lastFrameNs = 0L

        return Pair(fps.coerceAtLeast(0.0), jankPct.coerceIn(0.0, 100.0))
    }
}
