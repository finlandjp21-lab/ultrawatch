package com.harshvardhan.ultrawatch.markets

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import android.content.ComponentName
import com.harshvardhan.ultrawatch.Notify
import com.harshvardhan.ultrawatch.complication.MarketComplicationService
import com.harshvardhan.ultrawatch.core.DemoMarketFeed
import com.harshvardhan.ultrawatch.core.Quote
import com.harshvardhan.ultrawatch.core.QuoteParser
import com.harshvardhan.ultrawatch.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class MarketSnapshot(val quotes: List<Quote>, val demo: Boolean, val at: Long, val error: String? = null)

/**
 * Quotes come from your own endpoint (for example an SLBA PRO backend that returns JSON).
 * With no endpoint set, a clearly-labelled demo feed is used so every screen still works.
 * Broker API keys never live on the watch.
 */
object MarketRepository {
    private val demo = DemoMarketFeed(System.currentTimeMillis())

    suspend fun fetch(ctx: Context): MarketSnapshot = withContext(Dispatchers.IO) {
        val p = Prefs.get(ctx)
        val url = p.marketEndpoint
        val watch = p.watchlist.map { it.uppercase() }.toSet()
        val snap = if (url.isBlank()) {
            MarketSnapshot(demo.tick(), demo = true, at = System.currentTimeMillis())
        } else try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            MarketSnapshot(QuoteParser.parse(body), demo = false, at = System.currentTimeMillis())
        } catch (e: Exception) {
            MarketSnapshot(cached(ctx), demo = false, at = System.currentTimeMillis(), error = "Offline - showing last prices")
        }
        val filtered = snap.copy(quotes = snap.quotes.filter { watch.isEmpty() || it.symbol.uppercase() in watch }.ifEmpty { snap.quotes })
        if (filtered.error == null) cache(ctx, filtered.quotes)
        evaluateAlerts(ctx, filtered.quotes)
        filtered
    }

    private fun cache(ctx: Context, q: List<Quote>) {
        Prefs.get(ctx).lastQuotes = q.joinToString("\n") { "${it.symbol}|${it.ltp}|${it.prevClose}" }
    }

    fun cached(ctx: Context): List<Quote> = Prefs.get(ctx).lastQuotes.lines().mapNotNull {
        val p = it.split("|"); if (p.size == 3) Quote(p[0], p[1].toDoubleOrNull() ?: return@mapNotNull null, p[2].toDoubleOrNull() ?: 0.0) else null
    }

    private fun evaluateAlerts(ctx: Context, quotes: List<Quote>) {
        val p = Prefs.get(ctx)
        val engine = p.alerts
        val hits = engine.evaluate(quotes)
        p.alerts = engine
        hits.forEachIndexed { i, h -> Notify.post(ctx, Notify.CH_ALERTS, 100 + (h.alert.id % 1000).toInt() + i, "Price alert", h.message, "markets") }
        runCatching {
            ComplicationDataSourceUpdateRequester.create(ctx, ComponentName(ctx, MarketComplicationService::class.java)).requestUpdateAll()
        }
    }
}

/** Checks prices every 15 minutes in the background (the shortest interval Android allows). */
class MarketAlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val p = Prefs.get(applicationContext)
        if (p.alerts.alerts.isEmpty() || p.powerSaver) return Result.success()
        MarketRepository.fetch(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<MarketAlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("market_alerts", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
