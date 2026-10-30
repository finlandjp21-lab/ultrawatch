package com.harshvardhan.ultrawatch.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.harshvardhan.ultrawatch.data.Prefs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import java.time.LocalDate

/** Thin, flow-based wrappers around the platform sensors that every Wear OS watch exposes. */
object Sensors {
    data class Sample(val tMs: Long, val values: FloatArray, val accuracy: Int)

    fun has(ctx: Context, type: Int) = ctx.getSystemService(SensorManager::class.java).getDefaultSensor(type) != null

    fun flow(ctx: Context, type: Int, rateUs: Int = SensorManager.SENSOR_DELAY_NORMAL): Flow<Sample> {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(type) ?: return emptyFlow()
        return callbackFlow {
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    trySend(Sample(System.currentTimeMillis(), e.values.copyOf(), e.accuracy))
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.registerListener(l, sensor, rateUs)
            awaitClose { sm.unregisterListener(l) }
        }
    }

    /** Heart rate in bpm; drops unreliable/zero readings. */
    fun heartRate(ctx: Context) = flow(ctx, Sensor.TYPE_HEART_RATE)

    fun stepCounter(ctx: Context) = flow(ctx, Sensor.TYPE_STEP_COUNTER)

    fun accelerometer(ctx: Context, rateUs: Int = 40_000) = flow(ctx, Sensor.TYPE_ACCELEROMETER, rateUs)

    fun pressure(ctx: Context) = flow(ctx, Sensor.TYPE_PRESSURE)

    fun heading(ctx: Context) = flow(ctx, Sensor.TYPE_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_UI)

    /** Converts a rotation vector sample to compass heading degrees 0..360. */
    fun headingDeg(rv: FloatArray): Double {
        val rot = FloatArray(9); val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rot, rv)
        SensorManager.getOrientation(rot, o)
        return (Math.toDegrees(o[0].toDouble()) + 360) % 360
    }

    /**
     * TYPE_STEP_COUNTER counts since boot. Store the first reading of each day so
     * "today's steps" survives app restarts; resets after a reboot are handled.
     */
    fun stepsToday(ctx: Context, counterTotal: Long): Long {
        val p = Prefs.get(ctx)
        val today = LocalDate.now().toEpochDay()
        if (p.stepsBaselineDay != today || p.stepsBaseline < 0 || counterTotal < p.stepsBaseline) {
            p.stepsBaseline = counterTotal
            p.stepsBaselineDay = today
        }
        return counterTotal - p.stepsBaseline
    }
}
