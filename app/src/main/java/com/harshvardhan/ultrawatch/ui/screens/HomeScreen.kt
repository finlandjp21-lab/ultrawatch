package com.harshvardhan.ultrawatch.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.harshvardhan.ultrawatch.complication.EnergyCalc
import com.harshvardhan.ultrawatch.comms.Phone
import com.harshvardhan.ultrawatch.core.Command
import com.harshvardhan.ultrawatch.core.ContactMatcher
import com.harshvardhan.ultrawatch.core.Training
import com.harshvardhan.ultrawatch.core.VoiceCommandParser
import com.harshvardhan.ultrawatch.health.SessionService
import com.harshvardhan.ultrawatch.safety.SafetyService
import com.harshvardhan.ultrawatch.data.Prefs
import com.harshvardhan.ultrawatch.ui.*

@Composable
fun HomeScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    var heard by remember { mutableStateOf<String?>(null) }
    val voice = rememberVoiceInput("Try: call mom, play music, how is Nifty") { said ->
        heard = runVoiceCommand(ctx, said, go)
    }
    val startPerms = buildList {
        add(Manifest.permission.BODY_SENSORS); add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val startSafety = { if (Prefs.get(ctx).fallDetection) runCatching { SafetyService.ensureRunning(ctx) } }
    // Fall detection only needs the accelerometer, so it starts even if some permissions are declined.
    val startup = rememberPermissionGate(startPerms, then = { startSafety() }, denied = { startSafety() })
    LaunchedEffect(Unit) { startup() }
    val energy = remember { EnergyCalc.score(ctx) }

    AppScreen {
        item { Text("UltraWatch", fontSize = 16.sp) }
        item {
            ActionChip(
                label = "Energy ${energy ?: "--"}",
                secondary = energy?.let { Training.energyAdvice(it) } ?: "Track tonight's sleep to start",
                primary = true,
            ) { go("energy") }
        }
        item { NavChip("Ask or command", Icons.Outlined.Mic, Brand.Cyan, heard ?: "Tap and speak") { voice() } }
        item { NavChip("Health", Icons.Outlined.MonitorHeart, Brand.Red, "Heart, steps, stress") { go("health") } }
        item { NavChip("Workout", Icons.Outlined.DirectionsRun, Brand.Green, "GPS, multisport, zones") { go("workout") } }
        item { NavChip("Vitals", Icons.Outlined.Bloodtype, Brand.Red, "BP, SpO2, rhythm, body") { go("vitals") } }
        item { NavChip("Sleep", Icons.Outlined.Bedtime, Brand.Purple) { go("sleep") } }
        item { NavChip("Calls", Icons.Outlined.Call, Brand.Green) { go("calls") } }
        item { NavChip("Messages", Icons.Outlined.Chat, Brand.Blue) { go("messages") } }
        item { NavChip("Music", Icons.Outlined.MusicNote, Brand.Orange) { go("music") } }
        item { NavChip("Video", Icons.Outlined.Movie, Brand.Purple) { go("video") } }
        item { NavChip("Markets", Icons.Outlined.ShowChart, Brand.Green, "Watchlist and alerts") { go("markets") } }
        item { NavChip("AI assistant", Icons.Outlined.AutoAwesome, Brand.Cyan, "Gemini") { go("assistant") } }
        item { NavChip("Translator", Icons.Outlined.Translate, Brand.Blue, "Talk and transcribe") { go("translator") } }
        item { NavChip("GPS", Icons.Outlined.SatelliteAlt, Brand.Yellow, "Satellites, waypoints") { go("gps") } }
        item { NavChip("Safety and SOS", Icons.Outlined.Sos, Brand.Red, "SOS, siren, check-in") { go("safety") } }
        item { NavChip("Water mode", Icons.Outlined.Pool, Brand.Cyan, "Water lock, depth") { go("water") } }
        item { NavChip("Batteries", Icons.Outlined.BatteryChargingFull, Brand.Green, "Watch, phone, earbuds") { go("battery") } }
        item { NavChip("Face designer", Icons.Outlined.Palette, Brand.Orange) { go("face") } }
        item { NavChip("Settings", Icons.Outlined.Settings, Brand.Grey) { go("settings") } }
        item { BrandFooter() }
    }
}

/** Runs a spoken command. Returns a short status line for the home screen. */
fun runVoiceCommand(ctx: Context, said: String, go: (String) -> Unit): String {
    val contacts by lazy { Phone.contacts(ctx) }
    return when (val c = VoiceCommandParser.parse(said)) {
        is Command.Call -> ContactMatcher.bestMatch(c.who, contacts)?.let { Phone.call(ctx, it.number); "Calling ${it.name}" }
            ?: run { go("calls"); "No contact named ${c.who}" }
        is Command.Message -> {
            val who = ContactMatcher.bestMatch(c.who, contacts)
            val text = c.text
            when {
                who == null -> { go("messages"); "No contact named ${c.who}" }
                text == null -> { Phone.composeSms(ctx, who.number, ""); "Message ${who.name}" }
                Phone.sendSms(ctx, who.number, text) -> "Sent to ${who.name}"
                else -> { Phone.composeSms(ctx, who.number, text); "Confirm send to ${who.name}" }
            }
        }
        is Command.PlayMusic -> { MusicCommands.pending = c; go("music"); "Playing music" }
        Command.PauseMusic, Command.NextTrack, Command.PreviousTrack -> { MusicCommands.pending = c; go("music"); "OK" }
        is Command.PlayVideo -> { go("video"); "Videos" }
        is Command.StartWorkout -> { SessionService.start(ctx, c.type); go("workout"); "${c.type} started" }
        Command.StopWorkout -> { SessionService.stop(ctx); "Workout stopped" }
        Command.ShowHeartRate, Command.ShowSteps -> { go("health"); "Health" }
        is Command.ShowQuote -> { go("markets"); c.symbol }
        is Command.SetTimer -> {
            ctx.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, c.seconds)
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Timer ${c.seconds / 60} min"
        }
        Command.ReadMessages -> { go("messages"); "Messages" }
        Command.Breathe -> { go("health"); "Breathe" }
        is Command.Unknown -> shortcut(ctx, said, go)
    }
}

/** Shortcuts to other apps (Maps navigation, WhatsApp, system assistant), then Gemini for anything else. */
private fun shortcut(ctx: Context, said: String, go: (String) -> Unit): String {
    val s = said.lowercase().trim()
    Regex("^(?:navigate|directions|take me|le chalo) (?:to )?(.+)$").find(s)?.let {
        val dest = it.groupValues[1]
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { ctx.startActivity(i) }.isSuccess) "Navigating to $dest" else "Google Maps isn't installed"
    }
    if (s.contains("whatsapp")) {
        val i = ctx.packageManager.getLaunchIntentForPackage("com.whatsapp")?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (i != null) { ctx.startActivity(i); "Opening WhatsApp" } else "WhatsApp isn't installed"
    }
    if (s == "gemini" || s == "hey google" || s == "assistant") {
        val ok = runCatching { ctx.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        return if (ok) "Opening assistant" else "No system assistant"
    }
    AssistantState.pendingQuestion = said
    go("assistant")
    return "Asking Gemini"
}
