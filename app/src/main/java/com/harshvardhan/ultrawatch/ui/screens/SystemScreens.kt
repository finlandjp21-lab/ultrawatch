package com.harshvardhan.ultrawatch.ui.screens

import android.Manifest
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.harshvardhan.ultrawatch.BuildConfig
import com.harshvardhan.ultrawatch.battery.Batteries
import com.harshvardhan.ultrawatch.comms.MessageStore
import com.harshvardhan.ultrawatch.core.AppLock
import com.harshvardhan.ultrawatch.core.DeviceBattery
import com.harshvardhan.ultrawatch.core.FaceConfig
import com.harshvardhan.ultrawatch.core.FaceStyle
import com.harshvardhan.ultrawatch.core.SlotData
import com.harshvardhan.ultrawatch.core.SlotPosition
import com.harshvardhan.ultrawatch.core.WatchGeometry
import com.harshvardhan.ultrawatch.data.Prefs
import com.harshvardhan.ultrawatch.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun BatteryScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by Batteries.devices.collectAsState()
    var phoneStatus by remember { mutableStateOf<String?>(null) }
    val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
    val gate = rememberPermissionGate(perms, then = { Batteries.refresh(ctx) }, denied = { Batteries.refresh(ctx) })
    LaunchedEffect(Unit) {
        gate()
        if (!Batteries.requestPhone(ctx)) phoneStatus = "Install UltraWatch on your phone to see its battery"
        while (true) { delay(15_000); Batteries.refresh(ctx) }
    }
    AppScreen("Batteries") {
        list.forEach { d ->
            val stale = d.isStale(System.currentTimeMillis())
            item {
                ActionChip(
                    "${icon(d.kind)} ${d.name}  ${d.percent?.let { "$it%" } ?: "--"}",
                    when {
                        d.kind == DeviceBattery.Kind.WATCH -> (if (d.charging) "Charging · " else "") + Batteries.watchEstimate(d.charging)
                        d.kind == DeviceBattery.Kind.PHONE -> (if (d.charging) "Charging" else "Phone") + if (stale) " · last update a while ago" else ""
                        d.percent == null -> "This device reports battery only to its own app"
                        else -> "Bluetooth"
                    },
                    primary = (d.percent ?: 100) <= 20,
                ) {}
            }
        }
        if (list.none { it.kind == DeviceBattery.Kind.PHONE }) item { InfoText(phoneStatus ?: "Waiting for phone...") }
        item { ActionChip("Find my phone", "Rings your phone, even on silent") { scope.launch { phoneStatus = if (Batteries.findPhone(ctx)) "Ringing your phone" else "Phone app not reachable" } } }
        item { Toggle("Low battery alerts", Prefs.get(ctx).lowBatteryAlerts, "At 20% for any device") { Prefs.get(ctx).lowBatteryAlerts = it } }
        item { Toggle("Charge to 80% reminder", Prefs.get(ctx).chargeReminder, "Protects long-term battery health") { Prefs.get(ctx).chargeReminder = it } }
    }
}

private fun icon(k: DeviceBattery.Kind) = when (k) {
    DeviceBattery.Kind.WATCH -> "⌚"; DeviceBattery.Kind.PHONE -> "📱"; DeviceBattery.Kind.EARBUDS -> "🎧"
    DeviceBattery.Kind.HEADPHONES -> "🎧"; DeviceBattery.Kind.SENSOR -> "❤"; DeviceBattery.Kind.OTHER -> "🔵"
}

