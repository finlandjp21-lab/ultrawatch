package com.harshvardhan.ultrawatch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.harshvardhan.ultrawatch.markets.MarketAlertWorker

class UltraWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notify.createChannels(this)
        MarketAlertWorker.schedule(this)
        // Print the credit once per process start (visible in logcat / startup banner).
        android.util.Log.i("UltraWatch", "UltraWatch ${BuildConfig.VERSION_NAME} - Created by Harshvardhan Technologies")
    }
}

object Notify {
    const val CH_ALERTS = "alerts"
    const val CH_SESSION = "session"
    const val CH_SAFETY = "safety"
    const val CH_BATTERY = "battery"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_ALERTS, "Market alerts", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_SESSION, "Workouts and tracking", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_SAFETY, "Safety and SOS", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_BATTERY, "Battery", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    fun openAppIntent(ctx: Context, route: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        route?.let { i.putExtra(MainActivity.EXTRA_ROUTE, it) }
        return PendingIntent.getActivity(ctx, route.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun post(ctx: Context, channel: String, id: Int, title: String, text: String, route: String? = null) {
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, route))
            .setPriority(if (channel == CH_SAFETY || channel == CH_ALERTS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) { /* notifications not allowed */ }
    }

    fun fullScreen(ctx: Context, id: Int, title: String, text: String, route: String) {
        val pi = openAppIntent(ctx, route)
        val n = NotificationCompat.Builder(ctx, CH_SAFETY)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }

    fun ongoing(ctx: Context, channel: String, title: String, text: String, route: String?) =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent(ctx, route))
            .build()
}
