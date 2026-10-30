package com.harshvardhan.ultrawatch.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.harshvardhan.ultrawatch.core.Gnss
import kotlinx.coroutines.flow.MutableStateFlow

/** Live GPS status for the GPS screen: fix, satellites by constellation, frequency bands, speed. */
@SuppressLint("MissingPermission") // the GPS screen requests location permission first
class GpsMonitor(ctx: Context) {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    val location = MutableStateFlow<Location?>(null)
    val summary = MutableStateFlow(Gnss.summarize(emptyList()))
    val enabled get() = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

    private val locListener = LocationListener { location.value = it }

    private val gnssCb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            val sats = (0 until s.satelliteCount).map { i ->
                Gnss.Sat(
                    s.getConstellationType(i), s.getSvid(i), s.getCn0DbHz(i), s.usedInFix(i),
                    if (s.hasCarrierFrequencyHz(i)) s.getCarrierFrequencyHz(i) else null,
                )
            }
            summary.value = Gnss.summarize(sats)
        }
    }

    fun start() {
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locListener, Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnssCb, Handler(Looper.getMainLooper()))
            location.value = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }
    }

    fun stop() {
        runCatching { lm.removeUpdates(locListener); lm.unregisterGnssStatusCallback(gnssCb) }
    }
}
