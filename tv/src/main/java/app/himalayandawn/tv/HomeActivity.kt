package app.himalayandawn.tv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class HomeActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var scene: SceneView
    private lateinit var calendar: DualCalendar
    private lateinit var tiles: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var apps: List<AppEntry> = emptyList()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshTiles()
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (prefs.welcomeOnWake) startActivity(Intent(context, WelcomeActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        prefs = Prefs(this)
        scene = findViewById(R.id.scene)
        tiles = findViewById(R.id.tiles)
        calendar = DualCalendar(findViewById(android.R.id.content))
        val caption = findViewById<TextView>(R.id.sceneCaption)
        scene.onSceneChanged = { caption.text = it.title }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))

        getSharedPreferences("dawn", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)

        Thread { Housekeeping.onStartup(applicationContext) }.start()
        if (savedInstanceState == null && prefs.welcomeOnWake) startActivity(Intent(this, WelcomeActivity::class.java))
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        // Home pressed while already home: back to the first tile.
        findViewById<HorizontalScrollView>(R.id.tileScroll).scrollTo(0, 0)
        tiles.getChildAt(0)?.let { Tiles.focusTarget(it).requestFocus() }
    }

    /** `am start -n app.himalayandawn.tv/.HomeActivity --es scene fewa` jumps to a scene. */
    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("scene")?.let { id -> main.postDelayed({ scene.show(id) }, 300) }
    }

    override fun onStart() {
        super.onStart()
        scene.intervalMs = prefs.sceneIntervalSec * 1000L
        scene.start()
        calendar.start()
    }

    override fun onResume() {
        super.onResume()
        refreshTiles()
        checkStorage()
    }

    override fun onStop() {
        super.onStop()
        scene.stop()
        calendar.stop()
        main.removeCallbacksAndMessages(null)
    }

    // Kids mode or pinned apps changed (from adb, the welcome screen or the app menu).
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "kidsMode" || key == "pinned" || key == "kidsApps") main.post { refreshTiles() }
    }

    override fun onDestroy() {
        getSharedPreferences("dawn", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        unregisterReceiver(packageReceiver)
        unregisterReceiver(screenOnReceiver)
        super.onDestroy()
    }

    // Home has nowhere to go back to.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) true else super.onKeyDown(keyCode, event)

    private fun checkStorage() {
        val notice = findViewById<TextView>(R.id.storageNotice)
        val s = Housekeeping.storage()
        if (s.low) {
            notice.text = getString(R.string.storage_low, Housekeeping.format(this, s.free))
            notice.visibility = View.VISIBLE
        } else {
            notice.visibility = View.GONE
        }
        main.removeCallbacks(storageCheck)
        main.postDelayed(storageCheck, 60 * 60_000L)
    }

    private val storageCheck = Runnable { checkStorage() }

    private fun refreshTiles() {
        val hadFocus = currentFocus
        val focusedIndex = (0 until tiles.childCount).firstOrNull { Tiles.focusTarget(tiles.getChildAt(it)) == hadFocus } ?: 0
        apps = Apps.all(this)
        val byPkg = apps.associateBy { it.pkg }
        tiles.removeAllViews()

        val gap = Tiles.dp(this, 14f)
        fun add(tile: View) {
            tiles.addView(tile, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = gap
            })
        }

        if (prefs.kidsMode) {
            prefs.kidsApps.mapNotNull { byPkg[it] }.sortedBy { it.label }.forEach { add(appTile(it)) }
            add(Tiles.make(this, getString(R.string.exit_kids), null, getDrawable(R.drawable.ic_exit), tintIcon = true, onClick = {
                startActivity(Intent(this, WelcomeActivity::class.java))
            }))
        } else {
            for (pkg in prefs.pinned) {
                if (pkg == Prefs.BROWSER) {
                    val bro = byPkg[Prefs.TV_BRO]
                    add(Tiles.make(this, getString(R.string.browser), bro?.banner, bro?.icon ?: getDrawable(R.drawable.ic_globe),
                        tintIcon = bro == null, onClick = { Apps.openBrowser(this, apps) }))
                } else {
                    byPkg[pkg]?.let { add(appTile(it)) }
                }
            }
            add(Tiles.make(this, getString(R.string.all_apps), null, getDrawable(R.drawable.ic_grid), tintIcon = true, onClick = {
                startActivity(Intent(this, AllAppsActivity::class.java))
            }))
            add(Tiles.make(this, getString(R.string.settings), null, getDrawable(R.drawable.ic_gear), tintIcon = true, onClick = {
                Apps.openAndroidSettings(this)
            }))
            add(Tiles.make(this, getString(R.string.launcher_settings), null, getDrawable(R.drawable.ic_sliders), tintIcon = true, onClick = {
                startActivity(Intent(this, SettingsActivity::class.java))
            }))
        }
        val target = tiles.getChildAt(focusedIndex.coerceAtMost(tiles.childCount - 1))
        target?.let { Tiles.focusTarget(it).requestFocus() }
    }

    private fun appTile(app: AppEntry): View =
        Tiles.make(this, app.label, app.banner, if (app.banner == null) app.icon else null, onClick = { Apps.launch(this, app) },
            onLongClick = if (prefs.kidsMode) null else ({ AppOptions.show(this, app) { refreshTiles() } }))
}
