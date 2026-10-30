package com.harshvardhan.ultrawatch.ui.screens

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.harshvardhan.ultrawatch.ai.Gemini
import com.harshvardhan.ultrawatch.ai.LiveTranscriber
import com.harshvardhan.ultrawatch.ai.Speaker
import com.harshvardhan.ultrawatch.ai.TranslatorEngine
import com.harshvardhan.ultrawatch.core.AlertCondition
import com.harshvardhan.ultrawatch.core.MarketFormat
import com.harshvardhan.ultrawatch.core.PriceAlert
import com.harshvardhan.ultrawatch.core.Quote
import com.harshvardhan.ultrawatch.core.TranslatorLanguages
import com.harshvardhan.ultrawatch.data.Prefs
import com.harshvardhan.ultrawatch.markets.MarketRepository
import com.harshvardhan.ultrawatch.markets.MarketSnapshot
import com.harshvardhan.ultrawatch.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MarketsScreen() {
    val ctx = LocalContext.current
    val p = remember { Prefs.get(ctx) }
    var snap by remember { mutableStateOf<MarketSnapshot?>(null) }
    var selected by remember { mutableStateOf<Quote?>(null) }
    var alertsVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { snap = MarketRepository.fetch(ctx); delay(if (p.powerSaver) 60_000 else 15_000) }
    }

    val sel = selected
    if (sel != null) {
        var level by remember(sel) { mutableIntStateOf(sel.ltp.toInt()) }
        val step = when { sel.ltp > 20_000 -> 50; sel.ltp > 2_000 -> 10; else -> 1 }
        AppScreen(sel.symbol) {
            item { BigValue(MarketFormat.inr(sel.ltp), color = if (sel.isUp) Brand.Green else Brand.Red) }
            item { InfoText("${MarketFormat.inr(sel.change)} (${MarketFormat.pct(sel.changePct)}) · prev ${MarketFormat.inr(sel.prevClose)}") }
            item { Stepper("Alert level", level, 1..1_000_000, step) { level = it } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallChip("Above") { val e = p.alerts; e.add(PriceAlert(System.currentTimeMillis(), sel.symbol, AlertCondition.ABOVE, level.toDouble())); p.alerts = e; alertsVersion++ }
                    SmallChip("Below") { val e = p.alerts; e.add(PriceAlert(System.currentTimeMillis(), sel.symbol, AlertCondition.BELOW, level.toDouble())); p.alerts = e; alertsVersion++ }
                }
            }
            item { ActionChip("Alert on ±2% day move") { val e = p.alerts; e.add(PriceAlert(System.currentTimeMillis(), sel.symbol, AlertCondition.MOVE_PCT, 2.0)); p.alerts = e; alertsVersion++ } }
            item { ActionChip("Back") { selected = null } }
        }
        return
    }

    val alerts = remember(alertsVersion, snap) { p.alerts.alerts }
    AppScreen("Markets") {
        val s = snap
        if (s == null) item { InfoText("Loading...") }
        else {
            if (s.demo) item { InfoText("DEMO prices · set your SLBA PRO / quotes URL in Settings for live data", Brand.Yellow) }
            s.error?.let { item { InfoText(it, Brand.Yellow) } }
            s.quotes.forEach { q ->
                item { ActionChip("${q.symbol}  ${MarketFormat.inr(q.ltp)}", MarketFormat.pct(q.changePct)) { selected = q } }
            }
        }
        if (alerts.isNotEmpty()) {
            item { Text("Alerts", fontSize = 14.sp) }
            alerts.forEach { a ->
                item {
                    ActionChip("${a.symbol} ${a.condition.name.lowercase().replace('_', ' ')} ${if (a.condition == AlertCondition.MOVE_PCT) "${a.threshold}%" else MarketFormat.inr(a.threshold)}", "Tap to delete") {
                        val e = p.alerts; e.remove(a.id); p.alerts = e; alertsVersion++
                    }
                }
            }
        }
        item { InfoText("Alerts are checked every 15 minutes in the background. Not investment advice.") }
    }
}

object AssistantState { @Volatile var pendingQuestion: String? = null }

