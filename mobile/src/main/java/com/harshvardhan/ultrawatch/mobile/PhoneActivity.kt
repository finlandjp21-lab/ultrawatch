package com.harshvardhan.ultrawatch.mobile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.harshvardhan.ultrawatch.core.AlertCondition
import com.harshvardhan.ultrawatch.core.FaceConfig
import com.harshvardhan.ultrawatch.core.FaceStyle
import com.harshvardhan.ultrawatch.core.MarketFormat
import com.harshvardhan.ultrawatch.core.PriceAlert
import com.harshvardhan.ultrawatch.core.Quote
import com.harshvardhan.ultrawatch.core.SosMessage
import com.harshvardhan.ultrawatch.core.Training
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Orange = Color(0xFFFF7A1A)
private val Blue = Color(0xFF1E7BFF)

class PhoneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Orange, secondary = Blue)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { PhoneRoot() }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Watch), MARKETS("Markets", Icons.Outlined.ShowChart),
    SAFETY("Safety", Icons.Outlined.Sos), FACE("Face", Icons.Outlined.Palette), BRANDS("Watches", Icons.Outlined.Devices),
}

@Composable
private fun PhoneRoot() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }
    Scaffold(bottomBar = {
        NavigationBar {
            Tab.values().forEach { t -> NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) }) }
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.HOME -> HomeTab()
                Tab.MARKETS -> MarketsTab()
                Tab.SAFETY -> SafetyTab()
                Tab.FACE -> FaceTab()
                Tab.BRANDS -> BrandsTab()
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            content()
        }
    }
}

