package app.himalayandawn.tv

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Every app on the TV. Press and hold OK on one to pin it to Home or allow it in Kids mode. */
class AllAppsActivity : Activity() {
    private lateinit var grid: GridLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Tiles.dp(this, 48f)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Tiles.dp(this@AllAppsActivity, 32f), pad, pad)
            clipChildren = false
            clipToPadding = false
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.all_apps)
            setTextColor(getColor(R.color.snow))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = resources.getFont(R.font.mukta_light)
        })
        column.addView(TextView(this).apply {
            text = "Press and hold OK on an app to pin it to Home or allow it in Kids mode."
            setTextColor(getColor(R.color.snow_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = resources.getFont(R.font.mukta_light)
            setPadding(0, 0, 0, Tiles.dp(this@AllAppsActivity, 18f))
        })
        grid = GridLayout(this).apply {
            columnCount = 6
            clipChildren = false
            clipToPadding = false
        }
        column.addView(grid)
        setContentView(ScrollView(this).apply {
            setBackgroundResource(R.color.night)
            isFillViewport = true
            clipChildren = false
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        fill()
    }

    private fun fill() {
        grid.removeAllViews()
        val gap = Tiles.dp(this, 10f)
        for (app in Apps.all(this)) {
            val tile = Tiles.make(this, app.label, app.banner, if (app.banner == null) app.icon else null, widthDp = 120f,
                onClick = { Apps.launch(this, app) },
                onLongClick = { AppOptions.show(this, app) {} })
            grid.addView(tile, GridLayout.LayoutParams().apply { setMargins(gap, gap, gap, gap * 2) })
        }
        grid.getChildAt(0)?.let { Tiles.focusTarget(it).requestFocus() }
    }
}
