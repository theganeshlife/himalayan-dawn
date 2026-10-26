package app.himalayandawn.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import org.json.JSONArray
import java.util.concurrent.Executors

data class Scene(val id: String, val title: String, val author: String, val license: String, val source: String)

/**
 * Full-screen photo of Nepal that slowly drifts and cross-fades to the next one.
 * Animation is driven by its own clock, so it stays gentle even with the TV's
 * system animations switched off.
 */
class SceneView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var intervalMs: Long = 60_000L
    var onSceneChanged: ((Scene) -> Unit)? = null

    private val scenes: List<Scene> = loadScenes(context)
    private var order: MutableList<Int> = scenes.indices.shuffled().toMutableList()
    private var position = 0

    private var current: Bitmap? = null
    private var next: Bitmap? = null
    private var shownAt = 0L
    private var fadeStart = 0L
    private var running = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val matrix = Matrix()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    val currentScene: Scene? get() = scenes.getOrNull(order.getOrNull(position) ?: -1)

    fun start() {
        if (running || scenes.isEmpty()) return
        running = true
        if (current == null) {
            decode(order[position]) { bmp ->
                current = bmp
                shownAt = SystemClock.uptimeMillis()
                currentScene?.let { onSceneChanged?.invoke(it) }
                scheduleNext()
                invalidate()
            }
        } else {
            scheduleNext()
            invalidate()
        }
    }

    /**
     * Stops drawing. The photo on screen is kept (about 8 MB) so coming back Home shows it at once
     * instead of a blank background; a half-finished cross-fade is dropped.
     */
    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        if (next != null) {
            current = next
            shownAt = SystemClock.uptimeMillis()
            next = null
        }
        fadeStart = 0
    }

    fun show(id: String) {
        val index = scenes.indexOfFirst { it.id == id }
        if (index < 0 || index == order[position]) return
        val showing = order[position]
        order.remove(index)
        position = order.indexOf(showing)
        order.add(position + 1, index)
        advance()
    }

    private fun scheduleNext() {
        main.removeCallbacks(advanceRunnable)
        main.postDelayed(advanceRunnable, intervalMs)
    }

    private val advanceRunnable = Runnable { advance() }

    private fun advance() {
        if (!running || scenes.size < 2) return
        val nextPos = (position + 1) % order.size
        if (nextPos == 0) {
            // New round: reshuffle, without repeating the photo on screen.
            val showing = order[position]
            order.shuffle()
            if (order[0] == showing) java.util.Collections.swap(order, 0, 1)
            position = order.indexOf(showing)
        }
        decode(order[nextPos]) { bmp ->
            next = bmp
            position = nextPos
            fadeStart = SystemClock.uptimeMillis()
            currentScene?.let { onSceneChanged?.invoke(it) }
            invalidate()
        }
        scheduleNext()
    }

    private fun decode(index: Int, done: (Bitmap) -> Unit) {
        val id = scenes[index].id
        io.execute {
            val bmp = context.assets.open("scenes/$id.jpg").use { BitmapFactory.decodeStream(it) }
            main.post { if (running && bmp != null) done(bmp) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val cur = current ?: return
        drawPhoto(canvas, cur, now - shownAt, 255)
        val nxt = next
        if (nxt != null && fadeStart > 0) {
            val t = ((now - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
            val eased = t * t * (3 - 2 * t)
            drawPhoto(canvas, nxt, now - fadeStart, (eased * 255).toInt())
            if (t >= 1f) {
                current = nxt
                shownAt = fadeStart
                next = null
                fadeStart = 0
            }
        }
        if (!running) return
        if (fadeStart > 0) postInvalidateOnAnimation() else postInvalidateDelayed(DRIFT_FRAME_MS)
    }

    /** Centre-crop with a slow push-in over the scene's lifetime. */
    private fun drawPhoto(canvas: Canvas, bmp: Bitmap, ageMs: Long, alpha: Int) {
        val life = (intervalMs + FADE_MS).toFloat()
        val p = (ageMs / life).coerceIn(0f, 1f)
        val base = maxOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val s = base * (1f + 0.05f * p)
        matrix.reset()
        matrix.setScale(s, s)
        matrix.postTranslate((width - bmp.width * s) / 2f - width * 0.01f * p, (height - bmp.height * s) / 2f)
        paint.alpha = alpha
        canvas.drawBitmap(bmp, matrix, paint)
    }

    companion object {
        private const val FADE_MS = 4000L
        private const val DRIFT_FRAME_MS = 66L

        fun loadScenes(context: Context): List<Scene> {
            val text = context.assets.open("scenes/scenes.json").bufferedReader().use { it.readText() }.trimStart('﻿')
            val arr = JSONArray(text)
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Scene(o.getString("id"), o.getString("title"), o.optString("author"), o.optString("license"), o.optString("source"))
            }
        }
    }
}
