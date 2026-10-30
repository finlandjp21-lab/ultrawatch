package com.harshvardhan.ultrawatch.data

import android.content.Context
import android.content.SharedPreferences
import com.harshvardhan.ultrawatch.core.AlertEngine
import com.harshvardhan.ultrawatch.core.AppLock
import com.harshvardhan.ultrawatch.core.FaceConfig
import com.harshvardhan.ultrawatch.core.Vitals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User profile used by health maths. */
data class Profile(
    val name: String = "",
    val age: Int = 35,
    val male: Boolean = true,
    val heightCm: Int = 172,
    val weightKg: Int = 72,
    val restingHr: Int = 62,
) {
    val sex get() = if (male) Vitals.Sex.MALE else Vitals.Sex.FEMALE
}

data class EmergencyContact(val name: String, val number: String)

/**
 * Single place for persisted settings. SharedPreferences keeps it simple and readable
 * from the watch face and tile processes, which run in the same app.
 */
class Prefs private constructor(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("ultrawatch", Context.MODE_PRIVATE)

    private val _face = MutableStateFlow(FaceConfig.decode(sp.getString(K_FACE, null)))
    val face: StateFlow<FaceConfig> = _face

    var faceConfig: FaceConfig
        get() = _face.value
        set(v) { sp.edit().putString(K_FACE, v.encode()).apply(); _face.value = v }

    var profile: Profile
        get() = Profile(
            sp.getString("p_name", "") ?: "", sp.getInt("p_age", 35), sp.getBoolean("p_male", true),
            sp.getInt("p_h", 172), sp.getInt("p_w", 72), sp.getInt("p_rhr", 62),
        )
        set(p) { sp.edit().putString("p_name", p.name).putInt("p_age", p.age).putBoolean("p_male", p.male)
            .putInt("p_h", p.heightCm).putInt("p_w", p.weightKg).putInt("p_rhr", p.restingHr).apply() }

    var alerts: AlertEngine
        get() = AlertEngine.decode(sp.getString("alerts", "") ?: "")
        set(v) { sp.edit().putString("alerts", v.encode()).apply() }

    var watchlist: List<String>
        get() = (sp.getString("watchlist", null) ?: "NIFTY 50,BANKNIFTY,SENSEX,RELIANCE,HDFCBANK,TCS,INFY").split(",").filter { it.isNotBlank() }
        set(v) { sp.edit().putString("watchlist", v.joinToString(",")).apply() }

    /** Your own quotes endpoint (for example SLBA PRO). Empty = demo feed. */
    var marketEndpoint: String
        get() = sp.getString("mkt_url", "") ?: ""
        set(v) { sp.edit().putString("mkt_url", v.trim()).apply() }

    var lastQuotes: String
        get() = sp.getString("mkt_last", "") ?: ""
        set(v) { sp.edit().putString("mkt_last", v).apply() }

    var geminiKey: String
        get() = sp.getString("gem_key", "") ?: ""
        set(v) { sp.edit().putString("gem_key", v.trim()).apply() }

    var geminiModel: String
        get() = sp.getString("gem_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(v) { sp.edit().putString("gem_model", v.trim()).apply() }

    var emergencyContacts: List<EmergencyContact>
        get() = (sp.getString("sos_contacts", "") ?: "").lines().mapNotNull {
            val p = it.split("|"); if (p.size == 2 && p[1].isNotBlank()) EmergencyContact(p[0], p[1]) else null
        }
        set(v) { sp.edit().putString("sos_contacts", v.joinToString("\n") { "${it.name.replace("|", " ")}|${it.number}" }).apply() }

    var fallDetection by bool("fall_on", true)
    var chargeReminder by bool("charge80", true)
    var nightModeAuto by bool("night_auto", true)
    var nightModeForced by bool("night_force", false)
    var outdoorMode by bool("outdoor", false)
    var powerSaver by bool("power_saver", false)
    var lowBatteryAlerts by bool("low_batt", true)

    var appLock: AppLock
        get() = AppLock.decode(sp.getString("lock", null))
        set(v) { sp.edit().putString("lock", v.encode()).apply() }

    // --- health history (small rolling stores) ---
    var restHrHistory: List<Int>
        get() = (sp.getString("rhr_hist", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }
        set(v) { sp.edit().putString("rhr_hist", v.takeLast(14).joinToString(",")).apply() }

    /** Daily training loads, oldest first, last 28 days. Key = epoch day. */
    fun addLoad(epochDay: Long, load: Double) {
        val m = loads().toMutableMap()
        m[epochDay] = (m[epochDay] ?: 0.0) + load
        val keep = m.filterKeys { it > epochDay - 28 }
        sp.edit().putString("loads", keep.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
    }

    fun loads(): Map<Long, Double> = (sp.getString("loads", "") ?: "").split(",").mapNotNull {
        val p = it.split(":"); if (p.size == 2) (p[0].toLongOrNull() ?: return@mapNotNull null) to (p[1].toDoubleOrNull() ?: 0.0) else null
    }.toMap()

    fun loadSeries(today: Long): List<Double> { val m = loads(); return (today - 27..today).map { m[it] ?: 0.0 } }

    var lastSleepScore: Int
        get() = sp.getInt("sleep_score", -1)
        set(v) { sp.edit().putInt("sleep_score", v).apply() }

    var stepsBaseline: Long
        get() = sp.getLong("steps_base", -1)
        set(v) { sp.edit().putLong("steps_base", v).apply() }
    var stepsBaselineDay: Long
        get() = sp.getLong("steps_base_day", -1)
        set(v) { sp.edit().putLong("steps_base_day", v).apply() }

    var waypoints: List<com.harshvardhan.ultrawatch.core.Gnss.Waypoint>
        get() = com.harshvardhan.ultrawatch.core.Gnss.decodeWaypoints(sp.getString("waypoints", ""))
        set(v) { sp.edit().putString("waypoints", com.harshvardhan.ultrawatch.core.Gnss.encodeWaypoints(v.takeLast(30))).apply() }

    var phoneBattery: String
        get() = sp.getString("phone_batt", "") ?: ""
        set(v) { sp.edit().putString("phone_batt", v).apply() }

    /** Vitals log: one line per reading "type|time|v1|v2|v3". */
    fun addVital(line: String) {
        val all = (sp.getString("vitals", "") ?: "").lines().filter { it.isNotBlank() } + line
        sp.edit().putString("vitals", all.takeLast(300).joinToString("\n")).apply()
    }

    fun vitals(type: String): List<List<String>> = (sp.getString("vitals", "") ?: "").lines()
        .map { it.split("|") }.filter { it.size >= 3 && it[0] == type }

    private fun bool(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
    }

    companion object {
        private const val K_FACE = "face_cfg"
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs = inst ?: synchronized(this) { inst ?: Prefs(ctx.applicationContext).also { inst = it } }
    }
}
