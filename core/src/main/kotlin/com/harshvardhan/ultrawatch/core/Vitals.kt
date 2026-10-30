package com.harshvardhan.ultrawatch.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Vitals from external, standards-based devices or manual entry, interpreted with
 * published guidelines. Nothing here invents a reading: every value comes from a real
 * measurement, and every interpretation is labelled as screening, not diagnosis.
 */
object Vitals {

    // ---- Blood pressure: 2017 ACC/AHA categories ----
    enum class BpCategory(val label: String) {
        NORMAL("Normal"), ELEVATED("Elevated"), STAGE1("High - stage 1"),
        STAGE2("High - stage 2"), CRISIS("Hypertensive crisis"),
    }

    fun bpCategory(systolic: Int, diastolic: Int): BpCategory = when {
        systolic > 180 || diastolic > 120 -> BpCategory.CRISIS
        systolic >= 140 || diastolic >= 90 -> BpCategory.STAGE2
        systolic >= 130 || diastolic >= 80 -> BpCategory.STAGE1
        systolic >= 120 -> BpCategory.ELEVATED
        else -> BpCategory.NORMAL
    }

    fun bpAdvice(c: BpCategory): String = when (c) {
        BpCategory.NORMAL -> "In the normal range."
        BpCategory.ELEVATED -> "Slightly raised. Recheck on another day."
        BpCategory.STAGE1 -> "Raised. Discuss with your doctor if it stays here."
        BpCategory.STAGE2 -> "High. Talk to your doctor soon."
        BpCategory.CRISIS -> "Very high. Rest 5 min and remeasure. If still this high, or with chest pain, breathlessness or weakness, seek emergency care now."
    }

    fun isPlausibleBp(sys: Int, dia: Int, pulse: Int? = null) =
        sys in 60..260 && dia in 30..160 && sys > dia && (pulse == null || pulse in 25..240)

    // ---- SpO2 ----
    fun spo2Label(pct: Int): String = when {
        pct >= 95 -> "Normal"
        pct >= 92 -> "Slightly low"
        pct >= 88 -> "Low - recheck, consult a doctor"
        else -> "Very low - seek medical help"
    }

    /**
     * Oxygen Desaturation Index: dips of >= dropPct below the preceding 120 s baseline,
     * counted once per event, per hour of valid recording. samples = (timeMs, spo2%).
     * Invalid samples (<50 or >100) are ignored, as oximeters report them on poor contact.
     */
    fun odi(samples: List<Pair<Long, Int>>, dropPct: Int = 3, baselineWindowMs: Long = 120_000): Double? {
        val s = samples.filter { it.second in 50..100 }.sortedBy { it.first }
        if (s.size < 10) return null
        val hours = (s.last().first - s.first().first) / 3_600_000.0
        if (hours < 1.0) return null
        var events = 0
        var inEvent = false
        var lo = 0
        for (i in s.indices) {
            val t = s[i].first
            while (s[lo].first < t - baselineWindowMs) lo++
            if (lo == i) continue
            val baseline = (lo until i).map { s[it].second }.average()
            val drop = baseline - s[i].second
            if (!inEvent && drop >= dropPct) { events++; inEvent = true }
            else if (inEvent && drop < dropPct - 1) inEvent = false
        }
        return (events / hours * 10).roundToInt() / 10.0
    }

    /** Standard AASM-style severity bands used for ODI/AHI screening. */
    fun apneaScreen(odi: Double?): String = when {
        odi == null -> "Need at least 1 hour of overnight data"
        odi < 5 -> "Normal range"
        odi < 15 -> "Mild pattern - consider discussing with a doctor"
        odi < 30 -> "Moderate pattern - recommend a sleep study"
        else -> "Severe pattern - see a doctor about a sleep study"
    }

    // ---- Heart rhythm from RR intervals (chest strap) ----
    data class RhythmResult(val beats: Int, val rmssd: Double, val pnn50: Double, val irregular: Boolean?)

