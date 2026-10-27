package app.himalayandawn.tv.calendar

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A Bikram Sambat date. [month] is 1 (Baisakh) to 12 (Chaitra). */
data class BsDate(val year: Int, val month: Int, val day: Int)

object BsCalendar {
    /** BS 2000 Baisakh 1 fell on 14 April 1943. */
    private val EPOCH_AD: LocalDate = LocalDate.of(1943, 4, 14)

    val lastYear: Int get() = BsData.FIRST_YEAR + BsData.monthDays.size - 1

    fun daysInMonth(year: Int, month: Int): Int = BsData.monthDays[year - BsData.FIRST_YEAR][month - 1]

    fun fromAd(date: LocalDate): BsDate {
        var remaining = ChronoUnit.DAYS.between(EPOCH_AD, date)
        require(remaining >= 0) { "Date before BS ${BsData.FIRST_YEAR}" }
        for ((yi, months) in BsData.monthDays.withIndex()) {
            for ((mi, days) in months.withIndex()) {
                if (remaining < days) return BsDate(BsData.FIRST_YEAR + yi, mi + 1, remaining.toInt() + 1)
                remaining -= days
            }
        }
        throw IllegalArgumentException("Date after BS $lastYear")
    }

    fun toAd(bs: BsDate): LocalDate {
        var days = 0L
        for (y in BsData.FIRST_YEAR until bs.year) days += BsData.monthDays[y - BsData.FIRST_YEAR].sum()
        for (m in 1 until bs.month) days += daysInMonth(bs.year, m)
        return EPOCH_AD.plusDays(days + bs.day - 1)
    }

    fun isSupported(date: LocalDate): Boolean =
        !date.isBefore(EPOCH_AD) && date.isBefore(toAd(BsDate(lastYear, 12, daysInMonth(lastYear, 12))).plusDays(1))
}
