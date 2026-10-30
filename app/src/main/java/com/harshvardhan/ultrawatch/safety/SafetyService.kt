package com.harshvardhan.ultrawatch.safety

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.harshvardhan.ultrawatch.Notify
import com.harshvardhan.ultrawatch.core.BatteryMath
import com.harshvardhan.ultrawatch.core.FallDetector
import com.harshvardhan.ultrawatch.core.SafetyCheck
import com.harshvardhan.ultrawatch.data.Prefs
import com.harshvardhan.ultrawatch.health.Sensors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background guardian: fall detection, safety check-in timer and the 80 % charge reminder.
 * A detected fall opens the "Are you OK?" countdown screen; if nobody cancels, SOS is sent.
 */
class SafetyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fallJob: Job? = null
    private var checkJob: Job? = null
    private val reminder by lazy { BatteryMath.ChargeReminder(80) }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0) return
            val pct = level * 100 / scale
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (Prefs.get(c).chargeReminder && reminder.onLevel(pct, charging)) {
                Notify.post(c, Notify.CH_BATTERY, 80, "Battery at $pct%", "Unplug now to protect long-term battery health.", "battery")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Android 14 needs a sensor permission for health services; without it, stop quietly instead of crashing.
        val ok = runCatching {
            ServiceCompat.startForeground(this, 42,
                Notify.ongoing(this, Notify.CH_SAFETY, "UltraWatch safety", "Fall detection on", "safety"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        }.isSuccess
        if (!ok) { stopSelf(); return }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CHECK_START -> startCheck(intent.getLongExtra(EXTRA_MINUTES, 30))
            ACTION_CHECK_IN -> { checkIn.value?.checkIn(); checkJob?.cancel(); checkIn.value = null }
        }
        if (Prefs.get(this).fallDetection && fallJob == null) startFall()
        if (!Prefs.get(this).fallDetection) { fallJob?.cancel(); fallJob = null }
        return START_STICKY
    }

    private fun startFall() {
        val d = FallDetector()
        fallJob = scope.launch {
            Sensors.accelerometer(this@SafetyService, 20_000).collect { s ->
                if (d.onSample(s.tMs, s.values[0], s.values[1], s.values[2])) {
                    // Full-screen intent: Android blocks direct activity starts from the background.
                    Emergency.vibrateSos(this@SafetyService)
                    Notify.fullScreen(this@SafetyService, 43, "Fall detected", "Are you OK? Tap to respond.", "fall_alert")
                }
            }
        }
    }

    private fun startCheck(minutes: Long) {
        val sc = SafetyCheck(System.currentTimeMillis(), minutes * 60_000)
        checkIn.value = sc
        checkJob?.cancel()
        checkJob = scope.launch {
            var warned = false
            while (isActive) {
                when (sc.status(System.currentTimeMillis())) {
                    SafetyCheck.Status.GRACE -> if (!warned) {
                        warned = true
                        Emergency.vibrateSos(this@SafetyService)
                        Notify.post(this@SafetyService, Notify.CH_SAFETY, 44, "Safety check", "Check in now or your contacts will be alerted in 1 minute.", "safety")
                    }
                    SafetyCheck.Status.ALERT -> {
                        val loc = Emergency.currentLocation(this@SafetyService)
                        val n = Emergency.sendSos(this@SafetyService, "Missed safety check-in", loc)
                        Notify.post(this@SafetyService, Notify.CH_SAFETY, 45, "Safety alert sent",
                            if (n > 0) "Sent to $n contact(s)." else "Couldn't send SMS from the watch. Open UltraWatch to share your location.", "safety")
                        checkIn.value = null
                        return@launch
                    }
                    else -> {}
                }
                delay(5_000)
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CHECK_START = "check_start"
        const val ACTION_CHECK_IN = "check_in"
        const val EXTRA_MINUTES = "minutes"
        val checkIn = MutableStateFlow<SafetyCheck?>(null)

        fun ensureRunning(ctx: Context) = ctx.startForegroundService(Intent(ctx, SafetyService::class.java))
        fun startCheck(ctx: Context, minutes: Long) =
            ctx.startForegroundService(Intent(ctx, SafetyService::class.java).setAction(ACTION_CHECK_START).putExtra(EXTRA_MINUTES, minutes))
        fun checkIn(ctx: Context) = ctx.startService(Intent(ctx, SafetyService::class.java).setAction(ACTION_CHECK_IN))
    }
}
