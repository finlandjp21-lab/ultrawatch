package com.harshvardhan.ultrawatch.safety

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.harshvardhan.ultrawatch.core.SosMessage
import com.harshvardhan.ultrawatch.data.Prefs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.sin

/** SOS actions: location, SMS to emergency contacts, emergency call, siren. */
object Emergency {

    fun has(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(ctx: Context, timeoutMs: Long = 15_000): Location? {
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val provider = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> LocationManager.FUSED_PROVIDER
                }
                try {
                    lm.getCurrentLocation(provider, null, Executors.newSingleThreadExecutor()) { if (cont.isActive) cont.resume(it) }
                } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
            }
        }
        return fresh ?: listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /** Texts every emergency contact. Returns how many messages were handed to the SMS system. */
    fun sendSos(ctx: Context, reason: String, loc: Location?): Int {
        val p = Prefs.get(ctx)
        val msg = SosMessage.build(p.profile.name, reason, loc?.latitude, loc?.longitude, loc?.accuracy)
        if (!has(ctx, Manifest.permission.SEND_SMS)) return 0
        val sms = ctx.getSystemService(SmsManager::class.java) ?: return 0
        var sent = 0
        for (c in p.emergencyContacts) {
            try {
                val parts = sms.divideMessage(msg)
                sms.sendMultipartTextMessage(c.number, null, parts, null, null)
                sent++
            } catch (_: Exception) { /* no SIM / no telephony: watch without LTE */ }
        }
        return sent
    }

    /** Opens a share sheet / messaging with the location link when SMS isn't available on the watch. */
    fun shareLocationIntent(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    fun callIntent(ctx: Context, number: String): Intent =
        if (has(ctx, Manifest.permission.CALL_PHONE)) Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        else Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))

    fun vibrateSos(ctx: Context) {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        val sos = longArrayOf(0, 150, 100, 150, 100, 150, 300, 450, 100, 450, 100, 450, 300, 150, 100, 150, 100, 150)
        v.vibrate(VibrationEffect.createWaveform(sos, -1))
    }

    // ---- Siren: synthesised two-tone wail at full alarm volume through the watch speaker ----
    @Volatile private var siren: AudioTrack? = null

    val sirenOn get() = siren != null

    fun startSiren(ctx: Context) {
        if (siren != null) return
        val am = ctx.getSystemService(AudioManager::class.java)
        try { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) } catch (_: Exception) {}
        val rate = 22_050
        val seconds = 2
        val pcm = ShortArray(rate * seconds)
        var phase = 0.0
        for (i in pcm.indices) {
            val t = i.toDouble() / rate
            // sweep 900 Hz -> 1800 Hz -> 900 Hz every 2 s (most audible range for small speakers)
            val f = 900 + 900 * (1 - kotlin.math.abs((t % 2.0) - 1.0))
            phase += 2 * PI * f / rate
            pcm[i] = (sin(phase) * Short.MAX_VALUE * 0.95).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        track.setLoopPoints(0, pcm.size, -1)
        track.play()
        siren = track
    }

    fun stopSiren() {
        siren?.let { runCatching { it.stop() }; it.release() }
        siren = null
    }

    fun hasSpeaker(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)
}
