package com.harshvardhan.ultrawatch.health

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.harshvardhan.ultrawatch.Notify
import com.harshvardhan.ultrawatch.core.Geo
import com.harshvardhan.ultrawatch.core.HealthMath
import com.harshvardhan.ultrawatch.core.Multisport
import com.harshvardhan.ultrawatch.core.TrackBack
import com.harshvardhan.ultrawatch.core.Training
import com.harshvardhan.ultrawatch.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Live state of a workout or sleep session, observed by the UI. */
data class SessionState(
    val active: Boolean = false,
    val mode: String = "",          // workout type, "Multisport" or "Sleep"
    val startedAt: Long = 0,
    val elapsedMs: Long = 0,
    val bpm: Int = 0,
    val avgBpm: Int = 0,
    val maxBpm: Int = 0,
    val zone: Int = 0,
    val calories: Double = 0.0,
    val distanceM: Double = 0.0,
    val pace: String = "--:--",
    val leg: String = "",
    val legs: List<Long> = emptyList(),
    val movementEvents: Int = 0,
    val gpsFix: Boolean = false,
    val route: List<Geo.Pt> = emptyList(),
    val summary: String? = null,
)

/**
 * Foreground service for workouts (with GPS route + Track Back + multisport) and sleep tracking.
 * Keeps sensors alive while the screen is off.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobs = mutableListOf<Job>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationListener: LocationListener? = null

    private val hrSamples = ArrayList<Int>()
    private var multisport: Multisport? = null
    private val sleepMinutes = ArrayList<Int>()
    private var minuteMoves = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_MODE) ?: "Run")
            ACTION_NEXT_LEG -> nextLeg()
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start(mode: String) {
        if (_state.value.active) return
        val sleep = mode == "Sleep"
        val outdoor = mode in setOf("Run", "Walk", "Cycle", "Hike", "Multisport")
        val type = if (outdoor) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        val started = runCatching {
            ServiceCompat.startForeground(this, 41, Notify.ongoing(this, Notify.CH_SESSION, "UltraWatch", "$mode in progress", "workout"), type)
        }.isSuccess
        if (!started) { stopSelf(); return }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ultrawatch:session").apply { acquire(12 * 60 * 60_000L) }

        val now = System.currentTimeMillis()
        hrSamples.clear(); sleepMinutes.clear(); minuteMoves = 0
        trackBack = TrackBack()
        multisport = if (mode == "Multisport") Multisport().also { it.start(now) } else null
        _state.value = SessionState(active = true, mode = mode, startedAt = now, leg = multisport?.currentName ?: "")

        val profile = Prefs.get(this).profile
        jobs += scope.launch {
            Sensors.heartRate(this@SessionService).collect { s ->
                val bpm = s.values[0].toInt()
                if (bpm in 30..230) {
                    hrSamples += bpm
                    val st = _state.value
                    val kcalPerSec = HealthMath.caloriesPerMinute(bpm, profile.age, profile.weightKg.toDouble(),
                        if (profile.male) HealthMath.Sex.MALE else HealthMath.Sex.FEMALE) / 60.0
                    _state.value = st.copy(
                        bpm = bpm, avgBpm = hrSamples.average().toInt(), maxBpm = maxOf(st.maxBpm, bpm),
                        zone = HealthMath.heartRateZone(bpm, profile.age),
                        calories = if (sleep) st.calories else st.calories + kcalPerSec,
                    )
                }
            }
        }
        if (sleep) {
            jobs += scope.launch {
                Sensors.accelerometer(this@SessionService, 200_000).collect { s ->
                    if (HealthMath.isMovement(s.values[0], s.values[1], s.values[2])) minuteMoves++
                }
            }
            jobs += scope.launch {
                while (isActive) {
                    delay(60_000)
                    sleepMinutes += minuteMoves.coerceAtMost(30); minuteMoves = 0
                    _state.value = _state.value.copy(movementEvents = sleepMinutes.count { it > 0 })
                }
            }
        }
        jobs += scope.launch {
            while (isActive) {
                val st = _state.value
                val elapsed = System.currentTimeMillis() - st.startedAt
                _state.value = st.copy(
                    elapsedMs = elapsed,
                    pace = Geo.pace(st.distanceM, elapsed),
                    legs = multisport?.legDurations(System.currentTimeMillis()) ?: emptyList(),
                )
                delay(1_000)
            }
        }
        if (outdoor) startLocation()
    }

    @SuppressLint("MissingPermission") // checked by the UI before starting
    private fun startLocation() {
        val lm = getSystemService(LocationManager::class.java)
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.FUSED_PROVIDER
        val l = LocationListener { loc: Location ->
            val p = Geo.Pt(loc.latitude, loc.longitude, loc.time)
            val tb = trackBack ?: return@LocationListener
            if (tb.record(p, loc.accuracy)) {
                _state.value = _state.value.copy(distanceM = Geo.pathLength(tb.points), route = tb.points.toList(), gpsFix = true)
            } else {
                _state.value = _state.value.copy(gpsFix = loc.accuracy <= 35f)
            }
            lastLocation.value = p
        }
        locationListener = l
        try { lm.requestLocationUpdates(provider, 2_000L, 0f, l, Looper.getMainLooper()) } catch (_: Exception) {}
    }

    private fun nextLeg() {
        val m = multisport ?: return
        m.next(System.currentTimeMillis())
        _state.value = _state.value.copy(leg = if (m.finishedAt >= 0) "Finished" else m.currentName)
        if (m.finishedAt >= 0) stop()
    }

    private fun stop() {
        val st = _state.value
        if (!st.active) { stopSelf(); return }
        val p = Prefs.get(this)
        val prof = p.profile
        val minutes = st.elapsedMs / 60_000.0
        val summary = if (st.mode == "Sleep") {
            val total = sleepMinutes.size
            val deep = HealthMath.deepMinutesFromMovement(sleepMinutes)
            val score = HealthMath.sleepScore(total, deep, sleepMinutes.sum())
            if (total >= 60) p.lastSleepScore = score
            // lowest 5-minute average HR overnight is a good resting-HR estimate
            if (hrSamples.size > 30) {
                val rest = hrSamples.windowed(30, 30).minOf { it.average() }.toInt()
                p.restHrHistory = p.restHrHistory + rest
                p.profile = prof.copy(restingHr = rest)
            }
            "Sleep ${total / 60}h ${total % 60}m · deep ${deep}m · score $score"
        } else {
            val load = Training.trimp(minutes, st.avgBpm, prof.restingHr, HealthMath.maxHeartRate(prof.age), prof.male)
            p.addLoad(LocalDate.now().toEpochDay(), load)
            "${st.mode} ${minutes.toInt()} min · ${st.calories.toInt()} kcal · ${Geo.formatDistance(st.distanceM)} · load ${load.toInt()}"
        }
        _state.value = st.copy(active = false, summary = summary)
        cleanup()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        jobs.forEach { it.cancel() }; jobs.clear()
        locationListener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        locationListener = null
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    override fun onDestroy() { cleanup(); scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_NEXT_LEG = "next_leg"
        const val EXTRA_MODE = "mode"

        private val _state = MutableStateFlow(SessionState())
        val state: StateFlow<SessionState> = _state
        val lastLocation = MutableStateFlow<Geo.Pt?>(null)
        /** Route of the current/last outdoor session, used by Track Back. */
        @Volatile var trackBack: TrackBack? = null

        fun start(ctx: Context, mode: String) = ctx.startForegroundService(Intent(ctx, SessionService::class.java).setAction(ACTION_START).putExtra(EXTRA_MODE, mode))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, SessionService::class.java).setAction(ACTION_STOP))
        fun nextLeg(ctx: Context) = ctx.startService(Intent(ctx, SessionService::class.java).setAction(ACTION_NEXT_LEG))
    }
}
