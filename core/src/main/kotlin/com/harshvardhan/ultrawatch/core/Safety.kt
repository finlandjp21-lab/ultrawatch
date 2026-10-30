package com.harshvardhan.ultrawatch.core

import java.util.Locale
import kotlin.math.sqrt

/**
 * Fall detection from accelerometer samples (m/s2, any rate >= 25 Hz).
 * Pattern: free fall (|a| < 0.5 g for >= 80 ms) -> impact (|a| > 2.5 g within 1 s)
 * -> lying still (|a| within 0.85..1.15 g for >= 2 s). Only then is a fall reported,
 * which keeps false alarms from arm swings and claps low.
 */
class FallDetector(
    private val freeFallG: Double = 0.5,
    private val impactG: Double = 2.5,
    private val minFreeFallMs: Long = 80,
    private val impactWindowMs: Long = 1_000,
    private val stillMs: Long = 2_000,
) {
    enum class State { IDLE, FREE_FALL, AWAIT_IMPACT, AWAIT_STILL }

    var state = State.IDLE
        private set
    private var freeFallStart = 0L
    private var freeFallEnd = 0L
    private var stillStart = -1L
    private var impactAt = 0L

    fun reset() { state = State.IDLE; stillStart = -1 }

    /** Feed one sample. Returns true exactly once when a fall is confirmed. */
    fun onSample(tMs: Long, ax: Float, ay: Float, az: Float): Boolean {
        val g = sqrt((ax * ax + ay * ay + az * az).toDouble()) / 9.80665
        when (state) {
            State.IDLE -> if (g < freeFallG) { state = State.FREE_FALL; freeFallStart = tMs }
            State.FREE_FALL -> if (g >= freeFallG) {
                if (tMs - freeFallStart >= minFreeFallMs) { state = State.AWAIT_IMPACT; freeFallEnd = tMs }
                else state = State.IDLE
                // the sample that ended free fall may itself be the impact
                if (state == State.AWAIT_IMPACT && g > impactG) { state = State.AWAIT_STILL; impactAt = tMs; stillStart = -1 }
            }
            State.AWAIT_IMPACT -> when {
                g > impactG -> { state = State.AWAIT_STILL; impactAt = tMs; stillStart = -1 }
                tMs - freeFallEnd > impactWindowMs -> state = State.IDLE
            }
            State.AWAIT_STILL -> {
                if (g in 0.85..1.15) {
                    if (stillStart < 0) stillStart = tMs
                    if (tMs - stillStart >= stillMs) { reset(); return true }
                } else {
                    stillStart = -1
                    // person got up and is moving normally: not a fall
                    if (tMs - impactAt > stillMs + 3_000) reset()
                }
            }
        }
        return false
    }
}

/** Countdown used by fall alerts and the SOS button: user can cancel before it fires. */
class Countdown(private val totalMs: Long) {
    private var startedAt = -1L
    var cancelled = false
        private set

    fun start(now: Long) { startedAt = now; cancelled = false }
    fun cancel() { cancelled = true }
    val running get() = startedAt >= 0 && !cancelled
    fun remainingMs(now: Long): Long = if (startedAt < 0) totalMs else (totalMs - (now - startedAt)).coerceAtLeast(0)
    fun isExpired(now: Long) = running && remainingMs(now) == 0L
}

/**
 * Safety check-in: the user sets a duration (walking home, a hike). If they don't check in
 * before it ends, there's a grace period, then contacts are alerted.
 */
class SafetyCheck(private val begin: Long, private val durationMs: Long, private val graceMs: Long = 60_000) {
    enum class Status { ACTIVE, GRACE, ALERT, CHECKED_IN }
    private var checkedIn = false

    fun checkIn() { checkedIn = true }

    fun status(now: Long): Status = when {
        checkedIn -> Status.CHECKED_IN
        now - begin < durationMs -> Status.ACTIVE
        now - begin < durationMs + graceMs -> Status.GRACE
        else -> Status.ALERT
    }

    fun remainingMs(now: Long): Long = (durationMs - (now - begin)).coerceAtLeast(0)
}

object SosMessage {
    fun mapsLink(lat: Double, lon: Double): String =
        String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)

    /** Short enough for one SMS segment when the location is present (<= 160 chars for typical names). */
    fun build(userName: String, reason: String, lat: Double?, lon: Double?, accuracyM: Float? = null): String {
        val who = userName.ifBlank { "UltraWatch user" }
        val where = if (lat != null && lon != null) {
            " Location: " + mapsLink(lat, lon) + (accuracyM?.let { " (±${it.toInt()} m)" } ?: "")
        } else " Location unavailable."
        return "SOS from $who: $reason.$where"
    }
}

/** Battery helpers. Percent values 0..100, timestamps in ms. */
object BatteryMath {
    /**
     * Estimates minutes until full (charging) or empty (discharging) from two readings.
     * Returns null when the rate is zero/unknown or readings are too close together.
     */
    fun minutesRemaining(p1: Int, t1: Long, p2: Int, t2: Long, charging: Boolean): Int? {
        val dtMin = (t2 - t1) / 60_000.0
        if (dtMin < 1.0) return null
        val rate = (p2 - p1) / dtMin // percent per minute
        return when {
            charging && rate > 0 -> ((100 - p2) / rate).toInt()
            !charging && rate < 0 -> (p2 / -rate).toInt()
            else -> null
        }
    }

    fun formatMinutes(m: Int?): String = when {
        m == null -> "Calculating"
        m < 60 -> "${m} min"
        else -> "${m / 60} h ${m % 60} min"
    }

    /**
     * Charge-limit reminder with hysteresis: fires once when charging reaches the limit,
     * re-arms only after the level drops 5 points below it.
     */
    class ChargeReminder(private val limit: Int = 80) {
        private var armed = true
        fun onLevel(percent: Int, charging: Boolean): Boolean {
            if (percent <= limit - 5) armed = true
            if (armed && charging && percent >= limit) { armed = false; return true }
            return false
        }
    }
}

/** Languages offered in the translator (ML Kit language tags). */
object TranslatorLanguages {
    val ALL: List<Pair<String, String>> = listOf(
        "en" to "English", "hi" to "Hindi", "fi" to "Finnish", "sv" to "Swedish",
        "bn" to "Bengali", "ta" to "Tamil", "te" to "Telugu", "mr" to "Marathi",
        "gu" to "Gujarati", "kn" to "Kannada", "ur" to "Urdu", "de" to "German",
        "fr" to "French", "es" to "Spanish", "ar" to "Arabic", "ja" to "Japanese", "zh" to "Chinese",
    )

    fun name(code: String) = ALL.firstOrNull { it.first == code }?.second ?: code

    /** BCP-47 tag for the speech recogniser / text-to-speech, e.g. "hi" -> "hi-IN". */
    fun speechTag(code: String): String = when (code) {
        "en" -> "en-IN"; "hi" -> "hi-IN"; "fi" -> "fi-FI"; "sv" -> "sv-SE"; "bn" -> "bn-IN"
        "ta" -> "ta-IN"; "te" -> "te-IN"; "mr" -> "mr-IN"; "gu" -> "gu-IN"; "kn" -> "kn-IN"
        "ur" -> "ur-IN"; "de" -> "de-DE"; "fr" -> "fr-FR"; "es" -> "es-ES"; "ar" -> "ar-SA"
        "ja" -> "ja-JP"; "zh" -> "zh-CN"; else -> code
    }
}
