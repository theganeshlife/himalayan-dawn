package app.lakuri.tv

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

/** A background photo: one of the built-in scenes, or (with [remotePath]) a photo from the family's shared network folder. */
data class Scene(val id: String, val title: String, val author: String, val license: String, val source: String, val remotePath: String? = null)

/**
 * Full-screen photo that slowly drifts and cross-fades to the next one: the built-in photos of Nepal,
 * or the family's shared network folder when one is chosen in settings (see [NetworkPhotos]).
 * Animation is driven by its own clock, so it stays gentle even with the TV's
 * system animations switched off.
 */
class SceneView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var intervalMs: Long = 60_000L
    var onSceneChanged: ((Scene) -> Unit)? = null

    private val prefs = Prefs(context)
    private val builtIn: List<Scene> = loadScenes(context)
    private var scenes: List<Scene> = builtIn
    private var order: MutableList<Int> = scenes.indices.shuffled().toMutableList()
    private var position = 0
    /** [Prefs.photoSourceKey] the current list was read from ("" = built-in); null = not read yet, or the share couldn't be reached. */
    private var loadedSource: String? = null

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
        if (running) return
        running = true
        if (prefs.photoSourceKey != loadedSource) {
            // First start, the network folder was changed in settings, or last time it couldn't be reached.
            reload { showFirst() }
        } else if (current == null) {
            showFirst()
        } else {
            scheduleNext()
            invalidate()
        }
    }

    /** Shows the scene at [position]; cross-fades if a photo is already on screen. Skips photos that can't be read. */
    private fun showFirst(attempt: Int = 0) {
        decode(order[position], onFail = {
            if (attempt < 2 && scenes.size > 1) {
                position = (position + 1) % order.size
                showFirst(attempt + 1)
            } else if (scenes !== builtIn) {
                // The share is probably offline: use the built-in photos and try the folder again later.
                useScenes(builtIn)
                loadedSource = null
                showFirst()
            }
        }) { bmp ->
            if (current == null) {
                current = bmp
                shownAt = SystemClock.uptimeMillis()
            } else {
                next = bmp
                fadeStart = SystemClock.uptimeMillis()
            }
            currentScene?.let { onSceneChanged?.invoke(it) }
            scheduleNext()
            invalidate()
        }
    }

    /** Lists the network folder off the main thread, then runs [then]. */
    private fun reload(then: () -> Unit) {
        val folder = prefs.photoSourceKey
        io.execute {
            val found = if (folder.isEmpty()) emptyList() else NetworkPhotos.list(context)
            main.post {
                if (!running) return@post
                useScenes(found.ifEmpty { builtIn })
                loadedSource = if (folder.isNotEmpty() && found.isEmpty()) null else folder
                then()
            }
        }
    }

    /** Switches to a new list of scenes, keeping the photo on screen first in the new order if it's still there. */
    private fun useScenes(list: List<Scene>) {
        if (list == scenes) return
        val showingId = currentScene?.id
        scenes = list
        order = scenes.indices.shuffled().toMutableList()
        val keep = scenes.indexOfFirst { it.id == showingId }
        if (keep >= 0) java.util.Collections.swap(order, 0, order.indexOf(keep))
        position = 0
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
        if (!running) return
        if ((position + 1) % order.size == 0 && prefs.photoSourceKey.isNotEmpty()) {
            // End of a round: pick up photos added to or removed from the folder.
            scheduleNext()
            reload { step() }
        } else {
            step()
        }
    }

    private fun step() {
        if (!running || scenes.size < 2) {
            scheduleNext()
            return
        }
        val nextPos = (position + 1) % order.size
        if (nextPos == 0) {
            // New round: reshuffle, without repeating the photo on screen.
            val showing = order[position]
            order.shuffle()
            if (order[0] == showing) java.util.Collections.swap(order, 0, 1)
            position = order.indexOf(showing)
        }
        // A photo that can't be read (deleted, or the share dropped) is skipped rather than retried.
        decode(order[nextPos], onFail = { position = nextPos }) { bmp ->
            next = bmp
            position = nextPos
            fadeStart = SystemClock.uptimeMillis()
            currentScene?.let { onSceneChanged?.invoke(it) }
            invalidate()
        }
        scheduleNext()
    }

    private fun decode(index: Int, onFail: () -> Unit = {}, done: (Bitmap) -> Unit) {
        val scene = scenes[index]
        val dm = resources.displayMetrics
        val w = if (width > 0) width else dm.widthPixels
        val h = if (height > 0) height else dm.heightPixels
        io.execute {
            val bmp = scene.remotePath?.let { NetworkPhotos.decode(context, it, w, h) }
                ?: if (scene.remotePath == null) context.assets.open("scenes/${scene.id}.jpg").use { BitmapFactory.decodeStream(it) } else null
            main.post {
                if (!running) return@post
                if (bmp != null) done(bmp) else onFail()
            }
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
