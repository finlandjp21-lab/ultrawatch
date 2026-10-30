package com.harshvardhan.ultrawatch

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.harshvardhan.ultrawatch.core.Environment
import com.harshvardhan.ultrawatch.data.Prefs
import com.harshvardhan.ultrawatch.safety.Emergency
import com.harshvardhan.ultrawatch.ui.LockGate
import com.harshvardhan.ultrawatch.ui.UltraTheme
import com.harshvardhan.ultrawatch.ui.screens.*
import kotlinx.coroutines.delay
import java.time.LocalTime

class MainActivity : ComponentActivity() {
    private var pendingRoute by mutableStateOf<String?>(null)
    var waterLock by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingRoute = intent?.getStringExtra(EXTRA_ROUTE)
        val prefs = Prefs.get(this)

        setContent {
            val night by produceState(isNight(prefs)) { while (true) { value = isNight(prefs); delay(60_000) } }
            UltraTheme(night = night, outdoor = prefs.outdoorMode) {
                val nav = rememberSwipeDismissableNavController()
                LaunchedEffect(pendingRoute) {
                    pendingRoute?.let { r -> if (r in ROUTES) nav.navigate(r) { launchSingleTop = true } }
                    pendingRoute = null
                }
                val go: (String) -> Unit = { nav.navigate(it) { launchSingleTop = true } }
                SwipeDismissableNavHost(navController = nav, startDestination = "home") {
                    composable("home") { HomeScreen(go) }
                    composable("health") { HealthScreen(go) }
                    composable("energy") { EnergyScreen() }
                    composable("workout") { WorkoutScreen(go) }
                    composable("sleep") { SleepScreen() }
                    composable("vitals") { VitalsScreen() }
                    composable("water") { WaterScreen(onLock = { waterLock = true }) }
                    composable("calls") { CallsScreen() }
                    composable("messages") { LockGate { MessagesScreen() } }
                    composable("music") { MusicScreen() }
                    composable("video") { VideoScreen() }
                    composable("markets") { LockGate { MarketsScreen() } }
                    composable("assistant") { AssistantScreen(go) }
                    composable("translator") { TranslatorScreen() }
                    composable("safety") { SafetyScreen(go) }
                    composable("fall_alert") { FallAlertScreen(onDone = { nav.popBackStack() }) }
                    composable("gps") { GpsScreen(go) }
                    composable("trackback") { TrackBackScreen() }
                    composable("battery") { BatteryScreen() }
                    composable("face") { FaceDesignerScreen() }
                    composable("settings") { LockGate { SettingsScreen(go) } }
                    composable("about") { AboutScreen() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingRoute = intent.getStringExtra(EXTRA_ROUTE)
    }

    /** Water lock: ignore touches until the crown/button is pressed. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean = if (waterLock) true else super.dispatchTouchEvent(ev)

    /** Multifunction buttons: unlock water lock, or (while this app is open) stem button 1 toggles the siren. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (waterLock && (keyCode == KeyEvent.KEYCODE_STEM_PRIMARY || keyCode == KeyEvent.KEYCODE_STEM_1 || keyCode == KeyEvent.KEYCODE_BACK)) {
            waterLock = false; return true
        }
        if (keyCode == KeyEvent.KEYCODE_STEM_1 && event?.isLongPress == true) {
            if (Emergency.sirenOn) Emergency.stopSiren() else Emergency.startSiren(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isNight(p: Prefs) = p.nightModeForced || (p.nightModeAuto && Environment.isNight(LocalTime.now().hour))

    companion object {
        const val EXTRA_ROUTE = "route"
        val ROUTES = setOf("health", "energy", "workout", "sleep", "vitals", "water", "calls", "messages", "music", "video",
            "markets", "assistant", "translator", "safety", "fall_alert", "gps", "trackback", "battery", "face", "settings", "about")
    }
}
