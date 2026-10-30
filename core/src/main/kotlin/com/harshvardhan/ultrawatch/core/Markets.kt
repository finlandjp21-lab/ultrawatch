package com.harshvardhan.ultrawatch.core

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

data class Quote(val symbol: String, val ltp: Double, val prevClose: Double) {
    val change: Double get() = ltp - prevClose
    val changePct: Double get() = if (prevClose == 0.0) 0.0 else change / prevClose * 100.0
    val isUp: Boolean get() = change >= 0
}

object MarketFormat {
    /** Indian digit grouping: 1,23,45,678.90 */
    fun inr(value: Double, decimals: Int = 2): String {
        val neg = value < 0
        val scale = Math.pow(10.0, decimals.toDouble())
        val rounded = (abs(value) * scale).roundToLong()
        val intPart = rounded / scale.toLong()
        val frac = rounded % scale.toLong()
        val digits = intPart.toString()
        val grouped = if (digits.length <= 3) digits else {
            val last3 = digits.takeLast(3)
            val rest = digits.dropLast(3)
            rest.reversed().chunked(2).joinToString(",").reversed() + "," + last3
        }
        val fracStr = if (decimals > 0) "." + frac.toString().padStart(decimals, '0') else ""
        return (if (neg) "-" else "") + grouped + fracStr
    }

    fun pct(v: Double): String {
        val sign = if (v >= 0) "+" else "-"
        return sign + String.format(java.util.Locale.US, "%.2f", abs(v)) + "%"
    }
}

/**
 * Parses quotes from a JSON endpoint. Accepted shapes:
 *  [ {"symbol":"NIFTY 50","ltp":24500.5,"prevClose":24400}, ... ]
 *  { "data": [ ... same objects ... ] }
 * Field aliases: symbol|s|tradingsymbol, ltp|last_price|price|p, prevClose|close|pc.
 * Malformed items are skipped, not fatal.
 */
object QuoteParser {
    fun parse(json: String): List<Quote> {
        val root = MiniJson.parse(json)
        val items: List<Any?> = when (root) {
            is List<*> -> root
            is Map<*, *> -> (root["data"] as? List<*>) ?: emptyList<Any?>()
            else -> emptyList()
        }
        return items.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val sym = (m["symbol"] ?: m["s"] ?: m["tradingsymbol"]) as? String ?: return@mapNotNull null
            val ltp = num(m["ltp"] ?: m["last_price"] ?: m["price"] ?: m["p"]) ?: return@mapNotNull null
            val pc = num(m["prevClose"] ?: m["close"] ?: m["pc"]) ?: ltp
            if (sym.isBlank() || ltp <= 0) null else Quote(sym.trim(), ltp, pc)
        }
    }

    private fun num(v: Any?): Double? = when (v) {
        is Double -> v
        is String -> v.toDoubleOrNull()
        else -> null
    }
}

enum class AlertCondition { ABOVE, BELOW, MOVE_PCT }

data class PriceAlert(
    val id: Long,
    val symbol: String,
    val condition: AlertCondition,
    val threshold: Double,
    val oneShot: Boolean = true,
)

data class AlertHit(val alert: PriceAlert, val quote: Quote, val message: String)

/**
 * Evaluates alerts on each new batch of quotes. Fires on a *crossing* so an alert
 * doesn't spam while price stays beyond the level. MOVE_PCT fires when |day change %| >= threshold.
 * First observation of a symbol with price already beyond the level fires once.
 */
class AlertEngine(alerts: List<PriceAlert> = emptyList()) {
    private val active = alerts.toMutableList()
    private val lastPrice = HashMap<String, Double>()
    private val firedMove = HashSet<Long>()

    val alerts: List<PriceAlert> get() = active.toList()

    fun add(alert: PriceAlert) { active.add(alert) }
    fun remove(id: Long) { active.removeAll { it.id == id }; firedMove.remove(id) }

    fun evaluate(quotes: List<Quote>): List<AlertHit> {
        val hits = ArrayList<AlertHit>()
        val bySymbol = quotes.associateBy { it.symbol.uppercase() }
        for (a in active.toList()) {
            val q = bySymbol[a.symbol.uppercase()] ?: continue
            val prev = lastPrice[q.symbol.uppercase()]
            val fired = when (a.condition) {
                AlertCondition.ABOVE -> q.ltp >= a.threshold && (prev == null || prev < a.threshold)
                AlertCondition.BELOW -> q.ltp <= a.threshold && (prev == null || prev > a.threshold)
                AlertCondition.MOVE_PCT -> abs(q.changePct) >= a.threshold && a.id !in firedMove
            }
            if (fired) {
                if (a.condition == AlertCondition.MOVE_PCT) firedMove.add(a.id)
                hits.add(AlertHit(a, q, describe(a, q)))
                if (a.oneShot) active.remove(a)
            }
        }
        for (q in quotes) lastPrice[q.symbol.uppercase()] = q.ltp
        return hits
    }

    private fun describe(a: PriceAlert, q: Quote): String = when (a.condition) {
        AlertCondition.ABOVE -> "${q.symbol} crossed above ${MarketFormat.inr(a.threshold)} (now ${MarketFormat.inr(q.ltp)})"
        AlertCondition.BELOW -> "${q.symbol} fell below ${MarketFormat.inr(a.threshold)} (now ${MarketFormat.inr(q.ltp)})"
        AlertCondition.MOVE_PCT -> "${q.symbol} moved ${MarketFormat.pct(q.changePct)} today"
    }

    /** Persist as lines: id|symbol|condition|threshold|oneShot */
    fun encode(): String = active.joinToString("\n") {
        "${it.id}|${it.symbol}|${it.condition}|${it.threshold}|${it.oneShot}"
    }

    companion object {
        fun decode(text: String): AlertEngine = AlertEngine(text.lines().mapNotNull { line ->
            val p = line.split("|")
            if (p.size != 5) return@mapNotNull null
            val id = p[0].toLongOrNull() ?: return@mapNotNull null
            val cond = runCatching { AlertCondition.valueOf(p[2]) }.getOrNull() ?: return@mapNotNull null
            val thr = p[3].toDoubleOrNull() ?: return@mapNotNull null
            PriceAlert(id, p[1], cond, thr, p[4] == "true")
        })
    }
}

/** Deterministic random-walk feed used when no endpoint is configured. Clearly labelled DEMO in the UI. */
class DemoMarketFeed(seed: Long = 42L) {
    private val rnd = Random(seed)
    private val base = linkedMapOf(
        "NIFTY 50" to 24350.0,
        "BANKNIFTY" to 52100.0,
        "SENSEX" to 80150.0,
        "RELIANCE" to 2890.0,
        "HDFCBANK" to 1720.0,
        "TCS" to 4120.0,
        "INFY" to 1880.0,
    )
    private val current = LinkedHashMap(base)

    fun tick(): List<Quote> {
        for ((k, v) in current) {
            val drift = (rnd.nextDouble() - 0.5) * 0.004 // +-0.2 % per tick
            current[k] = (v * (1 + drift) * 100).roundToLong() / 100.0
        }
        return current.map { (k, v) -> Quote(k, v, base.getValue(k)) }
    }
}