    /**
     * Screening for an irregular rhythm pattern: after removing ectopic-looking beats
     * (>20 % from the local median), a normalised RMSSD above 0.1 together with pNN50 above 40 %
     * across >= 60 beats is flagged. This is a heuristic indicator, not an ECG or diagnosis.
     */
    fun rhythm(rrMs: List<Double>): RhythmResult {
        val valid = rrMs.filter { it in 270.0..2_000.0 }
        if (valid.size < 2) return RhythmResult(valid.size, 0.0, 0.0, null)
        val rmssd = HealthMath.rmssd(valid)
        var nn50 = 0
        for (i in 1 until valid.size) if (abs(valid[i] - valid[i - 1]) > 50) nn50++
        val pnn50 = nn50 * 100.0 / (valid.size - 1)
        val irregular = if (valid.size < 60) null else {
            val mean = valid.average()
            val nRmssd = rmssd / mean
            nRmssd > 0.10 && pnn50 > 40.0
        }
        return RhythmResult(valid.size, (rmssd * 10).roundToInt() / 10.0, (pnn50 * 10).roundToInt() / 10.0, irregular)
    }

    // ---- Body composition: published estimation formulas ----
    enum class Sex { MALE, FEMALE }

    fun bmi(weightKg: Double, heightCm: Double): Double = weightKg / (heightCm / 100).pow(2)

    fun bmiLabel(b: Double) = when {
        b < 18.5 -> "Underweight"
        b < 25 -> "Healthy"
        b < 30 -> "Overweight"
        else -> "Obese"
    }

    /** Deurenberg (1991): BF% = 1.20 x BMI + 0.23 x age - 10.8 x sex - 5.4 (sex: male 1, female 0). Adults 18+. */
    fun bodyFatPct(weightKg: Double, heightCm: Double, age: Int, sex: Sex): Double {
        val v = 1.20 * bmi(weightKg, heightCm) + 0.23 * age - 10.8 * (if (sex == Sex.MALE) 1 else 0) - 5.4
        return (v.coerceIn(3.0, 60.0) * 10).roundToInt() / 10.0
    }

    /** Boer (1984) lean body mass, kg. */
    fun leanMassKg(weightKg: Double, heightCm: Double, sex: Sex): Double {
        val v = when (sex) {
            Sex.MALE -> 0.407 * weightKg + 0.267 * heightCm - 19.2
            Sex.FEMALE -> 0.252 * weightKg + 0.473 * heightCm - 48.3
        }
        return (v * 10).roundToInt() / 10.0
    }

    /** Watson (1980) total body water, litres. */
    fun bodyWaterL(weightKg: Double, heightCm: Double, age: Int, sex: Sex): Double {
        val v = when (sex) {
            Sex.MALE -> 2.447 - 0.09516 * age + 0.1074 * heightCm + 0.3362 * weightKg
            Sex.FEMALE -> -2.097 + 0.1069 * heightCm + 0.2466 * weightKg
        }
        return (v * 10).roundToInt() / 10.0
    }

    /** Mifflin-St Jeor resting energy, kcal/day. */
    fun bmr(weightKg: Double, heightCm: Double, age: Int, sex: Sex): Int =
        (10 * weightKg + 6.25 * heightCm - 5 * age + if (sex == Sex.MALE) 5 else -161).roundToInt()
}

/**
 * Parsers for standard Bluetooth SIG GATT characteristics, so any certified device works:
 *  0x2A37 Heart Rate Measurement, 0x2A35 Blood Pressure Measurement,
 *  0x2A5E PLX Spot-Check Measurement, 0x2A5F PLX Continuous Measurement,
 *  0x2A9D Weight Measurement, 0x2A9C Body Composition Measurement.
 */
