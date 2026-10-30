package com.harshvardhan.ultrawatch.ui.screens

import android.Manifest
import android.hardware.Sensor
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bloodtype
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.harshvardhan.ultrawatch.complication.EnergyCalc
import com.harshvardhan.ultrawatch.core.Environment
import com.harshvardhan.ultrawatch.core.Geo
import com.harshvardhan.ultrawatch.core.HealthMath
import com.harshvardhan.ultrawatch.core.Training
import com.harshvardhan.ultrawatch.core.Vitals
import com.harshvardhan.ultrawatch.data.Prefs
import com.harshvardhan.ultrawatch.health.BleVitals
import com.harshvardhan.ultrawatch.health.SessionService
import com.harshvardhan.ultrawatch.health.Sensors
import com.harshvardhan.ultrawatch.ui.*
import java.time.LocalDate

@Composable
fun HealthScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val profile = remember { Prefs.get(ctx).profile }
    var bpm by remember { mutableIntStateOf(0) }
    var steps by remember { mutableLongStateOf(-1L) }
    val hrSamples = remember { mutableStateListOf<Int>() }
    var breathing by remember { mutableStateOf(false) }

    val gate = rememberPermissionGate(arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION), then = {})
    LaunchedEffect(Unit) { gate() }
    LaunchedEffect(Unit) {
        Sensors.heartRate(ctx).collect { s -> val v = s.values[0].toInt(); if (v in 30..230) { bpm = v; hrSamples += v; if (hrSamples.size > 120) hrSamples.removeAt(0) } }
    }
    LaunchedEffect(Unit) { Sensors.stepCounter(ctx).collect { steps = Sensors.stepsToday(ctx, it.values[0].toLong()) } }

    val stress = HealthMath.stressScore(HealthMath.bpmToRr(hrSamples))
    val zone = HealthMath.heartRateZone(bpm, profile.age)

    AppScreen {
        item { BigValue(if (bpm > 0) "$bpm" else "--", "bpm", Brand.Red, "Heart rate") }
        item { InfoText("Zone $zone · ${HealthMath.zoneName(zone)}") }
        if (!Sensors.has(ctx, Sensor.TYPE_HEART_RATE)) item { InfoText("This watch has no heart-rate sensor. Connect a chest strap in Vitals.") }
        item { Stat("Steps", if (steps >= 0) "%,d".format(steps) else "--") }
        item { Stat("Distance", if (steps >= 0) Geo.formatDistance(HealthMath.stepsToKm(steps, profile.heightCm.toDouble()) * 1000) else "--") }
        item { Stat("Stress", HealthMath.stressLabel(stress) + (stress?.let { " ($it)" } ?: "")) }
        item { Stat("VO2 max est.", HealthMath.vo2MaxEstimate(profile.restingHr, profile.age)?.toString() ?: "--") }
        item { Stat("Resting HR", "${profile.restingHr} bpm") }
        item { InfoText("Stress uses beat-to-beat changes estimated from heart rate; a chest strap in Vitals gives a more exact reading.") }
        item { ActionChip(if (breathing) "Stop breathing" else "Breathe · 1 min", "6 breaths per minute") { breathing = !breathing } }
        if (breathing) item { BreathPacer() }
        item { NavChip("Energy score", Icons.Outlined.Bolt, Brand.Orange) { go("energy") } }
        item { NavChip("Vitals devices", Icons.Outlined.Bloodtype, Brand.Red) { go("vitals") } }
    }
}

@Composable
private fun BreathPacer() {
    val t = rememberInfiniteTransition(label = "breath")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(5_000, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(90.dp)) {
            drawCircle(Brand.Cyan.copy(alpha = 0.25f + phase * 0.5f), radius = size.minDimension / 2 * (0.4f + 0.6f * phase))
        }
        Text(if (phase < 0.5f) "Breathe in" else "Breathe out", fontSize = 12.sp)
    }
}

@Composable
fun EnergyScreen() {
    val ctx = LocalContext.current
    val p = remember { Prefs.get(ctx) }
    val score = remember { EnergyCalc.score(ctx) }
    val ratio = remember { Training.loadRatio(p.loadSeries(LocalDate.now().toEpochDay())) }
    val todayLoad = remember { p.loadSeries(LocalDate.now().toEpochDay()).last() }
    AppScreen("Energy") {
        item { BigValue(score?.toString() ?: "--", "/100", Brand.Orange) }
        item { InfoText(score?.let { Training.energyAdvice(it) } ?: "Wear the watch to bed with Sleep tracking on to get your first score.") }
        item { Stat("Last sleep score", p.lastSleepScore.takeIf { it >= 0 }?.toString() ?: "--") }
        item { Stat("Resting HR", "${p.profile.restingHr} bpm") }
        item { Stat("Today's cardio load", todayLoad.toInt().toString()) }
        item { Stat("Load ratio (7/28 d)", ratio?.toString() ?: "--") }
        item { InfoText(Training.loadStatus(ratio)) }
        item { InfoText("Energy = sleep (45%) + resting heart rate vs your 2-week normal (35%) + yesterday's training (20%).") }
    }
}

