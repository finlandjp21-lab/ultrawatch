package com.harshvardhan.ultrawatch.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.harshvardhan.ultrawatch.core.AlertEngine
import com.harshvardhan.ultrawatch.core.BatteryProtocol
import com.harshvardhan.ultrawatch.core.DemoMarketFeed
import com.harshvardhan.ultrawatch.core.FaceConfig
import com.harshvardhan.ultrawatch.core.Quote
import com.harshvardhan.ultrawatch.core.QuoteParser
import com.harshvardhan.ultrawatch.core.Training
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneNotify.channels(this)
        val wm = WorkManager.getInstance(this)
        wm.enqueueUniquePeriodicWork("battery_sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BatterySyncWorker>(15, TimeUnit.MINUTES).build())
        wm.enqueueUniquePeriodicWork("market_alerts", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MarketWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        wm.enqueueUniquePeriodicWork("energy_brief", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EnergyWorker>(6, TimeUnit.HOURS).build())
        android.util.Log.i("UltraWatch", "UltraWatch phone companion - Created by Harshvardhan Technologies")
    }
}

/**
 * Notifications from the phone app. Any watch that mirrors phone notifications
 * (Realme, Nothing/CMF, Honor, Vivo, Huawei, Amazfit, Coros, Mi Band...) shows them on the wrist.
 */
object PhoneNotify {
    const val CH_WRIST = "wrist"
    const val CH_FIND = "find"

    fun channels(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CH_WRIST, "Watch alerts (markets, energy, safety)", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_FIND, "Find my phone", NotificationManager.IMPORTANCE_HIGH),
        ))
    }

    fun wrist(ctx: Context, id: Int, title: String, text: String) {
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, PhoneActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_WRIST)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}

/** Phone-side settings (separate from the watch's). */
class PhonePrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("ultrawatch_phone", Context.MODE_PRIVATE)
    var marketEndpoint: String get() = sp.getString("mkt_url", "") ?: ""; set(v) { sp.edit().putString("mkt_url", v.trim()).apply() }
    var alerts: AlertEngine get() = AlertEngine.decode(sp.getString("alerts", "") ?: ""); set(v) { sp.edit().putString("alerts", v.encode()).apply() }
    var face: FaceConfig get() = FaceConfig.decode(sp.getString("face", null)); set(v) { sp.edit().putString("face", v.encode()).apply() }
    var wristMarkets: Boolean get() = sp.getBoolean("w_mkt", true); set(v) { sp.edit().putBoolean("w_mkt", v).apply() }
    var wristEnergy: Boolean get() = sp.getBoolean("w_energy", true); set(v) { sp.edit().putBoolean("w_energy", v).apply() }
    var lastEnergyDay: Long get() = sp.getLong("energy_day", -1); set(v) { sp.edit().putLong("energy_day", v).apply() }
    var sosNumber: String get() = sp.getString("sos", "") ?: ""; set(v) { sp.edit().putString("sos", v.trim()).apply() }
    var name: String get() = sp.getString("name", "") ?: ""; set(v) { sp.edit().putString("name", v.trim()).apply() }
}

object PhoneLink {
    fun battery(ctx: Context): Triple<Int, Boolean, Long>? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return Triple(level * 100 / scale, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL, System.currentTimeMillis())
    }

    /** Sends this phone's battery to every connected Wear OS watch running UltraWatch. Returns watches reached. */
    suspend fun sendBattery(ctx: Context): Int {
        val b = battery(ctx) ?: return 0
        val payload = BatteryProtocol.encode(b.first, b.second, b.third)
        return runCatching {
            val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
            nodes.forEach { Wearable.getMessageClient(ctx).sendMessage(it.id, BatteryProtocol.PATH_PHONE_BATTERY, payload).await() }
            nodes.size
        }.getOrDefault(0)
    }
}

class WatchLinkService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        when (e.path) {
            BatteryProtocol.PATH_REQUEST_BATTERY -> kotlinx.coroutines.runBlocking { PhoneLink.sendBattery(this@WatchLinkService) }
            BatteryProtocol.PATH_FIND_PHONE -> FindPhoneActivity.ring(this)
        }
    }
}

class BatterySyncWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result { PhoneLink.sendBattery(applicationContext); return Result.success() }
}

object PhoneMarkets {
    private val demo = DemoMarketFeed(System.currentTimeMillis())
    suspend fun fetch(ctx: Context): Pair<List<Quote>, Boolean> = withContext(Dispatchers.IO) {
        val url = PhonePrefs(ctx).marketEndpoint
        if (url.isBlank()) return@withContext demo.tick() to true
        runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 8_000 }
            val body = c.inputStream.bufferedReader().use { it.readText() }; c.disconnect()
            QuoteParser.parse(body) to false
        }.getOrElse { emptyList<Quote>() to false }
    }

    /** Evaluates alerts and posts hits as notifications that mirror to any watch. */
    fun check(ctx: Context, quotes: List<Quote>) {
        val p = PhonePrefs(ctx)
        val e = p.alerts
        val hits = e.evaluate(quotes)
        p.alerts = e
        if (p.wristMarkets) hits.forEachIndexed { i, h -> PhoneNotify.wrist(ctx, 300 + i, "UltraWatch alert", h.message) }
    }
}

class MarketWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        if (PhonePrefs(applicationContext).alerts.alerts.isEmpty()) return Result.success()
        val (q, _) = PhoneMarkets.fetch(applicationContext)
        PhoneMarkets.check(applicationContext, q)
        return Result.success()
    }
}

/** Morning energy brief from Health Connect data, sent once per day as a wrist notification. */
class EnergyWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = PhonePrefs(ctx)
        val today = java.time.LocalDate.now().toEpochDay()
        val hour = java.time.LocalTime.now().hour
        if (!prefs.wristEnergy || prefs.lastEnergyDay == today || hour !in 6..11) return Result.success()
        val s = HealthRepo.summary(ctx) ?: return Result.success()
        val score = s.energy ?: return Result.success()
        PhoneNotify.wrist(ctx, 400, "Energy $score/100", "${Training.energyAdvice(score)} · sleep ${s.sleepText}")
        prefs.lastEnergyDay = today
        return Result.success()
    }
}
