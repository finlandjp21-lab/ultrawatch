package com.harshvardhan.ultrawatch.face

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasComplicationFactory
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.complications.ComplicationSlotBounds
import androidx.wear.watchface.complications.DefaultComplicationDataSourcePolicy
import androidx.wear.watchface.complications.SystemDataSources
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.rendering.CanvasComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import androidx.wear.watchface.style.CurrentUserStyleRepository
import com.harshvardhan.ultrawatch.R
import com.harshvardhan.ultrawatch.complication.MarketComplicationService
import com.harshvardhan.ultrawatch.core.Environment
import com.harshvardhan.ultrawatch.core.FaceConfig
import com.harshvardhan.ultrawatch.core.FaceStyle
import com.harshvardhan.ultrawatch.core.SlotPosition
import com.harshvardhan.ultrawatch.core.WatchGeometry
import com.harshvardhan.ultrawatch.data.Prefs
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The UltraWatch Luxe watch face. Its look comes from the in-app Face Designer
 * (style, colours, ticks, logo, which complication slots show). Complication data
 * sources can be changed from the system watch-face editor as usual.
 */
class UltraWatchFaceService : WatchFaceService() {

    override fun createComplicationSlotsManager(currentUserStyleRepository: CurrentUserStyleRepository): ComplicationSlotsManager {
        val factory = CanvasComplicationFactory { watchState, listener ->
            CanvasComplicationDrawable(ComplicationDrawable(this), watchState, listener)
        }
        val types = listOf(ComplicationType.RANGED_VALUE, ComplicationType.SHORT_TEXT, ComplicationType.MONOCHROMATIC_IMAGE, ComplicationType.SMALL_IMAGE)
        val slots = SlotPosition.values().map { pos ->
            val b = WatchGeometry.slotBounds(pos)
            val policy = when (pos) {
                SlotPosition.TOP -> DefaultComplicationDataSourcePolicy(
                    ComponentName(this, MarketComplicationService::class.java), ComplicationType.SHORT_TEXT,
                    SystemDataSources.DATA_SOURCE_DAY_OF_WEEK, ComplicationType.SHORT_TEXT)
                SlotPosition.LEFT -> DefaultComplicationDataSourcePolicy(SystemDataSources.DATA_SOURCE_WATCH_BATTERY, ComplicationType.RANGED_VALUE)
                SlotPosition.RIGHT -> DefaultComplicationDataSourcePolicy(SystemDataSources.DATA_SOURCE_STEP_COUNT, ComplicationType.SHORT_TEXT)
                SlotPosition.BOTTOM -> DefaultComplicationDataSourcePolicy(SystemDataSources.DATA_SOURCE_DAY_AND_DATE, ComplicationType.SHORT_TEXT)
            }
            ComplicationSlot.createRoundRectComplicationSlotBuilder(
                pos.ordinal, factory, types, policy, ComplicationSlotBounds(RectF(b[0], b[1], b[2], b[3])),
            ).build()
        }
        return ComplicationSlotsManager(slots, currentUserStyleRepository)
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        val renderer = FaceRenderer(this, surfaceHolder, watchState, complicationSlotsManager, currentUserStyleRepository)
        return WatchFace(WatchFaceType.ANALOG, renderer)
    }
}

class FaceAssets : Renderer.SharedAssets { override fun onDestroy() {} }

