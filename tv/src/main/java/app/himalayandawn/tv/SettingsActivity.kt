package app.himalayandawn.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = Tiles.dp(this, 48f)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Tiles.dp(this@SettingsActivity, 32f), pad, pad)
        }
        setContentView(ScrollView(this).apply { setBackgroundResource(R.color.night); addView(list) })
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        // Rebuilding after a change must leave focus on the same row, not jump to the top.
        val focusedIndex = (0 until list.childCount).firstOrNull { list.getChildAt(it).isFocused } ?: 2
        list.removeAllViews()
        heading("Himalayan Dawn")

        section("Welcome screen")
        row("Show \"Who's watching?\" when the TV wakes: ${onOff(prefs.welcomeOnWake)}") {
            prefs.welcomeOnWake = !prefs.welcomeOnWake; build()
        }
        if (prefs.hasPin) {
            row("Change PIN") { requirePin { startActivity(PinActivity.set(this)) } }
            row("Remove PIN") { requirePin { prefs.setPin(null); build() } }
        } else {
            row("Set a PIN (needed for Kids mode to lock)") { startActivity(PinActivity.set(this)) }
        }
        note("Kids mode shows only the apps you allow. In All apps, press and hold OK on an app and choose \"Allow in Kids mode\".")

        section("Clock")
        val zone = prefs.homeZone
        row("Home city for the clock: ${if (zone.isEmpty()) "Automatic (TV's time zone)" else DualCalendar.cityName(java.time.ZoneId.of(zone))}") {
            chooseHomeZone()
        }

        section("Scenes")
        val interval = prefs.sceneIntervalSec
        row("Change scene every: ${intervalLabel(interval)}") {
            prefs.sceneIntervalSec = when (interval) { 60 -> 180; 180 -> 600; else -> 60 }; build()
        }
        row("Use the calendar as the screensaver") { openScreensaverSettings() }

        section("Tidy up")
        val s = Housekeeping.storage()
        note("Free storage: ${Housekeeping.format(this, s.free)} of ${Housekeeping.format(this, s.total)}")
        row("Close background apps now") {
            val n = Housekeeping.closeBackgroundApps(this)
            Housekeeping.clearOwnCache(this)
            Toast.makeText(this, "Asked $n apps to close if idle", Toast.LENGTH_SHORT).show()
        }

        section("More")
        row("Android TV settings") { Apps.openAndroidSettings(this) }
        row("Photo credits and licences") { startActivity(Intent(this, CreditsActivity::class.java)) }
        (list.getChildAt(focusedIndex)?.takeIf { it.isFocusable } ?: list.getChildAt(2))?.requestFocus()
    }

    private fun requirePin(then: () -> Unit) {
        pendingAfterPin = then
        startActivityForResult(PinActivity.verify(this), REQ_PIN)
    }

    private var pendingAfterPin: (() -> Unit)? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PIN && resultCode == RESULT_OK) pendingAfterPin?.invoke()
        pendingAfterPin = null
    }

    private fun openScreensaverSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Open Settings › Device Preferences › Screen saver and choose Himalayan Dawn calendar", Toast.LENGTH_LONG).show()
        }
    }

    /** Cities where many Nepali families live, plus Automatic. */
    private fun chooseHomeZone() {
        val zones = listOf(
            "" to "Automatic (TV's time zone)",
            "Australia/Melbourne" to "Melbourne", "Australia/Sydney" to "Sydney", "Australia/Brisbane" to "Brisbane",
            "Australia/Adelaide" to "Adelaide", "Australia/Perth" to "Perth", "Australia/Darwin" to "Darwin",
            "Pacific/Auckland" to "Auckland", "Asia/Tokyo" to "Tokyo", "Asia/Seoul" to "Seoul",
            "Asia/Hong_Kong" to "Hong Kong", "Asia/Kuala_Lumpur" to "Kuala Lumpur", "Asia/Dubai" to "Dubai",
            "Asia/Qatar" to "Doha", "Asia/Riyadh" to "Riyadh", "Asia/Kolkata" to "India", "Asia/Kathmandu" to "Kathmandu",
            "Europe/London" to "London", "Europe/Lisbon" to "Lisbon", "Europe/Berlin" to "Berlin",
            "America/New_York" to "New York", "America/Chicago" to "Chicago", "America/Denver" to "Denver",
            "America/Los_Angeles" to "Los Angeles", "America/Toronto" to "Toronto", "America/Vancouver" to "Vancouver",
        )
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Home city for the clock")
            .setItems(zones.map { it.second }.toTypedArray()) { _, i -> prefs.homeZone = zones[i].first; build() }
            .show()
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"
    private fun intervalLabel(sec: Int) = if (sec < 120) "1 minute" else "${sec / 60} minutes"

    private fun heading(t: String) = list.addView(text(t, 28f, R.font.mukta_light, R.color.snow, bottom = 12f))
    private fun section(t: String) = list.addView(text(t, 13f, R.font.mukta_medium, R.color.saffron, top = 18f, bottom = 6f))
    private fun note(t: String) = list.addView(text(t, 13f, R.font.mukta_light, R.color.snow_dim, top = 4f, bottom = 4f))

    private fun text(t: String, size: Float, font: Int, color: Int, top: Float = 0f, bottom: Float = 0f) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = resources.getFont(font)
        setTextColor(getColor(color))
        setPadding(0, Tiles.dp(this@SettingsActivity, top), 0, Tiles.dp(this@SettingsActivity, bottom))
    }

    private fun row(label: String, action: () -> Unit) {
        val b = Button(this, null, 0, R.style.Dawn_Button).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setOnClickListener { action() }
        }
        list.addView(b, LinearLayout.LayoutParams(Tiles.dp(this, 520f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Tiles.dp(this@SettingsActivity, 6f)
        })
    }

    companion object {
        private const val REQ_PIN = 7
    }
}
