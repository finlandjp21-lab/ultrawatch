package com.harshvardhan.ultrawatch.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Finds paired watches and whether UltraWatch is installed on them. */
object WatchConnection {
    data class Status(val watches: List<String>, val withUltraWatch: List<String>, val wearOsAvailable: Boolean)

    /** Companion apps that pair watches with the phone, by brand. Only installed ones are shown. */
    val PAIRING_APPS = listOf(
        "Galaxy Wearable (Samsung)" to "com.samsung.android.app.watchmanager",
        "Pixel Watch" to "com.google.android.apps.wear.companion",
        "Wear OS by Google" to "com.google.android.wearable.app",
        "OHealth (OnePlus / Oppo)" to "com.heytap.health.international",
        "Mobvoi Health (TicWatch)" to "com.mobvoi.companion",
        "Mi Fitness (Xiaomi)" to "com.xiaomi.wearable",
        "realme Link" to "com.realme.link",
        "Nothing X" to "com.nothing.smartcenter",
        "CMF Watch" to "com.nothing.cmf.watch",
        "Zepp (Amazfit)" to "com.huami.watch.hmwatchmanager",
        "Huawei Health" to "com.huawei.health",
        "Honor Health" to "com.hihonor.health",
        "COROS" to "com.coros.coros",
        "vivo Health" to "com.vivo.health",
    )

    fun installedPairingApps(ctx: Context) = PAIRING_APPS.filter { (_, pkg) ->
        ctx.packageManager.getLaunchIntentForPackage(pkg) != null
    }

    fun open(ctx: Context, pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openBluetooth(ctx: Context) = ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun openStore(ctx: Context, pkg: String) = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun status(ctx: Context): Status = withTimeoutOrNull(6_000) {
        val nodes = runCatching { Wearable.getNodeClient(ctx).connectedNodes.await() }
        if (nodes.isFailure) return@withTimeoutOrNull Status(emptyList(), emptyList(), wearOsAvailable = false)
        val names = nodes.getOrThrow().map { it.displayName }
        val withApp = runCatching {
            Wearable.getCapabilityClient(ctx).getCapability("ultrawatch_watch", CapabilityClient.FILTER_REACHABLE).await().nodes.map { it.displayName }
        }.getOrDefault(emptyList())
        Status(names, withApp, wearOsAvailable = true)
    } ?: Status(emptyList(), emptyList(), wearOsAvailable = false)
}