@Composable
fun FaceDesignerScreen() {
    val ctx = LocalContext.current
    val p = remember { Prefs.get(ctx) }
    val cfg by p.face.collectAsState()
    fun set(c: FaceConfig) { p.faceConfig = c }
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(1_000) } }

    AppScreen("Face designer") {
        item { FacePreview(cfg, now) }
        item { Text("Presets", fontSize = 13.sp) }
        FaceConfig.PRESETS.forEach { (name, preset) -> item { ActionChip(name, primary = preset == cfg) { set(preset) } } }
        item { Text("Style", fontSize = 13.sp) }
        FaceStyle.values().forEach { s -> item { ActionChip(s.label, primary = s == cfg.style) { set(cfg.copy(style = s)) } } }
        item { Text("Accent colour", fontSize = 13.sp) }
        FaceConfig.PALETTE.chunked(5).forEach { row ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { c ->
                        Box(Modifier.size(26.dp).background(Color(c), CircleShape)
                            .border(2.dp, if (c == cfg.accent) Color.White else Color.Transparent, CircleShape)
                            .clickable { set(cfg.copy(accent = c)) })
                    }
                }
            }
        }
        item { Toggle("Seconds hand", cfg.showSeconds) { set(cfg.copy(showSeconds = it)) } }
        item { Toggle("Brand logo", cfg.showLogo) { set(cfg.copy(showLogo = it)) } }
        item { ActionChip("Tick marks: ${cfg.tickDensity}", "Tap to change") { set(cfg.copy(tickDensity = when (cfg.tickDensity) { 4 -> 12; 12 -> 60; else -> 4 })) } }
        item { Text("Widgets (complications)", fontSize = 13.sp) }
        SlotPosition.values().forEach { pos ->
            val cur = cfg.slots[pos] ?: SlotData.NONE
            item {
                ActionChip("${pos.name.lowercase().replaceFirstChar { it.uppercase() }}: ${cur.label}", "Tap to change") {
                    val next = SlotData.values()[(cur.ordinal + 1) % SlotData.values().size]
                    set(cfg.copy(slots = if (next == SlotData.NONE) cfg.slots - pos else cfg.slots + (pos to next)))
                }
            }
        }
        item { InfoText("To use it: long-press your watch face, swipe to UltraWatch Luxe. Widget data can also be changed from the watch-face editor.") }
    }
}