@Composable
private fun Footer() {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.hvt_logo), "Aitecsek and Harshvardhan Technologies logo", Modifier.size(36.dp).clip(CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("Created by Harshvardhan Technologies", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var watches by remember { mutableStateOf<Int?>(null) }
    var health by remember { mutableStateOf<HealthRepo.Summary?>(null) }
    var hcGranted by remember { mutableStateOf(false) }
    val battery = remember { PhoneLink.battery(ctx) }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { hcGranted = HealthRepo.granted(ctx); health = HealthRepo.summary(ctx) }
    }
    LaunchedEffect(Unit) {
        watches = PhoneLink.sendBattery(ctx)
        hcGranted = HealthRepo.granted(ctx)
        health = HealthRepo.summary(ctx)
    }
    LazyColumn {
        item { Footer() }
        item {
            Section("Wear OS watch") {
                Text(when (watches) { null -> "Looking for your watch..."; 0 -> "No Wear OS watch connected. Other brands: see the Watches tab."; else -> "Connected · sending phone battery (${battery?.first ?: "--"}%)" })
                OutlinedButton(onClick = { scope.launch { watches = PhoneLink.sendBattery(ctx) } }) { Text("Sync now") }
            }
        }
        item {
            Section("Health from any watch") {
                if (!HealthRepo.available(ctx)) {
                    Text("Health Connect isn't installed on this phone.")
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }) { Text("Get Health Connect") }
                } else if (!hcGranted) {
                    Text("Read steps, heart rate and sleep that your watch's own app (realme Link, Nothing X, Zepp, Huawei Health...) saves to Health Connect.")
                    Button(onClick = { hcLauncher.launch(HealthRepo.PERMISSIONS) }) { Text("Connect Health Connect") }
                } else {
                    val h = health
                    if (h == null) Text("Loading...") else {
                        Text("Energy ${h.energy ?: "--"}/100", fontSize = 28.sp, color = Orange)
                        h.energy?.let { Text(Training.energyAdvice(it)) }
                        Text("Steps today: ${"%,d".format(h.steps)}")
                        Text("Sleep: ${h.sleepText}" + (h.sleepScore?.let { " · score $it" } ?: ""))
                        Text("Resting HR: ${h.restingHr ?: "--"} · avg HR: ${h.avgHr ?: "--"}")
                        if (h.sources.isNotEmpty()) Text("From: " + h.sources.joinToString { HealthRepo.appName(it) }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text("No data yet. In your watch's app, turn on Health Connect sync.", fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            val p = remember { PhonePrefs(ctx) }
            var mkt by remember { mutableStateOf(p.wristMarkets) }
            var en by remember { mutableStateOf(p.wristEnergy) }
            Section("Alerts on your wrist") {
                Text("These notifications appear on any watch that shows phone notifications. Allow UltraWatch in your watch app's notification settings.", fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(mkt, { mkt = it; p.wristMarkets = it }); Spacer(Modifier.width(8.dp)); Text("Market price alerts") }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(en, { en = it; p.wristEnergy = it }); Spacer(Modifier.width(8.dp)); Text("Morning energy score") }
                OutlinedButton(onClick = { PhoneNotify.wrist(ctx, 1, "UltraWatch test", "If you see this on your watch, alerts are working.") }) { Text("Send test to watch") }
            }
        }
    }
}

@Composable
private fun MarketsTab() {
    val ctx = LocalContext.current
    val p = remember { PhonePrefs(ctx) }
    var quotes by remember { mutableStateOf<List<Quote>>(emptyList()) }
    var demo by remember { mutableStateOf(true) }
    var url by remember { mutableStateOf(p.marketEndpoint) }
    var alerts by remember { mutableStateOf(p.alerts.alerts) }
    var sel by remember { mutableStateOf<Quote?>(null) }
    var level by remember { mutableStateOf("") }
    LaunchedEffect(url) {
        while (true) { val (q, d) = PhoneMarkets.fetch(ctx); quotes = q; demo = d; PhoneMarkets.check(ctx, q); alerts = p.alerts.alerts; delay(15_000) }
    }
    LazyColumn {
        item {
            Section("Quotes source") {
                OutlinedTextField(url, { url = it }, label = { Text("SLBA PRO / quotes URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { p.marketEndpoint = url }) { Text("Save") }
                if (demo) Text("Showing DEMO prices until a URL is saved.", color = Color(0xFFFFD60A), fontSize = 13.sp)
            }
        }
        item {
            Section("Watchlist") {
                quotes.forEach { q ->
                    Row(Modifier.fillMaxWidth().clickable { sel = q; level = q.ltp.toInt().toString() }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(q.symbol); Text("${MarketFormat.inr(q.ltp)}  ${MarketFormat.pct(q.changePct)}", color = if (q.isUp) Color(0xFF30D158) else Color(0xFFFF453A))
                    }
                }
            }
        }
        sel?.let { q ->
            item {
                Section("Alert for ${q.symbol}") {
                    OutlinedTextField(level, { level = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Price") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Above" to AlertCondition.ABOVE, "Below" to AlertCondition.BELOW).forEach { (lbl, cond) ->
                            Button(onClick = {
                                level.toDoubleOrNull()?.let { v -> val e = p.alerts; e.add(PriceAlert(System.currentTimeMillis(), q.symbol, cond, v)); p.alerts = e; alerts = e.alerts }
                            }) { Text(lbl) }
                        }
                    }
                }
            }
        }
        item {
            Section("Active alerts") {
                if (alerts.isEmpty()) Text("None. Tap a symbol to add one.")
                alerts.forEach { a ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${a.symbol} ${a.condition.name.lowercase()} ${MarketFormat.inr(a.threshold)}")
                        TextButton(onClick = { val e = p.alerts; e.remove(a.id); p.alerts = e; alerts = e.alerts }) { Text("Delete") }
                    }
                }
                Text("Checked every 15 minutes; alerts mirror to your watch. Not investment advice.", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SafetyTab() {
    val ctx = LocalContext.current
    val p = remember { PhonePrefs(ctx) }
    var number by remember { mutableStateOf(p.sosNumber) }
    var name by remember { mutableStateOf(p.name) }
    var status by remember { mutableStateOf<String?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        status = sendSos(ctx, p) // location is optional; SOS still opens with no location
    }
    LazyColumn {
        item {
            Section("SOS for any watch") {
                Text("For watches that can't run apps (Realme, Nothing, Honor...): SOS from the phone texts your location and calls your contact.", fontSize = 13.sp)
                OutlinedTextField(name, { name = it; p.name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(number, { number = it; p.sosNumber = it }, label = { Text("Emergency contact number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)), modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("SEND SOS", fontSize = 20.sp) }
                status?.let { Text(it) }
                if (number.isNotBlank()) OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.fillMaxWidth()) { Text("Call $number") }
            }
        }
    }
}

@android.annotation.SuppressLint("MissingPermission")
private fun sendSos(ctx: android.content.Context, p: PhonePrefs): String {
    if (p.sosNumber.isBlank()) return "Add an emergency contact number first"
    val lm = ctx.getSystemService(android.location.LocationManager::class.java)
    val loc = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    val msg = SosMessage.build(p.name, "SOS", loc?.latitude, loc?.longitude, loc?.accuracy)
    // No SMS/call permissions (Play Protect blocks sideloaded apps that have them):
    // open the messaging app with the SOS text ready, then the dialer with the number filled in.
    ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.sosNumber))).putExtra("sms_body", msg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return "Tap Send in your messages app, then call ${p.sosNumber}"
}

@Composable
private fun FaceTab() {
    val ctx = LocalContext.current
    val p = remember { PhonePrefs(ctx) }
    var cfg by remember { mutableStateOf(p.face) }
    var msg by remember { mutableStateOf<String?>(null) }
    val preview = remember(cfg) { FaceExporter.render(ctx, cfg, 320).asImageBitmap() }
    LazyColumn {
        item {
            Section("Watch face for any brand") {
                Text("Design a dial, save it as a picture, then set it as a photo watch face in realme Link, Nothing X, Zepp, Huawei Health or similar. Your watch draws the time on top.", fontSize = 13.sp)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(preview, "Watch face preview", Modifier.size(220.dp).clip(CircleShape))
                }
            }
        }
        item {
            Section("Design") {
                Text("Preset")
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FaceConfig.PRESETS.forEach { (n, c) -> FilterChip(selected = cfg == c, onClick = { cfg = c; p.face = c }, label = { Text(n) }) }
                }
                Text("Style")
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FaceStyle.values().forEach { s -> FilterChip(selected = cfg.style == s, onClick = { cfg = cfg.copy(style = s); p.face = cfg }, label = { Text(s.label) }) }
                }
                Text("Accent")
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FaceConfig.PALETTE.forEach { c ->
                        Box(Modifier.size(28.dp).background(Color(c), CircleShape).border(2.dp, if (cfg.accent == c) Color.White else Color.Transparent, CircleShape)
                            .clickable { cfg = cfg.copy(accent = c); p.face = cfg })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(cfg.showLogo, { cfg = cfg.copy(showLogo = it); p.face = cfg }); Spacer(Modifier.width(8.dp)); Text("Logo") }
                Button(onClick = { msg = if (FaceExporter.save(ctx, cfg)) "Saved to Pictures/UltraWatch" else "Couldn't save the picture" }) { Text("Save as picture") }
                msg?.let { Text(it) }
            }
        }
    }
}

@Composable
private fun BrandsTab() {
    val rows = listOf(
        Triple("Samsung Galaxy Watch 4+, Pixel Watch, OnePlus Watch 2/3, Xiaomi Watch 2, Oppo Watch X, TicWatch, Fossil", "Full UltraWatch app on the watch", Color(0xFF30D158)),
        Triple("Amazfit (Zepp OS), Huawei (HarmonyOS)", "Phone alerts on wrist + Health Connect + photo face. A native mini-app is possible later.", Color(0xFFFFD60A)),
        Triple("Realme, Nothing / CMF, Honor, Vivo, Coros, Redmi / Mi Band", "Phone alerts on wrist + Health Connect (if the brand app syncs) + photo face + phone SOS", Color(0xFFFFD60A)),
    )
    LazyColumn {
        item { Footer() }
        rows.forEach { (brands, how, c) ->
            item { Section(brands) { Text(how, color = c) } }
        }
        item {
            Section("Set up a non-Wear OS watch") {
                Text("1. In your watch's app, allow notifications from UltraWatch.")
                Text("2. In the same app, turn on Health Connect sync if offered.")
                Text("3. Home tab → Connect Health Connect.")
                Text("4. Face tab → save a picture and set it as a photo watch face.")
            }
        }
    }
}
