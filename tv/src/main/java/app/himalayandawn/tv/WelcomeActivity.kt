package app.himalayandawn.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import java.time.LocalTime

/** "Who's watching?" screen. Family needs the PIN (if one is set); Kids switches to Kids mode. */
class WelcomeActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var scene: SceneView
    private lateinit var calendar: DualCalendar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        prefs = Prefs(this)
        scene = findViewById(R.id.scene)
        calendar = DualCalendar(findViewById(android.R.id.content))

        val hour = LocalTime.now(DualCalendar.localZone(this)).hour
        findViewById<TextView>(R.id.greetingSub).text = when {
            hour < 12 -> "Good morning. Who's watching?"
            hour < 17 -> "Good afternoon. Who's watching?"
            else -> "Good evening. Who's watching?"
        }

        findViewById<View>(R.id.family).setOnClickListener {
            if (prefs.hasPin) startActivityForResult(PinActivity.verify(this), REQ_FAMILY) else choose(kids = false)
        }
        findViewById<View>(R.id.kids).setOnClickListener { choose(kids = true) }
        findViewById<View>(if (prefs.kidsMode) R.id.kids else R.id.family).requestFocus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FAMILY && resultCode == RESULT_OK) choose(kids = false)
    }

    private fun choose(kids: Boolean) {
        prefs.kidsMode = kids
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    override fun onStart() {
        super.onStart()
        scene.intervalMs = prefs.sceneIntervalSec * 1000L
        scene.start()
        calendar.start()
    }

    override fun onStop() {
        super.onStop()
        scene.stop()
        calendar.stop()
    }

    // With a PIN set, Back can't be used to skip the choice.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK && prefs.hasPin) true else super.onKeyDown(keyCode, event)

    companion object {
        private const val REQ_FAMILY = 1
    }
}
