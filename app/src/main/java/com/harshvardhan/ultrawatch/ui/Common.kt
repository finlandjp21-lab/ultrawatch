package com.harshvardhan.ultrawatch.ui

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.input.RemoteInputIntentHelper
import com.harshvardhan.ultrawatch.R

object Brand {
    val Orange = Color(0xFFFF7A1A)
    val Blue = Color(0xFF1E7BFF)
    val Green = Color(0xFF30D158)
    val Red = Color(0xFFFF453A)
    val Purple = Color(0xFFBF5AF2)
    val Cyan = Color(0xFF39D0FF)
    val Yellow = Color(0xFFFFD60A)
    val Grey = Color(0xFF9A9AA0)
}

/** Normal, outdoor (max contrast) and night-vision red palettes. */
@Composable
fun UltraTheme(night: Boolean, outdoor: Boolean, content: @Composable () -> Unit) {
    val colors = when {
        night -> Colors(
            primary = Color(0xFFD01010), primaryVariant = Color(0xFF900000), secondary = Color(0xFFB00000),
            background = Color.Black, surface = Color(0xFF1A0000), error = Color(0xFFFF2020),
            onPrimary = Color.Black, onSecondary = Color.Black, onBackground = Color(0xFFE02020),
            onSurface = Color(0xFFE02020), onSurfaceVariant = Color(0xFF901010), onError = Color.Black,
        )
        outdoor -> Colors(
            primary = Color(0xFFFFB000), secondary = Color.White, background = Color.Black,
            surface = Color(0xFF262626), onPrimary = Color.Black, onSecondary = Color.Black,
            onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color(0xFFE0E0E0),
        )
        else -> Colors(
            primary = Brand.Orange, primaryVariant = Color(0xFFC85A00), secondary = Brand.Blue,
            background = Color.Black, surface = Color(0xFF1C1C20), onPrimary = Color.Black,
            onSecondary = Color.White, onBackground = Color.White, onSurface = Color.White,
            onSurfaceVariant = Color(0xFFB0B0B8),
        )
    }
    MaterialTheme(colors = colors, content = content)
}

/** Standard scrolling screen with time, curved scroll indicator and vignette. */
@Composable
fun AppScreen(title: String? = null, content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = state,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (title != null) item { ListHeader { Text(title, fontWeight = FontWeight.Medium) } }
            content()
        }
    }
}

@Composable
fun NavChip(label: String, icon: ImageVector, tint: Color, secondary: String? = null, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        secondaryLabel = if (secondary != null) { { Text(secondary, maxLines = 1) } } else null,
        icon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(ChipDefaults.IconSize)) },
        colors = ChipDefaults.secondaryChipColors(),
    )
}

@Composable
fun ActionChip(label: String, secondary: String? = null, primary: Boolean = false, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        label = { Text(label, maxLines = 2) },
        secondaryLabel = if (secondary != null) { { Text(secondary, maxLines = 2) } } else null,
        colors = if (primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
    )
}

@Composable
fun SmallChip(label: String, onClick: () -> Unit) = CompactChip(onClick = onClick, label = { Text(label, maxLines = 1) })

@Composable
fun Toggle(label: String, checked: Boolean, secondary: String? = null, onChange: (Boolean) -> Unit) {
    ToggleChip(
        modifier = Modifier.fillMaxWidth(),
        checked = checked,
        onCheckedChange = onChange,
        label = { Text(label, maxLines = 2) },
        secondaryLabel = if (secondary != null) { { Text(secondary, maxLines = 2) } } else null,
        toggleControl = { Icon(ToggleChipDefaults.switchIcon(checked), contentDescription = if (checked) "On" else "Off") },
    )
}

@Composable
fun BigValue(value: String, unit: String = "", color: Color = MaterialTheme.colors.primary, label: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (label != null) Text(label, fontSize = 12.sp, color = MaterialTheme.colors.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 40.sp, fontWeight = FontWeight.Medium, color = color)
            if (unit.isNotEmpty()) Text(" $unit", fontSize = 13.sp, color = MaterialTheme.colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
fun InfoText(text: String, color: Color = MaterialTheme.colors.onSurfaceVariant) =
    Text(text, fontSize = 12.sp, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))

@Composable
fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colors.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Number row with - / + buttons, for settings like age or alert price. */
@Composable
fun Stepper(label: String, value: Int, range: IntRange, step: Int = 1, suffix: String = "", onChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colors.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallChip("-") { onChange((value - step).coerceIn(range)) }
            Text("$value$suffix", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            SmallChip("+") { onChange((value + step).coerceIn(range)) }
        }
    }
}

@Composable
fun BrandFooter() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Image(painterResource(R.drawable.hvt_logo), contentDescription = "Aitecsek and Harshvardhan Technologies logo",
            modifier = Modifier.size(40.dp).clip(CircleShape))
        Spacer(Modifier.height(4.dp))
        Text("Created by Harshvardhan Technologies", fontSize = 10.sp, color = MaterialTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Runtime-permission helper: returns a function that runs [then] once all permissions are granted. */
@Composable
fun rememberPermissionGate(perms: Array<String>, then: () -> Unit, denied: () -> Unit = {}): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) then() else denied()
    }
    return {
        val missing = perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then() else launcher.launch(missing.toTypedArray())
    }
}

/** Speech-to-text using the watch's system recogniser. */
@Composable
fun rememberVoiceInput(prompt: String = "Speak now", languageTag: String? = null, onResult: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onResult)
    }
    return {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        languageTag?.let { i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        runCatching { launcher.launch(i) }
    }
}

/** Keyboard / handwriting / emoji input via the Wear OS input screen. */
@Composable
fun rememberTextInput(label: String, onResult: (String) -> Unit): () -> Unit {
    val key = "uw_text"
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(key)?.toString() }?.takeIf { it.isNotBlank() }?.let(onResult)
    }
    return {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(key).setLabel(label).build()))
        launcher.launch(intent)
    }
}

var unlockedThisSession by mutableStateOf(false)

/** Asks for the app-lock PIN before showing sensitive screens (messages, markets, SOS settings). */
@Composable
fun LockGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val prefs = com.harshvardhan.ultrawatch.data.Prefs.get(ctx)
    if (!prefs.appLock.isSet || unlockedThisSession) { content(); return }
    var entered by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("Enter PIN") }
    AppScreen {
        item { Text(msg, fontSize = 13.sp, textAlign = TextAlign.Center) }
        item { Text("●".repeat(entered.length).ifEmpty { "–" }, fontSize = 22.sp) }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK")).forEach { row ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { k ->
                        SmallChip(k) {
                            when (k) {
                                "⌫" -> entered = entered.dropLast(1)
                                "OK" -> {
                                    val lock = prefs.appLock
                                    when (val r = lock.verify(entered, System.currentTimeMillis())) {
                                        is com.harshvardhan.ultrawatch.core.AppLock.Result.Ok -> unlockedThisSession = true
                                        is com.harshvardhan.ultrawatch.core.AppLock.Result.Wrong -> msg = "Wrong PIN · ${r.triesLeft} tries left"
                                        is com.harshvardhan.ultrawatch.core.AppLock.Result.LockedOut -> msg = "Locked · wait ${r.ms / 1000}s"
                                    }
                                    prefs.appLock = lock
                                    entered = ""
                                }
                                else -> if (entered.length < 8) entered += k
                            }
                        }
                    }
                }
            }
        }
    }
}
