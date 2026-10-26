package app.himalayandawn.tv

import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.himalayandawn.tv.calendar.BsCalendar
import app.himalayandawn.tv.calendar.Festival
import app.himalayandawn.tv.calendar.NEPAL_ZONE
import app.himalayandawn.tv.calendar.Nepali
import app.himalayandawn.tv.calendar.Panchang
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Fills in the clock, AD date, Bikram Sambat date, tithi, Nepal time and festivals
 * in any layout that uses these view ids, and keeps them current each minute.
 * Tithi and festivals follow Nepal time; the clock and AD date follow the TV's own time zone.
 */
class DualCalendar(private val root: View, private val maxFestivals: Int = 3) {
    private val main = Handler(Looper.getMainLooper())
    private var nepalDay: LocalDate? = null
    private var festivals: List<Festival> = emptyList()
    private var lunarText = ""

    private fun text(id: Int): TextView? = root.findViewById(id)

    fun start() {
        update()
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
    }

    private fun update() {
        val zone = localZone(root.context)
        val now = ZonedDateTime.now(zone)
        val nepal = now.withZoneSameInstant(NEPAL_ZONE)

        text(R.id.clockTime)?.text = now.format(DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH))
        text(R.id.clockAmPm)?.text = now.format(DateTimeFormatter.ofPattern("a", Locale.ENGLISH)).lowercase()
        text(R.id.adDate)?.text = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
        text(R.id.localLabel)?.text = "${cityName(zone)} time"

        val (nTime, nAmPm) = Nepali.time(nepal.toLocalTime())
        text(R.id.nepalTime)?.text = nTime
        text(R.id.nepalAmPm)?.text = nAmPm

        val day = nepal.toLocalDate()
        if (day != nepalDay) loadDay(day)

        main.postDelayed(::update, 60_000L - (System.currentTimeMillis() % 60_000L) + 50)
    }

    private fun loadDay(day: LocalDate) {
        nepalDay = day
        if (BsCalendar.isSupported(day)) {
            val bs = BsCalendar.fromAd(day)
            text(R.id.bsDate)?.text = Nepali.bsDate(bs)
            text(R.id.bsYearDay)?.text = "${Nepali.digits(bs.year)} · ${Nepali.weekday(day.dayOfWeek)}"
        }
        WORKER.execute {
            val lunar = Panchang.udayaTithi(day)
            val list = Panchang.festivals(day, 60)
            main.post {
                if (day != nepalDay) return@post
                lunarText = Nepali.lunarLine(lunar)
                festivals = list
                showLunarAndFestivals(day)
            }
        }
    }

    private fun showLunarAndFestivals(day: LocalDate) {
        text(R.id.tithiLine)?.text = lunarText
        val today = festivals.filter { it.date == day }.joinToString(" · ") { it.name }
        text(R.id.todayFestival)?.apply {
            text = today
            visibility = if (today.isEmpty()) View.GONE else View.VISIBLE
        }
        val upcoming = festivals.filter { it.date.isAfter(day) }
        text(R.id.nextFestival)?.text = upcoming.firstOrNull()?.let {
            "अर्को चाड: ${it.name}, ${Nepali.daysAway(ChronoUnit.DAYS.between(day, it.date))}"
        } ?: ""

        val list = root.findViewById<LinearLayout>(R.id.festivalList) ?: return
        list.removeAllViews()
        val ctx = root.context
        for (f in upcoming.take(maxFestivals)) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(ctx).apply {
                text = f.name
                setTextColor(ctx.getColor(R.color.snow))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = ctx.resources.getFont(R.font.mukta_regular)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(ctx).apply {
                text = Nepali.daysAway(ChronoUnit.DAYS.between(day, f.date))
                setTextColor(ctx.getColor(R.color.snow_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = ctx.resources.getFont(R.font.mukta_light)
            })
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Tiles.dp(ctx, 3f)
            })
        }
        root.findViewById<View>(R.id.festivalHeader)?.visibility = if (upcoming.isEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        private val WORKER = Executors.newSingleThreadExecutor()

        fun cityName(zone: ZoneId): String =
            if (zone.id.contains('/')) zone.id.substringAfterLast('/').replace('_', ' ') else "Local"

        /**
         * The city chosen in launcher settings, else the TV's own time zone. Some TVs only report a
         * fixed offset like "GMT+11:00", which has no city name and ignores daylight saving; choosing
         * a city in settings fixes both.
         */
        fun localZone(context: android.content.Context): ZoneId {
            val chosen = Prefs(context).homeZone
            return if (chosen.isNotEmpty()) runCatching { ZoneId.of(chosen) }.getOrDefault(ZoneId.systemDefault()) else ZoneId.systemDefault()
        }
    }
}
