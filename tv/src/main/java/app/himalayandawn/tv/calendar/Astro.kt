package app.himalayandawn.tv.calendar

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Low-precision sun and moon positions (Meeus, Astronomical Algorithms, ch. 25 and 47).
 * Good to roughly 0.01 degrees, i.e. tithi boundaries to within a few minutes.
 */
object Astro {
    private const val DEG = Math.PI / 180.0
    private const val SYNODIC_DAYS = 29.530588853
    private const val ELONGATION_PER_DAY = 360.0 / SYNODIC_DAYS

    // Kathmandu
    const val LAT = 27.7172
    const val LON = 85.3240

    fun julianDay(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2440587.5
    private fun millis(jd: Double): Long = ((jd - 2440587.5) * 86_400_000.0).toLong()

    private fun norm(deg: Double): Double { val x = deg % 360.0; return if (x < 0) x + 360.0 else x }

    /** Apparent tropical longitude of the sun, degrees. */
    fun sunLongitude(epochMillis: Long): Double {
        val t = (julianDay(epochMillis) - 2451545.0) / 36525.0
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = (357.52911 + 35999.05029 * t - 0.0001537 * t * t) * DEG
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) +
            (0.019993 - 0.000101 * t) * sin(2 * m) + 0.000289 * sin(3 * m)
        val omega = (125.04 - 1934.136 * t) * DEG
        return norm(l0 + c - 0.00569 - 0.00478 * sin(omega))
    }

    // Meeus table 47.A: D, M, M', F, coefficient (1e-6 degrees)
    private val MOON_TERMS = arrayOf(
        intArrayOf(0, 0, 1, 0, 6288774), intArrayOf(2, 0, -1, 0, 1274027), intArrayOf(2, 0, 0, 0, 658314),
        intArrayOf(0, 0, 2, 0, 213618), intArrayOf(0, 1, 0, 0, -185116), intArrayOf(0, 0, 0, 2, -114332),
        intArrayOf(2, 0, -2, 0, 58793), intArrayOf(2, -1, -1, 0, 57066), intArrayOf(2, 0, 1, 0, 53322),
        intArrayOf(2, -1, 0, 0, 45758), intArrayOf(0, 1, -1, 0, -40923), intArrayOf(1, 0, 0, 0, -34720),
        intArrayOf(0, 1, 1, 0, -30383), intArrayOf(2, 0, 0, -2, 15327), intArrayOf(0, 0, 1, 2, -12528),
        intArrayOf(0, 0, 1, -2, 10980), intArrayOf(4, 0, -1, 0, 10675), intArrayOf(0, 0, 3, 0, 10034),
        intArrayOf(4, 0, -2, 0, 8548), intArrayOf(2, 1, -1, 0, -7888), intArrayOf(2, 1, 0, 0, -6766),
        intArrayOf(1, 0, -1, 0, -5163), intArrayOf(1, 1, 0, 0, 4987), intArrayOf(2, -1, 1, 0, 4036),
        intArrayOf(2, 0, 2, 0, 3994), intArrayOf(4, 0, 0, 0, 3861), intArrayOf(2, 0, -3, 0, 3665),
        intArrayOf(0, 1, -2, 0, -2689), intArrayOf(2, 0, -1, 2, -2602), intArrayOf(2, -1, -2, 0, 2390),
        intArrayOf(1, 0, 1, 0, -2348), intArrayOf(2, -2, 0, 0, 2236), intArrayOf(0, 1, 2, 0, -2120),
        intArrayOf(0, 2, 0, 0, -2069),
    )

