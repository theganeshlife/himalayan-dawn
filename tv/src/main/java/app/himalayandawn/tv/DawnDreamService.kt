package app.himalayandawn.tv

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.View
import android.widget.TextView
import kotlin.random.Random

/** Screensaver: slow scenes of Nepal with both calendars. The text wanders a little each minute to protect the panel. */
class DawnDreamService : DreamService() {
    private var calendar: DualCalendar? = null
    private var scene: SceneView? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        setContentView(R.layout.dream)
        val root = findViewById<View>(android.R.id.content)
        calendar = DualCalendar(root)
        val caption = findViewById<TextView>(R.id.sceneCaption)
        scene = findViewById<SceneView>(R.id.scene).apply {
            intervalMs = maxOf(Prefs(this@DawnDreamService).sceneIntervalSec, 90) * 1000L
            onSceneChanged = { caption.text = it.title }
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        scene?.start()
        calendar?.start()
        wander()
    }

    private fun wander() {
        findViewById<View>(R.id.dreamContent)?.apply {
            val d = Tiles.dp(context, 24f).toFloat()
            translationX = Random.nextFloat() * 2 * d - d
            translationY = Random.nextFloat() * 2 * d - d
        }
        main.postDelayed(::wander, 60_000L)
    }

    override fun onDreamingStopped() {
        main.removeCallbacksAndMessages(null)
        scene?.stop()
        calendar?.stop()
        super.onDreamingStopped()
    }
}