/** Live mini preview of the watch face design. */
@Composable
private fun FacePreview(cfg: FaceConfig, t: LocalTime) {
    val accent = Color(cfg.accent)
    Box(Modifier.size(110.dp).background(Color(cfg.background), CircleShape).border(1.dp, Color.DarkGray, CircleShape)) {
        Canvas(Modifier.size(110.dp)) {
            val c = Offset(size.width / 2, size.height / 2); val r = size.width / 2
            fun pt(a: Double, f: Float): Offset { val (x, y) = WatchGeometry.pointAt(c.x.toDouble(), c.y.toDouble(), (r * f).toDouble(), a); return Offset(x.toFloat(), y.toFloat()) }
            if (cfg.style == FaceStyle.DIGITAL_BOLD || cfg.style == FaceStyle.TRADER) return@Canvas
            WatchGeometry.ticks(cfg.tickDensity).forEach { (a, major) -> drawLine(if (major) accent else Color.Gray, pt(a, if (major) 0.84f else 0.92f), pt(a, 0.97f), if (major) 3f else 1f) }
            cfg.slots.keys.forEach { pos -> val b = WatchGeometry.slotBounds(pos); drawCircle(Color(0xFF333338), radius = (b[2] - b[0]) * size.width / 2.6f, center = Offset((b[0] + b[2]) / 2 * size.width, (b[1] + b[3]) / 2 * size.height)) }
            drawLine(Color.White, c, pt(WatchGeometry.hourAngle(t.hour, t.minute), 0.5f), 6f, StrokeCap.Round)
            drawLine(Color.White, c, pt(WatchGeometry.minuteAngle(t.minute, t.second), 0.78f), 4f, StrokeCap.Round)
            if (cfg.showSeconds) drawLine(if (cfg.style == FaceStyle.CHRONO_SPORT) Color(cfg.secondary) else accent, c, pt(WatchGeometry.secondAngle(t.second, 0, false), 0.88f), 2f)
            drawCircle(accent, 4f, c)
        }
        if (cfg.style == FaceStyle.DIGITAL_BOLD || cfg.style == FaceStyle.TRADER) {
            Text("%02d:%02d".format(t.hour, t.minute), color = accent, fontSize = 28.sp, modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
        }
    }
}

@Composable
fun SettingsScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = remember { Prefs.get(ctx) }
    var prof by remember { mutableStateOf(p.profile) }
    fun saveProf(n: com.harshvardhan.ultrawatch.data.Profile) { prof = n; p.profile = n }
    var night by remember { mutableStateOf(p.nightModeAuto) }
    var nightForce by remember { mutableStateOf(p.nightModeForced) }
    var outdoor by remember { mutableStateOf(p.outdoorMode) }
    var saver by remember { mutableStateOf(p.powerSaver) }
    var endpoint by remember { mutableStateOf(p.marketEndpoint) }
    var key by remember { mutableStateOf(p.geminiKey) }
    var model by remember { mutableStateOf(p.geminiModel) }
    var lockSet by remember { mutableStateOf(p.appLock.isSet) }
    var msg by remember { mutableStateOf<String?>(null) }

    val nameIn = rememberTextInput("Your name") { saveProf(prof.copy(name = it)) }
    val endpointIn = rememberTextInput("Quotes URL (https://...)") { endpoint = it; p.marketEndpoint = it }
    val keyIn = rememberTextInput("Gemini API key") { key = it; p.geminiKey = it }
    val modelIn = rememberTextInput("Gemini model") { model = it; p.geminiModel = it }
    val pinIn = rememberTextInput("New PIN (4-8 digits)") { pin ->
        val l = AppLock()
        msg = try { l.setPin(pin.trim()); p.appLock = l; lockSet = true; "App lock on" } catch (e: IllegalArgumentException) { "PIN must be 4-8 digits" }
    }
    val hasUwb = remember { Build.VERSION.SDK_INT >= 31 && ctx.packageManager.hasSystemFeature("android.hardware.uwb") }

    AppScreen("Settings") {
        msg?.let { item { InfoText(it, Brand.Green) } }
        item { Text("Profile", fontSize = 13.sp) }
        item { ActionChip("Name", prof.name.ifBlank { "Used in SOS messages" }) { nameIn() } }
        item { Stepper("Age", prof.age, 13..100) { saveProf(prof.copy(age = it)) } }
        item { Toggle("Male", prof.male, "Used by calorie and body formulas") { saveProf(prof.copy(male = it)) } }
        item { Stepper("Height", prof.heightCm, 120..220, suffix = " cm") { saveProf(prof.copy(heightCm = it)) } }
        item { Stepper("Weight", prof.weightKg, 30..200, suffix = " kg") { saveProf(prof.copy(weightKg = it)) } }
        item { Stepper("Resting HR", prof.restingHr, 35..110, suffix = " bpm") { saveProf(prof.copy(restingHr = it)) } }

        item { Text("Display", fontSize = 13.sp) }
        item { Toggle("Night vision (auto)", night, "Red screen 8 pm - 6 am") { night = it; p.nightModeAuto = it } }
        item { Toggle("Night vision now", nightForce) { nightForce = it; p.nightModeForced = it } }
        item { Toggle("Outdoor high contrast", outdoor, "Restart app to apply") { outdoor = it; p.outdoorMode = it } }
        item { Toggle("Power saver", saver, "Slower refresh, no background market checks") { saver = it; p.powerSaver = it } }

        item { Text("Security", fontSize = 13.sp) }
        item { ActionChip(if (lockSet) "Change PIN" else "Set app lock PIN", "Locks messages, markets and settings") { pinIn() } }
        if (lockSet) item { ActionChip("Remove app lock") { p.appLock = AppLock(); lockSet = false } }

        item { Text("Connections", fontSize = 13.sp) }
        item { ActionChip("Quotes URL", endpoint.ifBlank { "Not set · demo prices" }) { endpointIn() } }
        item { ActionChip("Gemini API key", if (key.isBlank()) "Not set" else "Set (" + key.takeLast(4) + ")") { keyIn() } }
        item { ActionChip("Gemini model", model) { modelIn() } }
        item { ActionChip("Message access", if (MessageStore.listenerEnabled(ctx)) "Allowed" else "Not allowed") { ctx.startActivity(MessageStore.openListenerSettings()) } }
        item { Stat("UWB chip", if (hasUwb) "Present" else "Not on this watch") }

        item { ActionChip("About UltraWatch") { go("about") } }
        item { BrandFooter() }
    }
}

@Composable
fun AboutScreen() {
    AppScreen("UltraWatch") {
        item { BrandFooter() }
        item { InfoText("Version ${BuildConfig.VERSION_NAME}") }
        item { InfoText("Health readings are for wellness and screening only, not medical diagnosis. Market data is not investment advice.") }
        item { InfoText("Works on Wear OS 3+ watches: Samsung Galaxy Watch 4+, Pixel Watch, OnePlus, Xiaomi Watch 2, Oppo Watch X, TicWatch and more.") }
    }
}
