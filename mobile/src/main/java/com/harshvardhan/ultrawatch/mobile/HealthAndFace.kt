package com.harshvardhan.ultrawatch.mobile

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.harshvardhan.ultrawatch.core.FaceConfig
import com.harshvardhan.ultrawatch.core.FaceStyle
import com.harshvardhan.ultrawatch.core.HealthMath
import com.harshvardhan.ultrawatch.core.SlotPosition
import com.harshvardhan.ultrawatch.core.Training
import com.harshvardhan.ultrawatch.core.WatchGeometry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads health data from Health Connect. Brand apps (realme Link, Nothing X, Honor Health,
 * Huawei Health, Zepp for Amazfit, Mi Fitness...) write here when sync is switched on in those apps.
 */
object HealthRepo {
    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    data class Summary(
        val steps: Long,
        val avgHr: Int?,
        val restingHr: Int?,
        val sleepMinutes: Int,
        val sleepScore: Int?,
        val energy: Int?,
        val sources: Set<String>,
    ) { val sleepText get() = if (sleepMinutes > 0) "${sleepMinutes / 60}h ${sleepMinutes % 60}m" else "not tracked" }

    suspend fun granted(ctx: Context): Boolean = available(ctx) &&
        HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    suspend fun summary(ctx: Context): Summary? {
        if (!granted(ctx)) return null
        val hc = HealthConnectClient.getOrCreate(ctx)
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now().atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val sources = HashSet<String>()

        val steps = runCatching {
            hc.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(startOfDay, now)))[StepsRecord.COUNT_TOTAL]
        }.getOrNull() ?: 0L

        val hr = runCatching { hc.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(now.minus(Duration.ofHours(24)), now))).records }.getOrDefault(emptyList())
        hr.forEach { sources += it.metadata.dataOrigin.packageName }
        val bpm = hr.flatMap { r -> r.samples.map { it.beatsPerMinute.toInt() } }
        val avgHr = bpm.takeIf { it.isNotEmpty() }?.average()?.toInt()

        val rhrRecords = runCatching { hc.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(14)), now))).records }.getOrDefault(emptyList())
        val restToday = rhrRecords.lastOrNull()?.beatsPerMinute?.toInt() ?: bpm.takeIf { it.size > 30 }?.sorted()?.take(10)?.average()?.toInt()
        val restBase = rhrRecords.dropLast(1).map { it.beatsPerMinute.toDouble() }.takeIf { it.isNotEmpty() }?.average()

        val sleep = runCatching { hc.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(now.minus(Duration.ofHours(20)), now))).records }.getOrDefault(emptyList())
        sleep.forEach { sources += it.metadata.dataOrigin.packageName }
        var total = 0; var deep = 0; var awake = 0
        sleep.forEach { s ->
            total += Duration.between(s.startTime, s.endTime).toMinutes().toInt()
            s.stages.forEach { st ->
                val m = Duration.between(st.startTime, st.endTime).toMinutes().toInt()
                if (st.stage == SleepSessionRecord.STAGE_TYPE_DEEP) deep += m
                if (st.stage == SleepSessionRecord.STAGE_TYPE_AWAKE) awake += m
            }
        }
        val sleepMinutes = total - awake
        // Without stage data, assume a typical 15 % deep sleep so the score isn't penalised.
        val sleepScore = if (sleepMinutes > 60) HealthMath.sleepScore(sleepMinutes, if (deep > 0) deep else (sleepMinutes * 0.15).toInt(), awake / 5) else null
        val energy = if (sleepScore != null && restToday != null) Training.energyScore(sleepScore, restToday, restBase ?: restToday.toDouble(), 0.0, 0.0) else null

        return Summary(steps, avgHr, restToday, sleepMinutes.coerceAtLeast(0), sleepScore, energy, sources)
    }

    /** Friendly names for common brand apps writing to Health Connect. */
    fun appName(pkg: String) = when {
        "realme" in pkg || "heytap" in pkg -> "realme"
        "nothing" in pkg -> "Nothing / CMF"
        "huawei" in pkg -> "Huawei Health"
        "hihonor" in pkg || "honor" in pkg -> "Honor Health"
        "zepp" in pkg || "amazfit" in pkg || "huami" in pkg -> "Zepp (Amazfit)"
        "xiaomi" in pkg || "mi.health" in pkg -> "Mi Fitness"
        "vivo" in pkg -> "vivo Health"
        "coros" in pkg -> "COROS"
        "samsung" in pkg -> "Samsung Health"
        "google" in pkg -> "Google"
        else -> pkg
    }
}

