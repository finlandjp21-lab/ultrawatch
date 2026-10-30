package com.harshvardhan.ultrawatch.ui.screens

import android.Manifest
import android.content.Intent
import android.hardware.Sensor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.harshvardhan.ultrawatch.comms.Phone
import com.harshvardhan.ultrawatch.core.ContactMatcher
import com.harshvardhan.ultrawatch.core.Geo
import com.harshvardhan.ultrawatch.core.Gnss
import com.harshvardhan.ultrawatch.core.SafetyCheck
import com.harshvardhan.ultrawatch.core.SosMessage
import com.harshvardhan.ultrawatch.data.EmergencyContact
import com.harshvardhan.ultrawatch.data.Prefs
import com.harshvardhan.ultrawatch.gps.GpsMonitor
import com.harshvardhan.ultrawatch.health.SessionService
import com.harshvardhan.ultrawatch.health.Sensors
import com.harshvardhan.ultrawatch.safety.Emergency
import com.harshvardhan.ultrawatch.safety.SafetyService
import com.harshvardhan.ultrawatch.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val sosPerms = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.ACCESS_FINE_LOCATION)

/** Sends SOS texts, then calls the first contact. Returns a status line. */
private suspend fun fireSos(ctx: android.content.Context, reason: String): String {
    val p = Prefs.get(ctx)
    Emergency.vibrateSos(ctx)
    val loc = Emergency.currentLocation(ctx)
    val sent = Emergency.sendSos(ctx, reason, loc)
    val first = p.emergencyContacts.firstOrNull()
    if (sent == 0) {
        // Bluetooth-only watch: hand the message to the messaging app via the phone
        val msg = SosMessage.build(p.profile.name, reason, loc?.latitude, loc?.longitude, loc?.accuracy)
        first?.let { Phone.composeSms(ctx, it.number, msg) }
    }
    first?.let { ctx.startActivity(Emergency.callIntent(ctx, it.number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    return when {
        first == null -> "Add an emergency contact first"
        sent > 0 -> "SOS sent to $sent contact(s), calling ${first.name}"
        else -> "Confirm the SOS text, calling ${first.name}"
    }
}

@Composable
fun SafetyScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = remember { Prefs.get(ctx) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var siren by remember { mutableStateOf(Emergency.sirenOn) }
    var fall by remember { mutableStateOf(p.fallDetection) }
    var contacts by remember { mutableStateOf(p.emergencyContacts) }
    var holdProgress by remember { mutableStateOf(0f) }
    var checkMinutes by remember { mutableIntStateOf(30) }
    val check by SafetyService.checkIn.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }

    val gate = rememberPermissionGate(sosPerms, then = { scope.launch { status = fireSos(ctx, "SOS button pressed") } })
    val addContact = rememberVoiceInput("Say a contact name") { said ->
        val c = ContactMatcher.bestMatch(said, Phone.contacts(ctx))
        if (c == null) status = "No contact named $said"
        else { contacts = (contacts + EmergencyContact(c.name, c.number)).distinctBy { it.number }.take(5); p.emergencyContacts = contacts }
    }
    val contactsGate = rememberPermissionGate(arrayOf(Manifest.permission.READ_CONTACTS), then = { addContact() })
    val shareGate = rememberPermissionGate(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), then = {
        scope.launch {
            val loc = Emergency.currentLocation(ctx)
            val text = if (loc != null) "My location: ${SosMessage.mapsLink(loc.latitude, loc.longitude)}" else "Location unavailable"
            ctx.startActivity(Intent.createChooser(Emergency.shareLocationIntent(text), "Share location").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    })

    AppScreen("Safety") {
        item {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp).pointerInput(Unit) {
                detectTapGestures(onPress = {
                    // Hold for 3 seconds to send SOS, so it can't fire by accident
                    val job = scope.launch { for (i in 1..30) { delay(100); holdProgress = i / 30f }; holdProgress = 0f; gate() }
                    tryAwaitRelease(); job.cancel(); holdProgress = 0f
                })
            }) {
                Canvas(Modifier.size(110.dp)) {
                    drawCircle(Brand.Red)
                    if (holdProgress > 0) drawArc(androidx.compose.ui.graphics.Color.White, -90f, 360f * holdProgress, false, style = Stroke(10f))
                }
                Text("SOS", fontSize = 26.sp)
            }
        }
        item { InfoText(status ?: "Hold 3 s to text your contacts your location and call the first one") }
        item {
            ActionChip(if (siren) "Stop siren" else "Siren", if (Emergency.hasSpeaker(ctx)) "Loud alarm to attract help" else "This watch has no speaker", primary = siren) {
                if (siren) Emergency.stopSiren() else Emergency.startSiren(ctx); siren = Emergency.sirenOn
            }
        }
        item { Toggle("Fall detection", fall, "Asks if you're OK, then sends SOS") {
            fall = it; p.fallDetection = it; runCatching { SafetyService.ensureRunning(ctx) }
        } }
        item { Text("Safety check-in", fontSize = 14.sp) }
        val c = check
        if (c != null) {
            val st = c.status(now)
            item { BigValue("${c.remainingMs(now) / 60_000}", "min left", if (st == SafetyCheck.Status.GRACE) Brand.Red else Brand.Green) }
            item { ActionChip("I'm safe", "Check in now", primary = true) { SafetyService.checkIn(ctx) } }
        } else {
            item { Stepper("Check in within", checkMinutes, 5..480, 5, " min") { checkMinutes = it } }
            item { ActionChip("Start check-in", "Alerts contacts if you don't respond") { SafetyService.startCheck(ctx, checkMinutes.toLong()) } }
        }
        item { ActionChip("Share my location", "Maps link via messages") { shareGate() } }
        item { NavChip("GPS and Track Back", Icons.Outlined.SatelliteAlt, Brand.Yellow) { go("gps") } }
        item { Text("Emergency contacts", fontSize = 14.sp) }
        contacts.forEach { ec -> item { ActionChip(ec.name, "${ec.number} · tap to remove") { contacts = contacts - ec; p.emergencyContacts = contacts } } }
        if (contacts.size < 5) item { ActionChip("Add contact", "Say their name") { contactsGate() } }
        item { InfoText("Satellite SOS needs special watch hardware; UltraWatch uses mobile data, SMS or your phone.") }
    }
}

@Composable
fun FallAlertScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var left by remember { mutableIntStateOf(30) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        Emergency.vibrateSos(ctx)
        while (left > 0 && result == null) { delay(1_000); left--; if (left % 5 == 0) Emergency.vibrateSos(ctx) }
        if (result == null) result = fireSos(ctx, "Fall detected, no response")
    }
    AppScreen {
        item { Text("Are you OK?", fontSize = 18.sp) }
        if (result == null) {
            item { BigValue("$left", "s", Brand.Red) }
            item { ActionChip("I'm OK", "Cancel SOS", primary = true) { result = "Cancelled"; onDone() } }
            item { ActionChip("I need help", "Send SOS now") { left = 0 } }
        } else item { InfoText(result!!) }
    }
}