    /** Apparent tropical longitude of the moon, degrees. */
    fun moonLongitude(epochMillis: Long): Double {
        val t = (julianDay(epochMillis) - 2451545.0) / 36525.0
        val lp = 218.3164477 + 481267.88123421 * t - 0.0015786 * t * t
        val d = (297.8501921 + 445267.1114034 * t - 0.0018819 * t * t) * DEG
        val m = (357.5291092 + 35999.0502909 * t - 0.0001536 * t * t) * DEG
        val mp = (134.9633964 + 477198.8675055 * t + 0.0087414 * t * t) * DEG
        val f = (93.2720950 + 483202.0175233 * t - 0.0036539 * t * t) * DEG
        val e = 1 - 0.002516 * t - 0.0000074 * t * t
        var sum = 0.0
        for (term in MOON_TERMS) {
            val arg = term[0] * d + term[1] * m + term[2] * mp + term[3] * f
            val ecc = when (kotlin.math.abs(term[1])) { 1 -> e; 2 -> e * e; else -> 1.0 }
            sum += term[4] * ecc * sin(arg)
        }
        val a1 = (119.75 + 131.849 * t) * DEG
        val a2 = (53.09 + 479264.290 * t) * DEG
        sum += 3958 * sin(a1) + 1962 * sin(lp * DEG - f) + 318 * sin(a2)
        val omega = (125.04452 - 1934.136261 * t) * DEG
        return norm(lp + sum / 1_000_000.0 - 0.00478 * sin(omega))
    }

    /** Moon minus sun, 0..360. */
    fun elongation(epochMillis: Long): Double = norm(moonLongitude(epochMillis) - sunLongitude(epochMillis))

    /** Lahiri ayanamsa, degrees (linear approximation, fine for this century). */
    fun ayanamsa(epochMillis: Long): Double {
        val years = (julianDay(epochMillis) - 2451545.0) / 365.25
        return 23.853 + 0.0139696 * years
    }

    /** Sidereal zodiac sign of the sun, 0 = Mesh ... 11 = Meen. */
    fun sunRashi(epochMillis: Long): Int = floor(norm(sunLongitude(epochMillis) - ayanamsa(epochMillis)) / 30.0).toInt()

    /** Most recent new moon at or before [epochMillis]. */
    fun previousNewMoon(epochMillis: Long): Long {
        var jd = julianDay(epochMillis) - elongation(epochMillis) / ELONGATION_PER_DAY
        repeat(6) {
            var e = elongation(millis(jd))
            if (e > 180) e -= 360.0
            jd -= e / ELONGATION_PER_DAY
        }
        val result = millis(jd)
        return if (result > epochMillis) previousNewMoon(epochMillis - 86_400_000L * 2) else result
    }

    fun nextNewMoon(epochMillis: Long): Long {
        val prev = previousNewMoon(epochMillis)
        var jd = julianDay(prev) + SYNODIC_DAYS
        repeat(6) {
            var e = elongation(millis(jd))
            if (e > 180) e -= 360.0
            jd -= e / ELONGATION_PER_DAY
        }
        return millis(jd)
    }

    /**
     * Sunrise or sunset at Kathmandu on [date] (NOAA method), as epoch millis.
     */
    fun sunEvent(date: LocalDate, rise: Boolean): Long {
        val noonUtc = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + 12 * 3_600_000L
        val t = (julianDay(noonUtc) - 2451545.0) / 36525.0
        val l0 = 280.46646 + 36000.76983 * t
        val m = (357.52911 + 35999.05029 * t) * DEG
        val e = 0.016708634 - 0.000042037 * t
        val c = (1.914602 - 0.004817 * t) * sin(m) + 0.019993 * sin(2 * m) + 0.000289 * sin(3 * m)
        val lambda = (l0 + c - 0.00569 - 0.00478 * sin((125.04 - 1934.136 * t) * DEG)) * DEG
        val eps = (23.439291 - 0.0130042 * t) * DEG
        val decl = asin(sin(eps) * sin(lambda))
        val y = tan(eps / 2) * tan(eps / 2)
        val l0r = l0 * DEG
        val eqTimeMin = 4 / DEG * (y * sin(2 * l0r) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0r) -
            0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * m))
        val latR = LAT * DEG
        val cosH = (cos(90.833 * DEG) - sin(latR) * sin(decl)) / (cos(latR) * cos(decl))
        val h = acos(cosH.coerceIn(-1.0, 1.0)) / DEG
        val minutesUtc = 720 - 4 * (LON + if (rise) h else -h) - eqTimeMin
        return date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + (minutesUtc * 60_000).toLong()
    }
}
