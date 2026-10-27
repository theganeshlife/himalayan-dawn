package app.himalayandawn.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.himalayandawn.tv.calendar.Nepali

/** Four-digit PIN pad, usable with the on-screen keys or the remote's number buttons. */
class PinActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var title: TextView
    private val dots = ArrayList<View>()
    private val entered = StringBuilder()
    private var firstEntry: String? = null
    private var setting = false
    private var failures = 0
    private var lockedUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setting = intent.getBooleanExtra(EXTRA_SET, false)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.color.night)
        }
        title = TextView(this).apply {
            setTextColor(getColor(R.color.snow))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = resources.getFont(R.font.mukta_regular)
            gravity = Gravity.CENTER
            setText(if (setting) R.string.new_pin else R.string.enter_pin)
        }
        col.addView(title)

        val dotRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        repeat(4) {
            val d = View(this)
            dots += d
            dotRow.addView(d, LinearLayout.LayoutParams(Tiles.dp(this, 14f), Tiles.dp(this, 14f)).apply {
                setMargins(Tiles.dp(this@PinActivity, 9f), Tiles.dp(this@PinActivity, 18f), Tiles.dp(this@PinActivity, 9f), Tiles.dp(this@PinActivity, 18f))
            })
        }
        col.addView(dotRow)

        val pad = GridLayout(this).apply { columnCount = 3 }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        for (k in keys) {
            val b = Button(this, null, 0, R.style.Dawn_Button).apply {
                text = if (k.length == 1 && k[0].isDigit()) Nepali.digits(k) else k
                contentDescription = when (k) { "⌫" -> "Delete"; "✓" -> "Done"; else -> k }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setOnClickListener { press(k) }
            }
            pad.addView(b, GridLayout.LayoutParams().apply {
                width = Tiles.dp(this@PinActivity, 76f)
                height = Tiles.dp(this@PinActivity, 46f)
                setMargins(Tiles.dp(this@PinActivity, 5f), Tiles.dp(this@PinActivity, 5f), Tiles.dp(this@PinActivity, 5f), Tiles.dp(this@PinActivity, 5f))
            })
        }
        col.addView(pad)
        setContentView(col)
        pad.getChildAt(4).requestFocus()
        render()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { press((keyCode - KeyEvent.KEYCODE_0).toString()); return true }
        if (keyCode == KeyEvent.KEYCODE_DEL) { press("⌫"); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun press(k: String) {
        if (SystemClock.elapsedRealtime() < lockedUntil) return
        when {
            k == "⌫" -> if (entered.isNotEmpty()) entered.setLength(entered.length - 1)
            k == "✓" -> {}
            entered.length < 4 -> entered.append(k)
        }
        render()
        if (entered.length == 4) dots.last().postDelayed(::complete, 150)
    }

    private fun complete() {
        val pin = entered.toString()
        entered.setLength(0)
        if (setting) {
            val first = firstEntry
            when {
                first == null -> { firstEntry = pin; title.setText(R.string.confirm_pin) }
                first == pin -> { prefs.setPin(pin); setResult(RESULT_OK); finish(); return }
                else -> { firstEntry = null; title.setText(R.string.pin_mismatch) }
            }
        } else if (prefs.checkPin(pin)) {
            setResult(RESULT_OK); finish(); return
        } else {
            failures++
            title.setText(R.string.wrong_pin)
            if (failures >= 5) { lockedUntil = SystemClock.elapsedRealtime() + 30_000; failures = 0 }
        }
        render()
    }

    private fun render() {
        dots.forEachIndexed { i, d ->
            d.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (i < entered.length) setColor(getColor(R.color.saffron))
                else setStroke(Tiles.dp(this@PinActivity, 2f), getColor(R.color.snow_dim))
            }
        }
    }

    companion object {
        private const val EXTRA_SET = "set"
        fun verify(context: Context) = Intent(context, PinActivity::class.java)
        fun set(context: Context) = Intent(context, PinActivity::class.java).putExtra(EXTRA_SET, true)
    }
}
