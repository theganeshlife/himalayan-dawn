package app.himalayandawn.tv

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.text.format.Formatter

/** Start-up tidy-up and storage checks. Only this launcher's own cache is ever cleared. */
object Housekeeping {
    private const val LOW_BYTES = 1_000_000_000L // 1 GB
    private const val LOW_FRACTION = 0.08

    /** Runs once per boot: closes leftover background apps and clears this launcher's cache. */
    fun onStartup(context: Context) {
        val prefs = Prefs(context)
        val bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (prefs.lastCleanup > bootedAt) return
        prefs.lastCleanup = System.currentTimeMillis()
        closeBackgroundApps(context)
        clearOwnCache(context)
    }

    /** Asks Android to end cached background processes. Apps in use or playing are not affected. */
    fun closeBackgroundApps(context: Context): Int {
        val am = context.getSystemService(ActivityManager::class.java)
        val packages = Apps.all(context).map { it.pkg }.toSet()
        packages.forEach { am.killBackgroundProcesses(it) }
        return packages.size
    }

    fun clearOwnCache(context: Context) {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    data class Storage(val free: Long, val total: Long) {
        val low: Boolean get() = free < LOW_BYTES || free < total * LOW_FRACTION
    }

    fun storage(): Storage {
        val stat = StatFs(Environment.getDataDirectory().path)
        return Storage(stat.availableBytes, stat.totalBytes)
    }

    fun format(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
}
