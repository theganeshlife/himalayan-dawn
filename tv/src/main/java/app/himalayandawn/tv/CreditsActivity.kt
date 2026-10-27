package app.himalayandawn.tv

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView

class CreditsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = buildString {
            appendLine("Photos (Wikimedia Commons)")
            appendLine()
            for (s in SceneView.loadScenes(this@CreditsActivity)) {
                appendLine("${s.title}: ${s.author}, ${s.license}")
                appendLine("  ${s.source}")
            }
            appendLine()
            appendLine("Photos are cropped and resized for the TV; the CC BY-SA ones stay under CC BY-SA 4.0 (or 3.0 as marked).")
            appendLine()
            appendLine("Fonts: Mukta (Girish Dalvi, Ek Type) and Noto Serif Devanagari (The Noto Project Authors), SIL Open Font License 1.1.")
            appendLine("Bikram Sambat month table: nepali-date-converter, © 2020 Subesh Bhandari, MIT License.")
            appendLine("Tithi and festivals are calculated on the TV from sun and moon positions for Kathmandu;")
            appendLine("very rarely a date near a tithi boundary may differ by a day from printed patro.")
        }
        val pad = Tiles.dp(this, 48f)
        setContentView(ScrollView(this).apply {
            setBackgroundResource(R.color.night)
            isFocusable = true
            addView(TextView(this@CreditsActivity).apply {
                text = body
                setTextColor(getColor(R.color.snow))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = resources.getFont(R.font.mukta_light)
                setPadding(pad, pad / 2, pad, pad)
            })
        })
    }
}
