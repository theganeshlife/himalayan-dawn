package app.himalayandawn.tv.calendar

import java.time.DayOfWeek
import java.time.LocalTime

object Nepali {
    private val DIGITS = charArrayOf('०', '१', '२', '३', '४', '५', '६', '७', '८', '९')

    val BS_MONTHS = arrayOf("बैशाख", "जेठ", "असार", "साउन", "भदौ", "असोज", "कार्तिक", "मंसिर", "पुस", "माघ", "फागुन", "चैत")
    private val LUNAR_MONTHS = arrayOf("चैत्र", "वैशाख", "ज्येष्ठ", "आषाढ", "श्रावण", "भाद्र", "आश्विन", "कार्तिक", "मार्ग", "पौष", "माघ", "फाल्गुन")
    private val TITHIS = arrayOf(
        "प्रतिपदा", "द्वितीया", "तृतीया", "चतुर्थी", "पञ्चमी", "षष्ठी", "सप्तमी", "अष्टमी",
        "नवमी", "दशमी", "एकादशी", "द्वादशी", "त्रयोदशी", "चतुर्दशी",
    )

    fun digits(n: Any): String = n.toString().map { if (it in '0'..'9') DIGITS[it - '0'] else it }.joinToString("")

    fun weekday(day: DayOfWeek): String = when (day) {
        DayOfWeek.SUNDAY -> "आइतबार"
        DayOfWeek.MONDAY -> "सोमबार"
        DayOfWeek.TUESDAY -> "मंगलबार"
        DayOfWeek.WEDNESDAY -> "बुधबार"
        DayOfWeek.THURSDAY -> "बिहीबार"
        DayOfWeek.FRIDAY -> "शुक्रबार"
        DayOfWeek.SATURDAY -> "शनिबार"
    }

    fun tithiName(ld: LunarDay): String = when (ld.tithi) {
        15 -> "पूर्णिमा"
        30 -> "औंसी"
        else -> TITHIS[ld.tithiInPaksha - 1]
    }

    /** e.g. "कार्तिक कृष्ण द्वितीया" or "आश्विन शुक्ल पूर्णिमा". */
    fun lunarLine(ld: LunarDay): String {
        val month = (if (ld.adhik) "अधिक " else "") + LUNAR_MONTHS[ld.displayMonth]
        val paksha = if (ld.shukla) "शुक्ल" else "कृष्ण"
        return "$month $paksha ${tithiName(ld)}"
    }

    fun bsDate(bs: BsDate): String = "${BS_MONTHS[bs.month - 1]} ${digits(bs.day)}"

    /** "२:२७" plus "अपराह्न" / "पूर्वाह्न". */
    fun time(t: LocalTime): Pair<String, String> {
        val h = t.hour % 12
        val clock = "${digits(if (h == 0) 12 else h)}:${digits(t.minute.toString().padStart(2, '0'))}"
        return clock to (if (t.hour < 12) "पूर्वाह्न" else "अपराह्न")
    }

    fun daysAway(days: Long): String = when (days) {
        0L -> "आज"
        1L -> "भोलि"
        else -> "${digits(days)} दिन"
    }
}