/**
 * Exports the Face Designer look as a 480x480 PNG for "photo watch face" features in
 * realme Link, Nothing X, Zepp, Huawei Health and similar apps. The watch draws the time on top,
 * so this is the dial: ticks, colours, logo and labelled widget spots.
 */
object FaceExporter {
    fun render(ctx: Context, cfg: FaceConfig, size: Int = 480): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2.0; val r = size / 2.0
        c.drawColor(cfg.background.toInt())
        if (cfg.style != FaceStyle.DIGITAL_BOLD && cfg.style != FaceStyle.TRADER) {
            for ((a, major) in WatchGeometry.ticks(cfg.tickDensity)) {
                val (x1, y1) = WatchGeometry.pointAt(cx, cx, r * if (major) 0.84 else 0.92, a)
                val (x2, y2) = WatchGeometry.pointAt(cx, cx, r * 0.97, a)
                p.color = if (major) cfg.accent.toInt() else Color.rgb(80, 80, 85)
                p.strokeWidth = if (major) size * 0.014f else size * 0.004f
                c.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), p)
            }
        } else {
            p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.012f; p.color = cfg.accent.toInt()
            c.drawCircle(cx.toFloat(), cx.toFloat(), (r * 0.94).toFloat(), p); p.style = Paint.Style.FILL
        }
        p.color = cfg.secondary.toInt(); p.alpha = 70
        for (pos in cfg.slots.keys) {
            val b = WatchGeometry.slotBounds(pos)
            c.drawCircle((b[0] + b[2]) / 2 * size, (b[1] + b[3]) / 2 * size, (b[2] - b[0]) * size / 2.4f, p)
        }
        p.alpha = 255
        if (cfg.showLogo && SlotPosition.TOP !in cfg.slots.keys) {
            BitmapFactory.decodeResource(ctx.resources, R.drawable.hvt_logo)?.let {
                val s = size * 0.16f
                c.drawBitmap(it, null, RectF(size / 2f - s / 2, size * 0.17f, size / 2f + s / 2, size * 0.17f + s), p)
            }
        }
        return bmp
    }

    /** Saves to Pictures/UltraWatch so the brand app's photo picker can find it. Returns true on success. */
    fun save(ctx: Context, cfg: FaceConfig): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ultrawatch_face_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UltraWatch")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { render(ctx, cfg).compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)
}

/** Rings at full alarm volume until stopped — triggered from the watch. */
class FindPhoneActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startRinging(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(64, 200, 64, 64); setBackgroundColor(Color.BLACK)
            addView(TextView(context).apply { text = "Found it!"; textSize = 32f; setTextColor(Color.WHITE) })
            addView(Button(context).apply { text = "Stop ringing"; setOnClickListener { finish() } })
        })
    }

    override fun onDestroy() { stopRinging(); NotificationManagerCompat.from(this).cancel(500); super.onDestroy() }

    companion object {
        @Volatile private var ringtone: Ringtone? = null

        private fun startRinging(ctx: Context) {
            if (ringtone?.isPlaying == true) return
            val am = ctx.getSystemService(AudioManager::class.java)
            runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
            ringtone = RingtoneManager.getRingtone(ctx.applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                isLooping = true
                play()
            }
        }

        fun stopRinging() { ringtone?.stop(); ringtone = null }

        /** Activities can't start from the background, so a full-screen notification opens this screen. */
        fun ring(ctx: Context) {
            startRinging(ctx) // plays straight away; the notification/screen lets you stop it
            val pi = PendingIntent.getActivity(ctx, 5, Intent(ctx, FindPhoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, PhoneNotify.CH_FIND)
                .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Your watch is looking for this phone")
                .setContentText("Tap to stop").setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX).setFullScreenIntent(pi, true).setContentIntent(pi).setAutoCancel(true).build()
            runCatching { NotificationManagerCompat.from(ctx).notify(500, n) }
        }
    }
}