@Composable
fun AssistantScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val chat = remember { mutableStateListOf<Pair<String, String>>() }
    var busy by remember { mutableStateOf(false) }
    val speaker = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    fun ask(q: String) {
        val cmd = runVoiceCommand(ctx, q, go)
        if (cmd != "Asking Gemini") { chat += q to cmd; return }
        AssistantState.pendingQuestion = null
        busy = true
        scope.launch {
            val a = Gemini.ask(ctx, q)
            chat += q to a; busy = false
            speaker.say(a, "en")
        }
    }
    LaunchedEffect(Unit) {
        AssistantState.pendingQuestion?.let { q -> AssistantState.pendingQuestion = null; busy = true
            val a = Gemini.ask(ctx, q); chat += q to a; busy = false; speaker.say(a, "en") }
    }
    val voice = rememberVoiceInput("Ask anything") { ask(it) }
    val keyboard = rememberTextInput("Ask Gemini") { ask(it) }

    AppScreen("Assistant") {
        item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SmallChip("Speak") { voice() }; SmallChip("Type") { keyboard() } } }
        if (busy) item { InfoText("Thinking...") }
        chat.reversed().forEach { (q, a) ->
            item { InfoText(q, Brand.Cyan) }
            item { Text(a, fontSize = 14.sp) }
        }
        if (chat.isEmpty()) {
            item { InfoText("Try: \"navigate to Helsinki airport\", \"open WhatsApp\", \"call papa\", \"what's a good stretch after running?\"") }
            item { ActionChip("Open system assistant", "Gemini / Google Assistant") {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VOICE_COMMAND).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } }
        }
    }
}

@Composable
fun TranslatorScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val transcriber = remember { LiveTranscriber(ctx) }
    val speaker = remember { Speaker(ctx) }
    var mine by remember { mutableStateOf("en") }
    var theirs by remember { mutableStateOf("hi") }
    var speakingSide by remember { mutableStateOf<String?>(null) } // "me" | "them" | "transcribe"
    val turns = remember { mutableStateListOf<Pair<String, String>>() }
    var ready by remember { mutableStateOf<String?>(null) }
    val partial by transcriber.partial.collectAsState()
    val lines by transcriber.lines.collectAsState()
    DisposableEffect(Unit) { onDispose { transcriber.destroy(); speaker.shutdown() } }

    fun startSide(side: String) {
        speakingSide = side
        val (from, to) = if (side == "me") mine to theirs else theirs to mine
        transcriber.onFinal = { text ->
            if (side != "transcribe") scope.launch {
                val out = TranslatorEngine.translate(text, from, to)
                turns += text to out
                transcriber.stop()
                speaker.say(out, to)
                speakingSide = null
            }
        }
        transcriber.start(if (side == "transcribe") mine else from)
    }
    val mic = rememberPermissionGate(arrayOf(Manifest.permission.RECORD_AUDIO), then = { speakingSide?.let { startSide(it) } })
    val langs = TranslatorLanguages.ALL

    AppScreen("Translator") {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip(TranslatorLanguages.name(mine)) { mine = langs[(langs.indexOfFirst { it.first == mine } + 1) % langs.size].first }
                Text("⇄", fontSize = 16.sp)
                SmallChip(TranslatorLanguages.name(theirs)) { theirs = langs[(langs.indexOfFirst { it.first == theirs } + 1) % langs.size].first }
            }
        }
        item { ActionChip("Download languages", ready ?: "Once, then works offline") {
            ready = "Downloading..."; scope.launch { ready = if (TranslatorEngine.prepare(mine, theirs) && TranslatorEngine.prepare(theirs, mine)) "Ready offline" else "Download failed, check internet" }
        } }
        item { ActionChip("I speak", TranslatorLanguages.name(mine), primary = speakingSide == "me") { speakingSide = "me"; mic() } }
        item { ActionChip("They speak", TranslatorLanguages.name(theirs), primary = speakingSide == "them") { speakingSide = "them"; mic() } }
        if (partial.isNotBlank()) item { InfoText(partial, Brand.Cyan) }
        turns.reversed().forEach { (src, out) ->
            item { InfoText(src) }
            item { Text(out, fontSize = 15.sp) }
        }
        item { Text("Live transcription", fontSize = 14.sp) }
        item {
            ActionChip(if (speakingSide == "transcribe") "Stop" else "Start transcribing", TranslatorLanguages.name(mine)) {
                if (speakingSide == "transcribe") { transcriber.stop(); speakingSide = null } else { speakingSide = "transcribe"; mic() }
            }
        }
        lines.takeLast(10).reversed().forEach { l -> item { Text(l, fontSize = 13.sp) } }
        item { InfoText("Works face to face. Phone-call translation isn't possible: Android doesn't give apps access to call audio.") }
    }
}
