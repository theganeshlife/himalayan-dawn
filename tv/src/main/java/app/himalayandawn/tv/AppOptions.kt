package app.himalayandawn.tv

import android.app.Activity
import android.app.AlertDialog

/** Press-and-hold menu for an app: pin, reorder, Kids mode, app info. */
object AppOptions {
    fun show(activity: Activity, app: AppEntry, changed: () -> Unit) {
        val prefs = Prefs(activity)
        val pinned = prefs.pinned.toMutableList()
        val isPinned = app.pkg in pinned
        val inKids = app.pkg in prefs.kidsApps

        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (isPinned) {
            actions += activity.getString(R.string.move_left) to { move(prefs, pinned, app.pkg, -1) }
            actions += activity.getString(R.string.move_right) to { move(prefs, pinned, app.pkg, +1) }
            actions += activity.getString(R.string.unpin) to { prefs.pinned = pinned - app.pkg }
        } else {
            actions += activity.getString(R.string.pin_to_home) to { prefs.pinned = pinned + app.pkg }
        }
        actions += if (inKids) {
            activity.getString(R.string.disallow_kids) to { prefs.kidsApps = prefs.kidsApps - app.pkg }
        } else {
            activity.getString(R.string.allow_kids) to { prefs.kidsApps = prefs.kidsApps + app.pkg }
        }
        actions += activity.getString(R.string.app_info) to { Apps.openAppInfo(activity, app.pkg) }

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
                changed()
            }
            .show()
    }

    private fun move(prefs: Prefs, list: MutableList<String>, pkg: String, by: Int) {
        val i = list.indexOf(pkg)
        val j = (i + by).coerceIn(0, list.size - 1)
        if (i == j) return
        list.removeAt(i)
        list.add(j, pkg)
        prefs.pinned = list
    }
}
