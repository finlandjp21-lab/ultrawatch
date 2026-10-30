package com.harshvardhan.ultrawatch.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Satellite and coordinate helpers for the GPS screen. */
object Gnss {
    /** Constellation ids match android.location.GnssStatus.CONSTELLATION_* values. */
    fun constellationName(id: Int): String = when (id) {
        1 -> "GPS"; 2 -> "SBAS"; 3 -> "GLONASS"; 4 -> "QZSS"; 5 -> "BeiDou"; 6 -> "Galileo"; 7 -> "NavIC"
        else -> "Other"
    }

    /** Classifies a carrier frequency (Hz) into its band, e.g. 1575.42 MHz -> "L1", 1176.45 MHz -> "L5". */
    fun band(carrierHz: Float?): String? {
        if (carrierHz == null || carrierHz <= 0f) return null
        val mhz = carrierHz / 1e6
        return when {
            abs(mhz - 1575.42) < 5 -> "L1"        // GPS L1, Galileo E1, QZSS L1, BeiDou B1C, SBAS
            abs(mhz - 1561.098) < 3 -> "B1"       // BeiDou B1I
            mhz in 1598.0..1610.0 -> "G1"         // GLONASS L1 FDMA
            abs(mhz - 1176.45) < 5 -> "L5"        // GPS L5, Galileo E5a, NavIC L5, BeiDou B2a, QZSS L5
            abs(mhz - 1207.14) < 5 -> "E5b"
            abs(mhz - 1227.60) < 5 -> "L2"
            abs(mhz - 1278.75) < 5 -> "E6"
            else -> null
        }
    }

    data class Sat(val constellation: Int, val svid: Int, val cn0: Float, val used: Boolean, val carrierHz: Float?)

    data class Summary(
        val inView: Int,
        val used: Int,
        val byConstellation: Map<String, Pair<Int, Int>>, // name -> (used, inView)
        val bands: Set<String>,
        val dualFrequency: Boolean,
        val avgCn0Used: Double,
    )

    fun summarize(sats: List<Sat>): Summary {
        val by = sats.groupBy { constellationName(it.constellation) }
            .mapValues { (_, l) -> l.count { it.used } to l.size }
            .toSortedMap()
        val bands = sats.mapNotNull { band(it.carrierHz) }.toSet()
        val l1 = bands.any { it in setOf("L1", "B1", "G1") }
        val l5 = bands.any { it in setOf("L5", "E5b", "L2") }
        val used = sats.filter { it.used }
        return Summary(
            inView = sats.size,
            used = used.size,
            byConstellation = by,
            bands = bands,
            dualFrequency = l1 && l5,
            avgCn0Used = if (used.isEmpty()) 0.0 else (used.map { it.cn0.toDouble() }.average() * 10).roundToInt() / 10.0,
        )
    }

    /** Fix quality label from horizontal accuracy (m) and satellites used. */
    fun fixQuality(accuracyM: Float?, used: Int): String = when {
        accuracyM == null || used < 3 -> "No fix"
        accuracyM <= 5 && used >= 8 -> "Excellent"
        accuracyM <= 10 -> "Good"
        accuracyM <= 25 -> "Fair"
        else -> "Poor"
    }

    fun decimal(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    /** 60.29245 -> 60°17'32.8"N */
    fun dms(value: Double, isLat: Boolean): String {
        val hemi = if (isLat) (if (value >= 0) "N" else "S") else (if (value >= 0) "E" else "W")
        val a = abs(value)
        var d = a.toInt()
        var m = ((a - d) * 60).toInt()
        var s = ((a - d - m / 60.0) * 3600 * 10).roundToInt() / 10.0
        if (s >= 60.0) { s = 0.0; m++ }
        if (m >= 60) { m = 0; d++ }
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", d, m, s, hemi)
    }

    fun speedKmh(mps: Float) = (mps * 3.6 * 10).roundToInt() / 10.0

    data class Waypoint(val name: String, val lat: Double, val lon: Double, val savedAt: Long)

    fun encodeWaypoints(list: List<Waypoint>) = list.joinToString("\n") { "${it.name.replace("|", " ")}|${it.lat}|${it.lon}|${it.savedAt}" }

    fun decodeWaypoints(s: String?): List<Waypoint> = s.orEmpty().lines().mapNotNull {
        val p = it.split("|"); if (p.size != 4) return@mapNotNull null
        Waypoint(p[0], p[1].toDoubleOrNull() ?: return@mapNotNull null, p[2].toDoubleOrNull() ?: return@mapNotNull null, p[3].toLongOrNull() ?: 0)
    }
}