@Composable
fun GpsScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = remember { Prefs.get(ctx) }
    val gps = remember { GpsMonitor(ctx) }
    val loc by gps.location.collectAsState()
    val sum by gps.summary.collectAsState()
    var heading by remember { mutableStateOf(0.0) }
    var waypoints by remember { mutableStateOf(p.waypoints) }
    var target by remember { mutableStateOf<Gnss.Waypoint?>(null) }
    var dms by remember { mutableStateOf(false) }
    val start = rememberPermissionGate(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), then = { gps.start() })
    LaunchedEffect(Unit) { start() }
    LaunchedEffect(Unit) { Sensors.heading(ctx).collect { heading = Sensors.headingDeg(it.values) } }
    DisposableEffect(Unit) { onDispose { gps.stop() } }
    val nameWp = rememberTextInput("Waypoint name") { n ->
        loc?.let { l -> waypoints = waypoints + Gnss.Waypoint(n, l.latitude, l.longitude, System.currentTimeMillis()); p.waypoints = waypoints }
    }

    AppScreen("GPS") {
        if (!gps.enabled) item { InfoText("Location is off. Turn it on in watch settings.", Brand.Red) }
        val l = loc
        val t = target
        if (t != null && l != null) {
            val here = Geo.Pt(l.latitude, l.longitude)
            val dest = Geo.Pt(t.lat, t.lon)
            val b = Geo.bearing(here, dest)
            item { Arrow(((b - heading) % 360 + 360) % 360) }
            item { BigValue(Geo.formatDistance(Geo.distanceM(here, dest)), color = Brand.Yellow, label = "To ${t.name}") }
            item { InfoText("Head ${Geo.compassPoint(b)} (${b.toInt()}°)") }
            item { ActionChip("Stop navigating") { target = null } }
        }
        item { Stat("Fix", Gnss.fixQuality(l?.accuracy, sum.used)) }
        item { Stat("Satellites", "${sum.used} used / ${sum.inView} seen") }
        item { Stat("Dual-frequency", if (sum.dualFrequency) "Yes (${sum.bands.joinToString("+")})" else if (sum.bands.isEmpty()) "--" else sum.bands.joinToString("+")) }
        sum.byConstellation.forEach { (name, uv) -> item { Stat(name, "${uv.first}/${uv.second}") } }
        if (l != null) {
            item { ActionChip(if (dms) "${Gnss.dms(l.latitude, true)} ${Gnss.dms(l.longitude, false)}" else Gnss.decimal(l.latitude, l.longitude), "±${l.accuracy.toInt()} m · tap to change format") { dms = !dms } }
            item { Stat("Altitude", if (l.hasAltitude()) "${l.altitude.toInt()} m" else "--") }
            item { Stat("Speed", if (l.hasSpeed()) "${Gnss.speedKmh(l.speed)} km/h" else "--") }
            item { Stat("Heading", "${heading.toInt()}° ${Geo.compassPoint(heading)}") }
            item { ActionChip("Save waypoint", "Name this spot", primary = true) { nameWp() } }
        } else item { InfoText("Waiting for a fix. Go outside with a clear view of the sky.") }
        item { ActionChip("Track Back", "Retrace your workout route") { go("trackback") } }
        if (waypoints.isNotEmpty()) item { Text("Waypoints", fontSize = 14.sp) }
        waypoints.reversed().forEach { w ->
            item { ActionChip(w.name, l?.let { Geo.formatDistance(Geo.distanceM(Geo.Pt(it.latitude, it.longitude), Geo.Pt(w.lat, w.lon))) + " away · navigate" } ?: "Navigate") { target = w } }
        }
        if (waypoints.isNotEmpty()) item { ActionChip("Clear waypoints") { waypoints = emptyList(); p.waypoints = emptyList() } }
    }
}

