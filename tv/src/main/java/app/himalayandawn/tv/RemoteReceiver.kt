package app.himalayandawn.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Commands sent from a computer over adb (`am broadcast`). The manifest requires the DUMP
 * permission, which adb's shell has but ordinary apps on the TV can't get.
 */
class RemoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        when (intent.action) {
            ACTION_KIDS_ON -> prefs.kidsMode = true
            ACTION_KIDS_OFF -> prefs.kidsMode = false
            else -> return
        }
        context.startActivity(
            Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    companion object {
        const val ACTION_KIDS_ON = "app.himalayandawn.tv.KIDS_ON"
        const val ACTION_KIDS_OFF = "app.himalayandawn.tv.KIDS_OFF"
    }
}
