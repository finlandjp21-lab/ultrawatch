package com.harshvardhan.ultrawatch.tiles

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.harshvardhan.ultrawatch.MainActivity
import com.harshvardhan.ultrawatch.battery.Batteries
import com.harshvardhan.ultrawatch.complication.EnergyCalc
import com.harshvardhan.ultrawatch.core.MarketFormat
import com.harshvardhan.ultrawatch.core.Training
import com.harshvardhan.ultrawatch.markets.MarketRepository

private const val WHITE = 0xFFFFFFFF.toInt()
private const val GREY = 0xFFAAAAAA.toInt()
private const val ORANGE = 0xFFFF7A1A.toInt()
private const val GREEN = 0xFF30D158.toInt()
private const val RED = 0xFFFF453A.toInt()

private fun text(s: String, size: Float, color: Int) = LayoutElementBuilders.Text.Builder()
    .setText(s)
    .setMaxLines(2)
    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build())
    .build()

private fun gap(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

private fun open(ctx: Context, route: String) = ModifiersBuilders.Modifiers.Builder()
    .setClickable(
        ModifiersBuilders.Clickable.Builder().setId(route).setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(ctx.packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(MainActivity.EXTRA_ROUTE, ActionBuilders.AndroidStringExtra.Builder().setValue(route).build())
                    .build(),
            ).build(),
        ).build(),
    ).build()

/** Shared plumbing: subclasses only build a column of content. */
abstract class SimpleTile : TileService() {
    abstract fun content(): List<LayoutElementBuilders.LayoutElement>
    abstract val route: String
    open val refreshMs = 10 * 60_000L

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val col = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        content().forEach { col.addContent(it) }
        col.addContent(gap(6f)).addContent(text("Harshvardhan Technologies", 9f, GREY))
        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setModifiers(open(this, route))
            .addContent(col.build())
            .build()
        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                .setFreshnessIntervalMillis(refreshMs)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build(),
        )
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
}

class HealthTileService : SimpleTile() {
    override val route = "energy"
    override fun content(): List<LayoutElementBuilders.LayoutElement> {
        val e = EnergyCalc.score(this)
        val w = Batteries.watch(this)
        val phone = Batteries.phone(this)
        return listOf(
            text("ENERGY", 11f, GREY),
            text(e?.toString() ?: "--", 40f, ORANGE),
            text(e?.let { Training.energyAdvice(it) } ?: "Track a night's sleep", 12f, WHITE),
            gap(6f),
            text("Watch ${w.percent ?: "--"}%" + (phone?.let { "  ·  Phone ${it.percent}%" } ?: ""), 12f, GREY),
        )
    }
}

class MarketTileService : SimpleTile() {
    override val route = "markets"
    override val refreshMs = 5 * 60_000L
    override fun content(): List<LayoutElementBuilders.LayoutElement> {
        val q = MarketRepository.cached(this).take(3)
        if (q.isEmpty()) return listOf(text("Markets", 14f, WHITE), text("Open to load prices", 12f, GREY))
        return listOf(text("WATCHLIST", 11f, GREY)) + q.map {
            text("${it.symbol}  ${MarketFormat.inr(it.ltp)}  ${MarketFormat.pct(it.changePct)}", 12f, if (it.isUp) GREEN else RED)
        }
    }
}

class SafetyTileService : SimpleTile() {
    override val route = "safety"
    override val refreshMs = 60 * 60_000L
    override fun content(): List<LayoutElementBuilders.LayoutElement> = listOf(
        text("SOS", 40f, RED),
        text("Tap to open safety:\nSOS, siren, check-in", 12f, WHITE),
    )
}
