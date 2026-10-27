package app.himalayandawn.tv

import android.content.Context
import java.security.MessageDigest

/** Launcher settings, stored on the TV only. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dawn", Context.MODE_PRIVATE)

    var pinned: List<String>
        get() = sp.getString("pinned", null)?.split(',')?.filter { it.isNotBlank() } ?: DEFAULT_PINNED
        set(value) = sp.edit().putString("pinned", value.joinToString(",")).apply()

    var kidsApps: Set<String>
        get() = sp.getStringSet("kidsApps", null) ?: DEFAULT_KIDS
        set(value) = sp.edit().putStringSet("kidsApps", value).apply()

    var kidsMode: Boolean
        get() = sp.getBoolean("kidsMode", false)
        set(value) = sp.edit().putBoolean("kidsMode", value).apply()

    var welcomeOnWake: Boolean
        get() = sp.getBoolean("welcomeOnWake", false)
        set(value) = sp.edit().putBoolean("welcomeOnWake", value).apply()

    var sceneIntervalSec: Int
        get() = sp.getInt("sceneIntervalSec", 60)
        set(value) = sp.edit().putInt("sceneIntervalSec", value).apply()

    /** IANA zone for the main clock, e.g. "Europe/London"; empty = the TV's own time zone. */
    var homeZone: String
        get() = sp.getString("homeZone", "") ?: ""
        set(value) = sp.edit().putString("homeZone", value).apply()

    var lastCleanup: Long
        get() = sp.getLong("lastCleanup", 0)
        set(value) = sp.edit().putLong("lastCleanup", value).apply()

    val hasPin: Boolean get() = sp.getString("pinHash", null) != null

    fun setPin(pin: String?) {
        sp.edit().putString("pinHash", pin?.let(::hash)).apply()
    }

    fun checkPin(pin: String): Boolean = sp.getString("pinHash", null) == hash(pin)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("himalayan-dawn:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val BROWSER = "@browser"
        const val TV_BRO = "com.phlox.tvwebbrowser"

        val DEFAULT_PINNED = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.google.android.youtube.tvmusic",
            "com.mxtech.videoplayer.television",
            BROWSER,
        )
        val DEFAULT_KIDS = setOf("com.google.android.youtube.tv")
    }
}