private class FaceRenderer(
    private val service: WatchFaceService,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    private val slots: ComplicationSlotsManager,
    repo: CurrentUserStyleRepository,
) : Renderer.CanvasRenderer2<FaceAssets>(surfaceHolder, repo, watchState, CanvasType.HARDWARE, 16L, false) {

    private val prefs = Prefs.get(service)
    private val logo: Bitmap? = runCatching { BitmapFactory.decodeResource(service.resources, R.drawable.hvt_logo) }.getOrNull()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digital = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override suspend fun createSharedAssets() = FaceAssets()

    private fun col(v: Long) = v.toInt()

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: FaceAssets) {
        val cfg: FaceConfig = prefs.faceConfig
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        val night = prefs.nightModeForced || (prefs.nightModeAuto && Environment.isNight(zonedDateTime.hour))
        val accent = when { night -> Color.rgb(200, 20, 20); ambient -> Color.LTGRAY; else -> col(cfg.accent) }
        val hand = if (night) Color.rgb(150, 0, 0) else if (ambient) Color.GRAY else Color.WHITE
        val dim = if (night) Color.rgb(90, 0, 0) else Color.rgb(85, 85, 90)

        canvas.drawColor(if (ambient) Color.BLACK else col(cfg.background))
        val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY(); val r = bounds.width() / 2f

        // ticks
        if (cfg.style != FaceStyle.DIGITAL_BOLD && cfg.style != FaceStyle.TRADER) {
            for ((a, major) in WatchGeometry.ticks(cfg.tickDensity)) {
                if (ambient && !major) continue
                val (x1, y1) = WatchGeometry.pointAt(cx.toDouble(), cy.toDouble(), r * (if (major) 0.86 else 0.92), a)
                val (x2, y2) = WatchGeometry.pointAt(cx.toDouble(), cy.toDouble(), r * 0.97, a)
                p.color = if (major) accent else dim
                p.strokeWidth = if (major) r * 0.025f else r * 0.008f
                canvas.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), p)
            }
        }

        // complications the designer turned on
        if (!ambient || cfg.style == FaceStyle.TRADER) {
            for ((_, slot) in slots.complicationSlots) {
                val pos = SlotPosition.values()[slot.id]
                if (pos in cfg.slots.keys && slot.enabled) slot.render(canvas, zonedDateTime, renderParameters)
            }
        }

        // brand mark when the top slot is free
        if (cfg.showLogo && !ambient && SlotPosition.TOP !in cfg.slots.keys) logo?.let {
            val s = r * 0.28f
            canvas.drawBitmap(it, null, RectF(cx - s / 2, cy - r * 0.62f, cx + s / 2, cy - r * 0.62f + s), p)
        }

        when (cfg.style) {
            FaceStyle.DIGITAL_BOLD, FaceStyle.TRADER -> drawDigital(canvas, cx, cy, r, zonedDateTime, accent, ambient, cfg)
            else -> drawHands(canvas, cx, cy, r, zonedDateTime, accent, hand, ambient, cfg)
        }
    }

    private fun drawDigital(c: Canvas, cx: Float, cy: Float, r: Float, t: ZonedDateTime, accent: Int, ambient: Boolean, cfg: FaceConfig) {
        digital.color = if (ambient) Color.WHITE else accent
        digital.textSize = r * 0.48f
        c.drawText(t.format(DateTimeFormatter.ofPattern("HH:mm")), cx, cy + r * 0.16f, digital)
        if (cfg.showSeconds && !ambient) {
            small.color = Color.LTGRAY; small.textSize = r * 0.12f
            c.drawText(t.format(DateTimeFormatter.ofPattern("ss")), cx + r * 0.62f, cy + r * 0.16f, small)
        }
        small.color = Color.GRAY; small.textSize = r * 0.1f
        c.drawText(t.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).uppercase(), cx, cy - r * 0.28f, small)
    }

    private fun drawHands(c: Canvas, cx: Float, cy: Float, r: Float, t: ZonedDateTime, accent: Int, hand: Int, ambient: Boolean, cfg: FaceConfig) {
        val ms = t.nano / 1_000_000
        fun line(angle: Double, len: Float, width: Float, color: Int, tail: Float = 0f) {
            val (x, y) = WatchGeometry.pointAt(cx.toDouble(), cy.toDouble(), (r * len).toDouble(), angle)
            val (tx, ty) = WatchGeometry.pointAt(cx.toDouble(), cy.toDouble(), (r * tail).toDouble(), angle + 180)
            p.color = color; p.strokeWidth = width; p.strokeCap = Paint.Cap.ROUND
            c.drawLine(tx.toFloat(), ty.toFloat(), x.toFloat(), y.toFloat(), p)
        }
        line(WatchGeometry.hourAngle(t.hour, t.minute, t.second), 0.5f, r * 0.045f, hand)
        line(WatchGeometry.minuteAngle(t.minute, t.second, ms), 0.78f, r * 0.03f, hand)
        if (cfg.showSeconds && !ambient) {
            val secColor = if (cfg.style == FaceStyle.CHRONO_SPORT) col(cfg.secondary) else accent
            line(WatchGeometry.secondAngle(t.second, ms), 0.88f, r * 0.012f, secColor, tail = 0.15f)
        }
        p.color = accent
        c.drawCircle(cx, cy, r * 0.035f, p)
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: FaceAssets) {
        canvas.drawColor(renderParameters.highlightLayer!!.backgroundTint)
        for ((_, slot) in slots.complicationSlots) if (slot.enabled) slot.renderHighlightLayer(canvas, zonedDateTime, renderParameters)
    }
}
