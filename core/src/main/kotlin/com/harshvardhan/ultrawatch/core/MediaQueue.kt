package com.harshvardhan.ultrawatch.core

import kotlin.random.Random

data class MediaItemInfo(
    val id: String,
    val title: String,
    val artist: String = "",
    val uri: String,
    val durationMs: Long = 0,
    val isVideo: Boolean = false,
)

enum class RepeatMode { OFF, ONE, ALL }

/**
 * Play-queue logic independent of the player. The Android layer mirrors this into ExoPlayer.
 * Shuffle keeps the current item first so toggling shuffle never interrupts playback.
 */
class MediaQueue(items: List<MediaItemInfo>, private val random: Random = Random.Default) {
    private val items = items.toList()
    private var order: List<Int> = items.indices.toList()
    private var pos = 0

    var repeatMode = RepeatMode.OFF
    var shuffle = false
        private set

    val size get() = items.size
    val current: MediaItemInfo? get() = if (items.isEmpty()) null else items[order[pos]]
    val upNext: List<MediaItemInfo> get() = if (items.isEmpty()) emptyList() else order.drop(pos + 1).map { items[it] }

    fun setShuffle(on: Boolean) {
        if (items.isEmpty() || on == shuffle) { shuffle = on; return }
        val cur = order[pos]
        order = if (on) {
            listOf(cur) + items.indices.filter { it != cur }.shuffled(random)
        } else items.indices.toList()
        pos = if (on) 0 else cur
        shuffle = on
    }

    fun jumpTo(id: String): Boolean {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        pos = order.indexOf(idx)
        return true
    }

    /** Called when the user presses next, or a track finishes (auto = true). Returns the item to play, or null to stop. */
    fun next(auto: Boolean = false): MediaItemInfo? {
        if (items.isEmpty()) return null
        if (auto && repeatMode == RepeatMode.ONE) return current
        return if (pos < order.lastIndex) {
            pos++; current
        } else if (repeatMode == RepeatMode.ALL || (!auto && repeatMode == RepeatMode.ONE)) {
            pos = 0; current
        } else if (auto) {
            null
        } else current // manual next on last item with repeat off: stay
    }

    /** Standard behaviour: if more than 3 s into the track, restart it; otherwise go to previous. */
    fun previous(positionMs: Long): Pair<MediaItemInfo?, Boolean> {
        if (items.isEmpty()) return null to false
        if (positionMs > 3_000) return current to true // restart
        return if (pos > 0) { pos--; current to false }
        else if (repeatMode == RepeatMode.ALL) { pos = order.lastIndex; current to false }
        else current to true
    }

    fun cycleRepeat(): RepeatMode {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        return repeatMode
    }

    companion object {
        fun formatTime(ms: Long): String {
            val total = (ms.coerceAtLeast(0) / 1000)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.US, h, m, s) else "%d:%02d".format(java.util.Locale.US, m, s)
        }

        /** Case-insensitive search over title and artist; all words must match. */
        fun search(items: List<MediaItemInfo>, query: String?): List<MediaItemInfo> {
            if (query.isNullOrBlank()) return items
            val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            return items.filter { item ->
                val hay = (item.title + " " + item.artist).lowercase()
                words.all { hay.contains(it) }
            }
        }
    }
}