@Composable
fun WorkoutScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val st by SessionService.state.collectAsState()
    var pendingMode by remember { mutableStateOf("Run") }
    val perms = buildList {
        add(Manifest.permission.BODY_SENSORS); add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val start = rememberPermissionGate(perms, then = { SessionService.start(ctx, pendingMode) })

    AppScreen(if (st.active) st.mode else "Workout") {
        if (st.active && st.mode != "Sleep") {
            item { BigValue(fmt(st.elapsedMs), color = Brand.Green) }
            if (st.leg.isNotEmpty()) item { InfoText("Leg: ${st.leg}", Brand.Orange) }
            item { Stat("Heart rate", if (st.bpm > 0) "${st.bpm} bpm · Z${st.zone}" else "--") }
            item { Stat("Calories", "${st.calories.toInt()} kcal") }
            item { Stat("Distance", Geo.formatDistance(st.distanceM)) }
            item { Stat("Pace", "${st.pace} /km") }
            item { Stat("GPS", if (st.gpsFix) "Locked" else "Searching") }
            if (st.mode == "Multisport") item { ActionChip("Next leg", "Swim → Cycle → Run", primary = true) { SessionService.nextLeg(ctx) } }
            item { ActionChip("Track Back", "Guide me to the start") { go("trackback") } }
            item { ActionChip("Stop and save", primary = st.mode != "Multisport") { SessionService.stop(ctx) } }
        } else {
            st.summary?.let { s -> item { InfoText(s, Brand.Green) } }
            listOf("Run", "Walk", "Cycle", "Hike", "Multisport", "Gym", "Yoga", "Swim").forEach { m ->
                item {
                    ActionChip(m, when (m) {
                        "Multisport" -> "Triathlon: swim, cycle, run"
                        "Run", "Walk", "Cycle", "Hike" -> "GPS route + Track Back"
                        "Swim" -> "Use water lock in the pool"
                        else -> "Heart rate and calories"
                    }) { pendingMode = m; start() }
                }
            }
        }
    }
}

private fun fmt(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60) }

@Composable
fun SleepScreen() {
    val ctx = LocalContext.current
    val st by SessionService.state.collectAsState()
    val start = rememberPermissionGate(arrayOf(Manifest.permission.BODY_SENSORS), then = { SessionService.start(ctx, "Sleep") })
    val p = remember { Prefs.get(ctx) }
    AppScreen("Sleep") {
        if (st.active && st.mode == "Sleep") {
            item { BigValue(fmt(st.elapsedMs), color = Brand.Purple, label = "Sleeping") }
            item { Stat("Restless minutes", st.movementEvents.toString()) }
            item { ActionChip("I'm awake", "Stop and score", primary = true) { SessionService.stop(ctx) } }
        } else {
            item { BigValue(p.lastSleepScore.takeIf { it >= 0 }?.toString() ?: "--", "score", Brand.Purple, "Last night") }
            st.summary?.takeIf { it.startsWith("Sleep") }?.let { s -> item { InfoText(s) } }
            item { ActionChip("Start sleep tracking", "Tap when you go to bed", primary = true) { start() } }
            item { InfoText("Uses movement and heart rate. For apnea screening, wear a Bluetooth pulse oximeter overnight (Vitals).") }
        }
    }
}

