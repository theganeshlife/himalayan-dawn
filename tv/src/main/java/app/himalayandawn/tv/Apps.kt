package app.himalayandawn.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

data class AppEntry(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable, val intent: Intent)

/** Launchable apps on the TV, TV (leanback) entries preferred over phone-style ones. */
object Apps {
    /** Set-up, factory and promo entries this TV ships with; still installed, just not shown. */
    private val HIDDEN = setOf(
        "com.tcl.partnercustomizer", // "TCL Channel" promo shortcut
        "com.tcl.initsetup",
        "com.google.android.tungsten.setupwraith",
        "com.tcl.factory.view",
        "com.tcl.copydatatotv",
    )

    fun all(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val seen = HashMap<String, AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == context.packageName || ai.packageName in HIDDEN || seen.containsKey(ai.packageName)) continue
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val banner = if (category == Intent.CATEGORY_LEANBACK_LAUNCHER) {
                    ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                } else null
                seen[ai.packageName] = AppEntry(ai.packageName, ri.loadLabel(pm).toString(), banner, ri.loadIcon(pm), intent)
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    fun isInstalled(context: Context, pkg: String): Boolean =
        try { context.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    fun launch(context: Context, entry: AppEntry) {
        try {
            context.startActivity(entry.intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    /** The Browser tile: TV Bro if installed, otherwise its Play Store page. */
    fun openBrowser(context: Context, apps: List<AppEntry>) {
        apps.firstOrNull { it.pkg == Prefs.TV_BRO }?.let { launch(context, it); return }
        Toast.makeText(context, R.string.browser_missing, Toast.LENGTH_LONG).show()
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Prefs.TV_BRO}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(store) } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    fun openAndroidSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    fun openAppInfo(context: Context, pkg: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }
}