object GattParsers {
    data class HeartRate(val bpm: Int, val rrMs: List<Double>)
    data class BloodPressure(val systolic: Double, val diastolic: Double, val map: Double, val unitKpa: Boolean, val pulse: Double?)
    data class Plx(val spo2: Double, val pulse: Double)
    data class Weight(val kg: Double)
    data class BodyComp(val fatPct: Double, val muscleKg: Double?, val waterKg: Double?, val weightKg: Double?)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    /** IEEE-11073 16-bit SFLOAT. Returns null for NaN/NRes/+-INF special values. */
    fun sfloat(b: ByteArray, i: Int): Double? {
        val raw = u16(b, i)
        if (raw == 0x07FF || raw == 0x0800 || raw == 0x07FE || raw == 0x0802 || raw == 0x0801) return null
        var mantissa = raw and 0x0FFF
        if (mantissa >= 0x0800) mantissa -= 0x1000
        var exponent = raw shr 12
        if (exponent >= 0x8) exponent -= 0x10
        return mantissa * 10.0.pow(exponent)
    }

    fun heartRate(b: ByteArray): HeartRate {
        val flags = u8(b, 0)
        var i = 1
        val bpm = if (flags and 0x01 != 0) u16(b, i).also { i += 2 } else u8(b, i).also { i += 1 }
        if (flags and 0x08 != 0) i += 2 // energy expended
        val rr = ArrayList<Double>()
        if (flags and 0x10 != 0) while (i + 1 < b.size) { rr += u16(b, i) * 1000.0 / 1024.0; i += 2 }
        return HeartRate(bpm, rr)
    }

    fun bloodPressure(b: ByteArray): BloodPressure? {
        val flags = u8(b, 0)
        val sys = sfloat(b, 1) ?: return null
        val dia = sfloat(b, 3) ?: return null
        val map = sfloat(b, 5) ?: 0.0
        var i = 7
        if (flags and 0x02 != 0) i += 7 // timestamp
        val pulse = if (flags and 0x04 != 0 && i + 1 < b.size) sfloat(b, i) else null
        return BloodPressure(sys, dia, map, unitKpa = flags and 0x01 != 0, pulse = pulse)
    }

    /** Works for both spot-check (0x2A5E) and continuous (0x2A5F): SpO2 and PR are the first two SFLOATs. */
    fun plx(b: ByteArray): Plx? {
        val spo2 = sfloat(b, 1) ?: return null
        val pr = sfloat(b, 3) ?: return null
        return Plx(spo2, pr)
    }

    fun weight(b: ByteArray): Weight? {
        val flags = u8(b, 0)
        val raw = u16(b, 1)
        if (raw == 0xFFFF) return null
        return Weight(if (flags and 0x01 != 0) raw * 0.01 * 0.45359237 else raw * 0.005)
    }

    fun bodyComposition(b: ByteArray): BodyComp {
        val flags = u16(b, 0)
        val imperial = flags and 0x0001 != 0
        val massRes = if (imperial) 0.01 * 0.45359237 else 0.005
        val fat = u16(b, 2) * 0.1
        var i = 4
        if (flags and 0x0002 != 0) i += 7            // timestamp
        if (flags and 0x0004 != 0) i += 1            // user id
        if (flags and 0x0008 != 0) i += 2            // basal metabolism
        if (flags and 0x0010 != 0) i += 2            // muscle percentage
        val muscle = if (flags and 0x0020 != 0) (u16(b, i) * massRes).also { i += 2 } else null
        if (flags and 0x0040 != 0) i += 2            // fat free mass
        if (flags and 0x0080 != 0) i += 2            // soft lean mass
        val water = if (flags and 0x0100 != 0) (u16(b, i) * massRes).also { i += 2 } else null
        if (flags and 0x0200 != 0) i += 2            // impedance
        val w = if (flags and 0x0400 != 0 && i + 1 < b.size) u16(b, i) * massRes else null
        return BodyComp(fat, muscle, water, w)
    }
}