@Composable
fun VitalsScreen() {
    val ctx = LocalContext.current
    val p = remember { Prefs.get(ctx) }
    val found by BleVitals.found.collectAsState()
    val status by BleVitals.status.collectAsState()
    val hr by BleVitals.liveHr.collectAsState()
    val rr by BleVitals.rrIntervals.collectAsState()
    val spo2 by BleVitals.lastSpo2.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var manualSys by remember { mutableIntStateOf(120) }
    var manualDia by remember { mutableIntStateOf(80) }
    val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val scan = rememberPermissionGate(perms, then = { BleVitals.scan(ctx) })
    DisposableEffect(Unit) { onDispose { BleVitals.stopScan(ctx) } }

    val bp = remember(refresh, status) { p.vitals("bp").lastOrNull() }
    val body = remember(refresh, status) { p.vitals("body").lastOrNull() }
    val prof = p.profile

    AppScreen("Vitals") {
        item { InfoText("Readings come from standard Bluetooth health devices or your own entry. Screening only, not a diagnosis.") }
        item { ActionChip("Find devices", status, primary = true) { scan() } }
        found.forEach { f -> item { ActionChip(f.name, f.kind.label) { BleVitals.connect(ctx, f) } } }

        // Blood pressure
        item { Text("Blood pressure", fontSize = 14.sp) }
        if (bp != null) {
            val s = bp[2].toInt(); val d = bp[3].toInt()
            val cat = Vitals.bpCategory(s, d)
            item { BigValue("$s/$d", "mmHg", if (cat.ordinal >= 3) Brand.Red else Brand.Green) }
            item { InfoText("${cat.label}. ${Vitals.bpAdvice(cat)}") }
        } else item { InfoText("No reading yet") }
        item { Stepper("Manual systolic", manualSys, 70..250) { manualSys = it } }
        item { Stepper("Manual diastolic", manualDia, 40..150) { manualDia = it } }
        item {
            ActionChip("Save manual reading") {
                if (Vitals.isPlausibleBp(manualSys, manualDia)) { p.addVital("bp|${System.currentTimeMillis()}|$manualSys|$manualDia|"); refresh++ }
            }
        }

        // SpO2 and apnea screening
        item { Text("Blood oxygen", fontSize = 14.sp) }
        item { Stat("SpO2", spo2?.let { "${it.first}% · ${Vitals.spo2Label(it.first)}" } ?: "Connect an oximeter") }
        val odi = Vitals.odi(BleVitals.overnightSpo2)
        item { Stat("Overnight ODI", odi?.let { "$it /h" } ?: "--") }
        item { InfoText(Vitals.apneaScreen(odi)) }

        // Rhythm from chest strap RR intervals
        item { Text("Heart rhythm", fontSize = 14.sp) }
        val rhythm = Vitals.rhythm(rr)
        item { Stat("Strap HR", hr?.let { "$it bpm" } ?: "Connect a strap") }
        item { Stat("HRV (RMSSD)", if (rhythm.beats > 1) "${rhythm.rmssd} ms" else "--") }
        item {
            InfoText(when (rhythm.irregular) {
                null -> "Sit still for 1 minute with the strap on (${rhythm.beats}/60 beats)."
                true -> "Irregular rhythm pattern noticed. This isn't an ECG; please see a doctor if it repeats."
                false -> "Regular rhythm pattern."
            }, if (rhythm.irregular == true) Brand.Red else Brand.Grey)
        }

        // Body composition
        item { Text("Body composition", fontSize = 14.sp) }
        if (body != null) {
            item { Stat("Body fat (scale)", "${body[2]}%") }
            body.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { m -> item { Stat("Muscle (scale)", "$m kg") } }
            body.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { w -> item { Stat("Water (scale)", "$w kg") } }
        }
        val bmi = Vitals.bmi(prof.weightKg.toDouble(), prof.heightCm.toDouble())
        item { Stat("BMI", "%.1f · %s".format(bmi, Vitals.bmiLabel(bmi))) }
        item { Stat("Body fat est.", "${Vitals.bodyFatPct(prof.weightKg.toDouble(), prof.heightCm.toDouble(), prof.age, prof.sex)}%") }
        item { Stat("Lean mass est.", "${Vitals.leanMassKg(prof.weightKg.toDouble(), prof.heightCm.toDouble(), prof.sex)} kg") }
        item { Stat("Body water est.", "${Vitals.bodyWaterL(prof.weightKg.toDouble(), prof.heightCm.toDouble(), prof.age, prof.sex)} L") }
        item { Stat("Resting energy", "${Vitals.bmr(prof.weightKg.toDouble(), prof.heightCm.toDouble(), prof.age, prof.sex)} kcal") }
        item { InfoText("Estimates use published formulas (Deurenberg, Boer, Watson, Mifflin-St Jeor) from your profile.") }
    }
}

@Composable
fun WaterScreen(onLock: () -> Unit) {
    val ctx = LocalContext.current
    var surface by remember { mutableStateOf<Float?>(null) }
    var pressure by remember { mutableStateOf<Float?>(null) }
    var salt by remember { mutableStateOf(false) }
    val hasBaro = remember { Sensors.has(ctx, Sensor.TYPE_PRESSURE) }
    LaunchedEffect(Unit) { Sensors.pressure(ctx).collect { pressure = it.values[0] } }
    AppScreen("Water") {
        item { ActionChip("Water lock", "Press the side button to unlock", primary = true) { onLock() } }
        if (hasBaro) {
            item { Stat("Pressure", pressure?.let { "%.1f hPa".format(it) } ?: "--") }
            item { Stat("Altitude", pressure?.let { "${Environment.altitudeM(it).toInt()} m" } ?: "--") }
            item { ActionChip(if (surface == null) "Set surface level" else "Reset surface", "Tap just before entering the water") { surface = pressure } }
            item { Toggle("Sea water", salt) { salt = it } }
            val depth = if (surface != null && pressure != null) Environment.depthM(pressure!!, surface!!, salt) else null
            item { BigValue(depth?.toString() ?: "--", "m", Brand.Cyan, "Depth estimate") }
            item { InfoText("Estimate from the pressure sensor; not a dive computer. Check your watch's water rating before swimming.", Color(0xFFFFB000)) }
        } else item { InfoText("This watch has no pressure sensor, so depth isn't available. Water lock still works.") }
    }
}
