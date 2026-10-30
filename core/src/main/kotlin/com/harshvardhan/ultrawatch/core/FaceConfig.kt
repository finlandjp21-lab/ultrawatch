package com.harshvardhan.ultrawatch.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class FaceStyle(val label: String) {
    ANALOG_LUXE("Analog Luxe"),
    DIGITAL_BOLD("Digital Bold"),
    CHRONO_SPORT("Chrono Sport"),
    MINIMAL("Minimal"),
    TRADER("Trader"),
}

enum class SlotData(val label: String) {
    NONE("Empty"), HEART_RATE("Heart rate"), STEPS("Steps"), BATTERY("Battery"),
    DATE("Date"), NIFTY("NIFTY 50"), CALORIES("Calories"), NEXT_EVENT("Next event"),
}

enum class SlotPosition { TOP, LEFT, RIGHT, BOTTOM }

/** Everything the watch face designer controls. Serialised to a compact string for SharedPreferences. */
data class FaceConfig(
    val style: FaceStyle = FaceStyle.ANALOG_LUXE,
    val accent: Long = 0xFFFF7A1A,      // Harshvardhan orange
    val secondary: Long = 0xFF1E7BFF,   // Aitecsek blue
    val background: Long = 0xFF000000,
    val showSeconds: Boolean = true,
    val showLogo: Boolean = true,
    val tickDensity: Int = 12,          // 4, 12 or 60
    val slots: Map<SlotPosition, SlotData> = mapOf(
        SlotPosition.LEFT to SlotData.HEART_RATE,
        SlotPosition.RIGHT to SlotData.STEPS,
        SlotPosition.BOTTOM to SlotData.DATE,
    ),
) {
    fun encode(): String = buildString {
        append("v=1")
        append(";style=").append(style.name)
        append(";accent=").append(java.lang.Long.toHexString(accent))
        append(";secondary=").append(java.lang.Long.toHexString(secondary))
        append(";bg=").append(java.lang.Long.toHexString(background))
        append(";sec=").append(showSeconds)
        append(";logo=").append(showLogo)
        append(";ticks=").append(tickDensity)
        append(";slots=").append(slots.entries.joinToString(",") { "${it.key.name}:${it.value.name}" })
    }

    companion object {
        val PRESETS: List<Pair<String, FaceConfig>> = listOf(
            "Sunrise Luxe" to FaceConfig(),
            "Ocean Digital" to FaceConfig(style = FaceStyle.DIGITAL_BOLD, accent = 0xFF1E7BFF, secondary = 0xFF39D0FF),
            "Track Day" to FaceConfig(style = FaceStyle.CHRONO_SPORT, accent = 0xFFFF3B30, secondary = 0xFFFFD60A, tickDensity = 60),
            "Pure" to FaceConfig(style = FaceStyle.MINIMAL, accent = 0xFFFFFFFF, secondary = 0xFF8E8E93, showSeconds = false, tickDensity = 4, slots = emptyMap()),
            "Market Pulse" to FaceConfig(
                style = FaceStyle.TRADER, accent = 0xFF30D158, secondary = 0xFFFF453A,
                slots = mapOf(SlotPosition.TOP to SlotData.NIFTY, SlotPosition.LEFT to SlotData.HEART_RATE,
                    SlotPosition.RIGHT to SlotData.BATTERY, SlotPosition.BOTTOM to SlotData.DATE),
            ),
        )

        val PALETTE: List<Long> = listOf(
            0xFFFF7A1A, 0xFF1E7BFF, 0xFF39D0FF, 0xFF30D158, 0xFFFFD60A,
            0xFFFF3B30, 0xFFBF5AF2, 0xFFFF2D55, 0xFFFFFFFF, 0xFFC9A96E,
        )

        /** Never throws: unknown or broken fields fall back to defaults. */
        fun decode(text: String?): FaceConfig {
            if (text.isNullOrBlank()) return FaceConfig()
            val d = FaceConfig()
            val kv = text.split(";").mapNotNull {
                val idx = it.indexOf('=')
                if (idx <= 0) null else it.substring(0, idx) to it.substring(idx + 1)
            }.toMap()
            fun color(key: String, def: Long) = kv[key]?.let { runCatching { java.lang.Long.parseUnsignedLong(it, 16) }.getOrNull() } ?: def
            val slots = kv["slots"]?.let { s ->
                if (s.isBlank()) emptyMap() else s.split(",").mapNotNull { pair ->
                    val p = pair.split(":")
                    if (p.size != 2) return@mapNotNull null
                    val pos = runCatching { SlotPosition.valueOf(p[0]) }.getOrNull() ?: return@mapNotNull null
                    val data = runCatching { SlotData.valueOf(p[1]) }.getOrNull() ?: return@mapNotNull null
                    pos to data
                }.toMap()
            } ?: d.slots
            return FaceConfig(
                style = kv["style"]?.let { runCatching { FaceStyle.valueOf(it) }.getOrNull() } ?: d.style,
                accent = color("accent", d.accent),
                secondary = color("secondary", d.secondary),
                background = color("bg", d.background),
                showSeconds = kv["sec"]?.toBooleanStrictOrNull() ?: d.showSeconds,
                showLogo = kv["logo"]?.toBooleanStrictOrNull() ?: d.showLogo,
                tickDensity = kv["ticks"]?.toIntOrNull()?.takeIf { it in setOf(4, 12, 60) } ?: d.tickDensity,
                slots = slots,
            )
        }
    }
}

/** Geometry helpers for round and square watch faces. Angles are degrees clockwise from 12 o'clock. */
object WatchGeometry {
    fun hourAngle(hour: Int, minute: Int, second: Int = 0): Double =
        ((hour % 12) * 30.0) + minute * 0.5 + second * (0.5 / 60.0)

    fun minuteAngle(minute: Int, second: Int, millis: Int = 0): Double =
        minute * 6.0 + second * 0.1 + millis * 0.0001

    fun secondAngle(second: Int, millis: Int = 0, smooth: Boolean = true): Double =
        if (smooth) second * 6.0 + millis * 0.006 else second * 6.0

    /** Point on a circle centred at (cx, cy) for a clockwise-from-12 angle. */
    fun pointAt(cx: Double, cy: Double, radius: Double, angleDeg: Double): Pair<Double, Double> {
        val rad = (angleDeg - 90.0) * PI / 180.0
        return (cx + radius * cos(rad)) to (cy + radius * sin(rad))
    }

    /** Complication slot bounds as fractions of the screen (left, top, right, bottom). */
    fun slotBounds(pos: SlotPosition): FloatArray = when (pos) {
        SlotPosition.TOP -> floatArrayOf(0.35f, 0.14f, 0.65f, 0.32f)
        SlotPosition.LEFT -> floatArrayOf(0.14f, 0.40f, 0.36f, 0.60f)
        SlotPosition.RIGHT -> floatArrayOf(0.64f, 0.40f, 0.86f, 0.60f)
        SlotPosition.BOTTOM -> floatArrayOf(0.35f, 0.68f, 0.65f, 0.86f)
    }

    /** Sweep angle for a progress ring, clamped 0..360. */
    fun progressSweep(value: Double, goal: Double): Float =
        if (goal <= 0) 0f else (value / goal * 360.0).coerceIn(0.0, 360.0).toFloat()

    /** Tick marks to draw: returns angles. Major every 30 degrees. */
    fun ticks(density: Int): List<Pair<Double, Boolean>> {
        val n = if (density in setOf(4, 12, 60)) density else 12
        val step = 360.0 / n
        return (0 until n).map { i -> val a = i * step; a to (a % 30.0 == 0.0) }
    }
}
