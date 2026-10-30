package com.harshvardhan.ultrawatch.core

/** One device's battery as shown on the Batteries screen. */
data class DeviceBattery(
    val id: String,          // "watch", "phone" or a Bluetooth address
    val name: String,
    val kind: Kind,
    val percent: Int?,       // null = unknown
    val charging: Boolean = false,
    val updatedAt: Long = 0,
) {
    enum class Kind { WATCH, PHONE, EARBUDS, HEADPHONES, SENSOR, OTHER }

    /** Phone readings older than 15 min are shown as stale. */
    fun isStale(now: Long, maxAgeMs: Long = 15 * 60_000) = kind == Kind.PHONE && now - updatedAt > maxAgeMs
}

object BatteryProtocol {
    /** Data Layer message path used by the phone companion app. */
    const val PATH_PHONE_BATTERY = "/ultrawatch/phone_battery"
    const val PATH_FIND_PHONE = "/ultrawatch/find_phone"
    const val PATH_REQUEST_BATTERY = "/ultrawatch/request_battery"

    /** Payload: "percent|charging|timestamp" as UTF-8. Small and version-tolerant. */
    fun encode(percent: Int, charging: Boolean, timestamp: Long): ByteArray =
        "$percent|$charging|$timestamp".toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray?): Triple<Int, Boolean, Long>? {
        val p = bytes?.toString(Charsets.UTF_8)?.split("|") ?: return null
        if (p.size < 3) return null
        val pct = p[0].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        val ts = p[2].toLongOrNull() ?: return null
        return Triple(pct, p[1] == "true", ts)
    }

    /** BLE Battery Level characteristic 0x2A19: a single uint8, 0..100. */
    fun parseBleLevel(value: ByteArray?): Int? {
        if (value == null || value.isEmpty()) return null
        val v = value[0].toInt() and 0xFF
        return if (v in 0..100) v else null
    }

    /** Guess an icon category from the Bluetooth name and class. */
    fun kindFor(name: String?, majorClass: Int?): DeviceBattery.Kind {
        val n = name.orEmpty().lowercase()
        return when {
            listOf("bud", "pod", "earbud", "tws", "airdots", "nord", "rockerz").any { it in n } -> DeviceBattery.Kind.EARBUDS
            listOf("headphone", "wh-", "qc", "bose", "jbl", "sony", "sennheiser").any { it in n } -> DeviceBattery.Kind.HEADPHONES
            listOf("polar", "garmin hrm", "wahoo", "tickr", "strap", "hrm", "scale", "omron", "oximeter").any { it in n } -> DeviceBattery.Kind.SENSOR
            majorClass == 0x0400 -> DeviceBattery.Kind.HEADPHONES // Bluetooth major class: audio/video
            else -> DeviceBattery.Kind.OTHER
        }
    }

    /** Sort for display: watch, phone, then others by lowest battery first so low devices stand out. */
    fun sortForDisplay(list: List<DeviceBattery>): List<DeviceBattery> = list.sortedWith(
        compareBy<DeviceBattery> { when (it.kind) { DeviceBattery.Kind.WATCH -> 0; DeviceBattery.Kind.PHONE -> 1; else -> 2 } }
            .thenBy { it.percent ?: 101 }
            .thenBy { it.name }
    )

    /** Low-battery alert once per device per discharge (re-arms after charging back above threshold + 10). */
    class LowAlert(private val threshold: Int = 20) {
        private val alerted = HashSet<String>()
        fun check(d: DeviceBattery): Boolean {
            val p = d.percent ?: return false
            if (d.charging || p >= threshold + 10) { alerted.remove(d.id); return false }
            if (p <= threshold && alerted.add(d.id)) return true
            return false
        }
    }
}
