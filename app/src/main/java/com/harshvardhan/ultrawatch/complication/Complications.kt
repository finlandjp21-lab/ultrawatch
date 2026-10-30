package com.harshvardhan.ultrawatch.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.harshvardhan.ultrawatch.MainActivity
import com.harshvardhan.ultrawatch.core.MarketFormat
import com.harshvardhan.ultrawatch.core.Quote
import com.harshvardhan.ultrawatch.core.Training
import com.harshvardhan.ultrawatch.data.Prefs
import com.harshvardhan.ultrawatch.markets.MarketRepository
import java.time.LocalDate

private fun text(s: String) = PlainComplicationText.Builder(s).build()

private fun open(ctx: Context, route: String) = PendingIntent.getActivity(
    ctx, route.hashCode(),
    Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** NIFTY 50 (or first watchlist symbol) for any watch face. */
class MarketComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, Quote("NIFTY 50", 24412.3, 24351.4))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val q = runCatching { MarketRepository.fetch(this).quotes }.getOrNull().orEmpty().ifEmpty { MarketRepository.cached(this) }
        val pick = q.firstOrNull { it.symbol == "NIFTY 50" } ?: q.firstOrNull() ?: return null
        return build(request.complicationType, pick)
    }

    private fun build(type: ComplicationType, q: Quote): ComplicationData? {
        val pct = MarketFormat.pct(q.changePct)
        val desc = text("${q.symbol} ${MarketFormat.inr(q.ltp)} $pct")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(MarketFormat.inr(q.ltp, 0)), desc)
                .setTitle(text(pct)).setTapAction(open(this, "markets")).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("${q.symbol} ${MarketFormat.inr(q.ltp)}"), desc)
                .setTitle(text(pct)).setTapAction(open(this, "markets")).build()
            else -> null
        }
    }
}

object EnergyCalc {
    /** Today's energy score, or null until there's at least one tracked night. */
    fun score(ctx: Context): Int? {
        val p = Prefs.get(ctx)
        val sleep = p.lastSleepScore.takeIf { it >= 0 } ?: return null
        val hist = p.restHrHistory
        val today = hist.lastOrNull() ?: p.profile.restingHr
        val base = if (hist.size >= 3) hist.dropLast(1).average() else today.toDouble()
        val loads = p.loadSeries(LocalDate.now().toEpochDay())
        val yesterday = loads.getOrElse(loads.size - 2) { 0.0 }
        val usual = loads.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: yesterday
        return Training.energyScore(sleep, today, base, yesterday, usual)
    }
}

class EnergyComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, 78)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, EnergyCalc.score(this))

    private fun build(type: ComplicationType, score: Int?): ComplicationData? {
        val s = score?.toString() ?: "--"
        val desc = text("Energy score $s")
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder((score ?: 0).toFloat(), 0f, 100f, desc)
                .setText(text(s)).setTitle(text("ENERGY")).setTapAction(open(this, "energy")).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(s), desc)
                .setTitle(text("ENRGY")).setTapAction(open(this, "energy")).build()
            else -> null
        }
    }
}
