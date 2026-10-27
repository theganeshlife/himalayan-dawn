package app.himalayandawn.tv.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarTest {
    @Test
    fun bsConversionMatchesKnownDates() {
        assertEquals(BsDate(2082, 1, 1), BsCalendar.fromAd(LocalDate.of(2025, 4, 14)))
        assertEquals(BsDate(2083, 1, 1), BsCalendar.fromAd(LocalDate.of(2026, 4, 14)))
        assertEquals(BsDate(2083, 7, 1), BsCalendar.fromAd(LocalDate.of(2026, 10, 18)))
        assertEquals(BsDate(2083, 7, 9), BsCalendar.fromAd(LocalDate.of(2026, 10, 26)))
        assertEquals(BsDate(2083, 8, 1), BsCalendar.fromAd(LocalDate.of(2026, 11, 17)))
        assertEquals(LocalDate.of(2026, 10, 26), BsCalendar.toAd(BsDate(2083, 7, 9)))
    }

    private fun dateOf(list: List<Festival>, name: String): LocalDate? = list.firstOrNull { it.name == name }?.date

    private fun within(actual: LocalDate?, vararg accepted: String) {
        assertTrue("got $actual, expected one of ${accepted.toList()}", accepted.map(LocalDate::parse).contains(actual))
    }

    @Test
    fun dashainTiharChhath2026() {
        val f = Panchang.festivals(LocalDate.of(2026, 10, 1), 60)
        f.forEach { println("${it.date}  ${it.name}") }
        // Expected dates from Hamro Patro, Kartik 2083.
        within(dateOf(f, "घटस्थापना"), "2026-10-11")
        within(dateOf(f, "फूलपाती"), "2026-10-17")
        within(dateOf(f, "महाअष्टमी"), "2026-10-18")
        within(dateOf(f, "महानवमी"), "2026-10-20")
        within(dateOf(f, "विजया दशमी"), "2026-10-21")
        within(dateOf(f, "कोजाग्रत पूर्णिमा"), "2026-10-25")
        within(dateOf(f, "काग तिहार"), "2026-11-07")
        within(dateOf(f, "कुकुर तिहार"), "2026-11-08")
        within(dateOf(f, "लक्ष्मी पूजा"), "2026-11-08")
        within(dateOf(f, "गोवर्धन पूजा"), "2026-11-10")
        within(dateOf(f, "भाइटीका"), "2026-11-11")
        within(dateOf(f, "छठ"), "2026-11-15")
        assertEquals(15, Panchang.udayaTithi(LocalDate.of(2026, 10, 26)).tithi)
    }

    @Test
    fun printYear() {
        Panchang.festivals(LocalDate.of(2026, 4, 14), 380).forEach {
            println("${it.date}  ${BsCalendar.fromAd(it.date)}  ${it.name}")
        }
        var d = LocalDate.of(2026, 10, 15)
        repeat(6) {
            val rise = Astro.sunEvent(d, true)
            val ld = Panchang.lunarDay(rise)
            println("$d sunrise=${java.time.Instant.ofEpochMilli(rise).atZone(NEPAL_ZONE).toLocalTime()} tithi=${ld.tithi} elong=${"%.2f".format(Astro.elongation(rise))}")
            d = d.plusDays(1)
        }
    }
}
