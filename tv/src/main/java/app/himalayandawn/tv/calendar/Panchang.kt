package app.himalayandawn.tv.calendar

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

val NEPAL_ZONE: ZoneId = ZoneId.of("Asia/Kathmandu")

/**
 * Lunar day at a moment. [tithi] is 1..30 (1-15 shukla, 16-30 krishna, 30 = aunsi).
 * [amantaMonth] is 0 (Chaitra) .. 11 (Phalgun), months running new moon to new moon.
 */
data class LunarDay(val tithi: Int, val amantaMonth: Int, val adhik: Boolean, val monthStart: Long) {
    val shukla: Boolean get() = tithi <= 15
    val tithiInPaksha: Int get() = if (tithi <= 15) tithi else tithi - 15

    /** Nepali calendars name the krishna paksha after the following month (purnimanta). */
    val displayMonth: Int get() = if (shukla) amantaMonth else (amantaMonth + 1) % 12
}

enum class Observe { SUNRISE, PRADOSH, MIDNIGHT }

data class Festival(val name: String, val date: LocalDate)

object Panchang {
    fun lunarDay(epochMillis: Long): LunarDay {
        val tithi = floor(Astro.elongation(epochMillis) / 12.0).toInt() + 1
        val start = Astro.previousNewMoon(epochMillis)
        val end = Astro.nextNewMoon(epochMillis)
        val r1 = Astro.sunRashi(start)
        val r2 = Astro.sunRashi(end)
        return LunarDay(tithi.coerceIn(1, 30), (r1 + 1) % 12, r1 == r2, start)
    }

    fun observeMoment(date: LocalDate, rule: Observe): Long = when (rule) {
        Observe.SUNRISE -> Astro.sunEvent(date, rise = true)
        Observe.PRADOSH -> Astro.sunEvent(date, rise = false) + 72 * 60_000L
        Observe.MIDNIGHT -> date.plusDays(1).atStartOfDay(NEPAL_ZONE).toInstant().toEpochMilli() - 60_000L
    }

    /** The tithi that governs a Nepal calendar day (the one prevailing at sunrise in Kathmandu). */
    fun udayaTithi(date: LocalDate): LunarDay = lunarDay(observeMoment(date, Observe.SUNRISE))

    private class LunarRule(val name: String, val month: Int, val tithi: Int, val observe: Observe = Observe.SUNRISE)
    private class SolarRule(val name: String, val bsMonth: Int, val bsDay: Int)

    // Amanta month numbers: 0 Chaitra, 1 Baisakh, 2 Jestha, 3 Asar, 4 Shrawan, 5 Bhadra,
    // 6 Ashwin, 7 Kartik, 8 Mangsir, 9 Poush, 10 Magh, 11 Phalgun.
    private val LUNAR = listOf(
        LunarRule("रामनवमी", 0, 9),
        LunarRule("माता तीर्थ औंसी", 0, 30),
        LunarRule("बुद्ध जयन्ती", 1, 15),
        LunarRule("नाग पञ्चमी", 4, 5),
        LunarRule("जनै पूर्णिमा", 4, 15),
        LunarRule("गाईजात्रा", 4, 16),
        LunarRule("श्रीकृष्ण जन्माष्टमी", 4, 23, Observe.MIDNIGHT),
        LunarRule("कुशे औंसी", 4, 30),
        LunarRule("हरितालिका तीज", 5, 3),
        LunarRule("ऋषि पञ्चमी", 5, 5),
        LunarRule("इन्द्रजात्रा", 5, 14),
        LunarRule("घटस्थापना", 6, 1),
        // Kalratri falls on the night of Ashtami, so these two follow the tithi at midnight (as Hamro Patro does).
        LunarRule("फूलपाती", 6, 7, Observe.MIDNIGHT),
        LunarRule("महाअष्टमी", 6, 8, Observe.MIDNIGHT),
        LunarRule("महानवमी", 6, 9),
        LunarRule("विजया दशमी", 6, 10),
        LunarRule("कोजाग्रत पूर्णिमा", 6, 15, Observe.MIDNIGHT),
        LunarRule("काग तिहार", 6, 28),
        LunarRule("कुकुर तिहार", 6, 29),
        LunarRule("लक्ष्मी पूजा", 6, 30, Observe.PRADOSH),
        LunarRule("गोवर्धन पूजा", 7, 1),
        LunarRule("भाइटीका", 7, 2),
        LunarRule("छठ", 7, 6),
        LunarRule("हरिबोधिनी एकादशी", 7, 11),
        LunarRule("बसन्त पञ्चमी", 10, 5),
        LunarRule("महाशिवरात्रि", 10, 29, Observe.MIDNIGHT),
        LunarRule("फागु पूर्णिमा (होली)", 11, 15),
        LunarRule("घोडेजात्रा", 11, 30),
    )

    private val SOLAR = listOf(
        SolarRule("नयाँ वर्ष", 1, 1),
        SolarRule("गणतन्त्र दिवस", 2, 15),
        SolarRule("असार १५ · दही चिउरा", 3, 15),
        SolarRule("संविधान दिवस", 6, 3),
        SolarRule("तमु ल्होसार", 9, 15),
        SolarRule("पृथ्वी जयन्ती", 9, 27),
        SolarRule("माघे संक्रान्ति", 10, 1),
        SolarRule("शहीद दिवस", 10, 16),
        SolarRule("प्रजातन्त्र दिवस", 11, 7),
    )

    /** Festivals falling on Nepal dates from [from] to [from] + [days] - 1, in date order. */
    fun festivals(from: LocalDate, days: Int): List<Festival> {
        val out = ArrayList<Festival>()
        // Lunar: the first day in the right month whose observed tithi has reached the target.
        // That handles both a tithi spanning two days (take the first) and a skipped tithi (take the next).
        val lunarByRule = HashMap<Observe, List<LunarDay>>()
        for (rule in LUNAR) {
            val series = lunarByRule.getOrPut(rule.observe) {
                (-1..days - 1).map { lunarDay(observeMoment(from.plusDays(it.toLong()), rule.observe)) }
            }
            for (i in 1 until series.size) {
                val prev = series[i - 1]
                val cur = series[i]
                val reached = cur.amantaMonth == rule.month && !cur.adhik && cur.tithi >= rule.tithi
                val sameMonth = kotlin.math.abs(prev.monthStart - cur.monthStart) < 86_400_000L
                val alreadyReached = sameMonth && prev.tithi >= rule.tithi
                if (reached && !alreadyReached) out += Festival(rule.name, from.plusDays((i - 1).toLong()))
            }
        }
        var date = from
        repeat(days) {
            if (BsCalendar.isSupported(date)) {
                val bs = BsCalendar.fromAd(date)
                SOLAR.filter { it.bsMonth == bs.month && it.bsDay == bs.day }.forEach { out += Festival(it.name, date) }
            }
            date = date.plusDays(1)
        }
        return out.sortedBy { it.date }
    }
}