@Composable
private fun Arrow(rotationDeg: Double) {
    Canvas(Modifier.size(70.dp).rotate(rotationDeg.toFloat())) {
        val w = size.width; val h = size.height
        val path = Path().apply { moveTo(w / 2, 0f); lineTo(w * 0.85f, h); lineTo(w / 2, h * 0.72f); lineTo(w * 0.15f, h); close() }
        drawPath(path, Brand.Yellow)
        drawCircle(Brand.Grey, radius = 3f, center = Offset(w / 2, h / 2))
    }
}

@Composable
fun TrackBackScreen() {
    val ctx = LocalContext.current
    val here by SessionService.lastLocation.collectAsState()
    var heading by remember { mutableStateOf(0.0) }
    LaunchedEffect(Unit) { Sensors.heading(ctx).collect { heading = Sensors.headingDeg(it.values) } }
    val tb = SessionService.trackBack
    AppScreen("Track Back") {
        val h = here
        if (tb == null || tb.points.isEmpty()) {
            item { InfoText("Start an outdoor workout (Run, Walk, Cycle, Hike). Your route is recorded so you can retrace it.") }
        } else if (h == null) {
            item { InfoText("Waiting for GPS...") }
        } else {
            val g = tb.guide(h)
            if (g == null || g.arrived) item { BigValue("Home", color = Brand.Green, label = "You're back at the start") }
            else {
                item { Arrow(tb.arrowRotation(g.bearingDeg, heading)) }
                item { BigValue(Geo.formatDistance(g.distanceToStartM), color = Brand.Yellow, label = "Back to start") }
                item { InfoText("Next point ${Geo.formatDistance(g.distanceToNextM)} · head ${Geo.compassPoint(g.bearingDeg)}") }
            }
        }
        item { InfoText("Uses the watch's GPS, including dual-frequency (L1+L5) when the watch supports it.") }
        if (!Sensors.has(ctx, Sensor.TYPE_ROTATION_VECTOR)) item { InfoText("No compass on this watch: hold the watch facing your walking direction.") }
    }
}
