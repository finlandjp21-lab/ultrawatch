package com.harshvardhan.ultrawatch.core

/** Everything the voice assistant can do. */
sealed class Command {
    data class Call(val who: String) : Command()
    data class Message(val who: String, val text: String?) : Command()
    data class PlayMusic(val query: String?) : Command()
    data object PauseMusic : Command()
    data object NextTrack : Command()
    data object PreviousTrack : Command()
    data class PlayVideo(val query: String?) : Command()
    data class StartWorkout(val type: String) : Command()
    data object StopWorkout : Command()
    data object ShowHeartRate : Command()
    data object ShowSteps : Command()
    data class ShowQuote(val symbol: String) : Command()
    data class SetTimer(val seconds: Int) : Command()
    data object ReadMessages : Command()
    data object Breathe : Command()
    data class Unknown(val raw: String) : Command()
}

/** Rule-based parser for spoken commands (English plus common Hinglish). */
object VoiceCommandParser {

    private val symbolAliases = mapOf(
        "nifty" to "NIFTY 50", "nifty 50" to "NIFTY 50", "nifty fifty" to "NIFTY 50",
        "bank nifty" to "BANKNIFTY", "banknifty" to "BANKNIFTY", "sensex" to "SENSEX",
        "reliance" to "RELIANCE", "tcs" to "TCS", "infosys" to "INFY", "infy" to "INFY",
        "hdfc bank" to "HDFCBANK", "hdfc" to "HDFCBANK",
    )

    fun parse(raw: String): Command {
        val s = raw.lowercase().trim().replace(Regex("[?.!,]+$"), "").replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return Command.Unknown(raw)

        Regex("^(?:send (?:a )?)?(?:message|text|msg|sms)(?: to)? (.+?)(?: (?:saying|that|bolo|ki) (.+))?$")
            .find(s)?.let { m ->
                return Command.Message(clean(m.groupValues[1]), m.groupValues[2].ifBlank { null })
            }
        Regex("^(?:tell) (.+?) (?:that )?(.+)$").find(s)?.let { m ->
            return Command.Message(clean(m.groupValues[1]), m.groupValues[2])
        }
        Regex("^(?:call|phone|dial|ring) (.+)$").find(s)?.let { return Command.Call(clean(it.groupValues[1])) }
        Regex("^(.+?) ko (?:call|phone) (?:karo|kar do|lagao)$").find(s)?.let { return Command.Call(clean(it.groupValues[1])) }

        if (s.matches(Regex("^(?:read|show|check)(?: my)? (?:messages|texts|notifications)$")) || s == "any messages") {
            return Command.ReadMessages
        }
        if (s in setOf("pause", "pause music", "stop music", "stop the music", "gaana band karo")) return Command.PauseMusic
        if (s in setOf("next", "next song", "next track", "skip", "skip song")) return Command.NextTrack
        if (s in setOf("previous", "previous song", "previous track", "go back", "last song")) return Command.PreviousTrack

        Regex("^(?:play|watch) (?:a |the )?video(?: of)?(?: (.+))?$").find(s)?.let {
            return Command.PlayVideo(it.groupValues[1].ifBlank { null })
        }
        Regex("^play(?: some)?(?: music| songs?)?(?: (?:by )?(.+))?$").find(s)?.let {
            val q = it.groupValues[1].ifBlank { null }
            return Command.PlayMusic(q)
        }
        if (s in setOf("music", "gaana bajao", "gaana chalao")) return Command.PlayMusic(null)

        Regex("^(?:start|begin) (?:a )?(run|running|walk|walking|cycle|cycling|ride|workout|gym|yoga|swim|swimming|hike)(?: workout)?$")
            .find(s)?.let { return Command.StartWorkout(normalizeWorkout(it.groupValues[1])) }
        if (s in setOf("stop workout", "end workout", "finish workout", "stop run")) return Command.StopWorkout

        if (s.contains("heart rate") || s.contains("pulse") || s == "bpm") return Command.ShowHeartRate
        if (s.contains("steps") || s.contains("how much did i walk")) return Command.ShowSteps
        if (s.startsWith("breathe") || s.contains("breathing")) return Command.Breathe

        Regex("^(?:set )?(?:a )?timer (?:for )?(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)$").find(s)?.let { m ->
            val n = m.groupValues[1].toInt()
            val unit = m.groupValues[2]
            val secs = when {
                unit.startsWith("s") -> n
                unit.startsWith("m") -> n * 60
                else -> n * 3600
            }
            if (secs > 0) return Command.SetTimer(secs)
        }

        Regex("^(?:how is|how's|what is|what's|show|price of|quote)(?: the)? (.+?)(?: (?:price|doing|trading|today|now|right now))*$").find(s)?.let { m ->
            symbolAliases[m.groupValues[1].trim()]?.let { return Command.ShowQuote(it) }
        }
        symbolAliases[s]?.let { return Command.ShowQuote(it) }

        return Command.Unknown(raw)
    }

    private fun clean(name: String) = name.trim().removePrefix("to ").trim()

    private fun normalizeWorkout(w: String) = when (w) {
        "run", "running" -> "Run"
        "walk", "walking", "hike" -> "Walk"
        "cycle", "cycling", "ride" -> "Cycle"
        "swim", "swimming" -> "Swim"
        "yoga" -> "Yoga"
        else -> "Workout"
    }
}

data class Contact(val id: String, val name: String, val number: String, val starred: Boolean = false)

/** Finds the best contact for a spoken name. Handles nicknames, partial names and small mis-hearings. */
object ContactMatcher {
    private val relationAliases = mapOf(
        "mom" to listOf("mom", "mummy", "maa", "mother", "mum"),
        "dad" to listOf("dad", "papa", "father", "pitaji"),
        "wife" to listOf("wife", "wifey", "patni"),
        "brother" to listOf("brother", "bhai", "bhaiya"),
        "sister" to listOf("sister", "didi", "behen"),
    )

    fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Score 0..100, higher is better. */
    fun score(query: String, contact: Contact): Int {
        val q = normalize(query)
        val n = normalize(contact.name)
        if (q.isEmpty() || n.isEmpty()) return 0
        if (q == n) return 100
        val tokens = n.split(" ")
        // relation keywords, e.g. "mom" matches a contact called "Mummy"
        for ((_, words) in relationAliases) {
            if (q in words && tokens.any { it in words }) return 95
        }
        if (tokens.any { it == q }) return 90 + if (contact.starred) 5 else 0
        if (n.startsWith(q)) return 85
        if (tokens.any { it.startsWith(q) && q.length >= 3 }) return 75
        val best = tokens.minOf { levenshtein(q, it) }
        val whole = levenshtein(q, n)
        val d = minOf(best, whole)
        val limit = if (q.length <= 4) 1 else 2
        return if (d <= limit) 70 - d * 10 else 0
    }

    fun bestMatch(query: String, contacts: List<Contact>, minScore: Int = 50): Contact? =
        contacts.map { it to score(query, it) }
            .filter { it.second >= minScore }
            .sortedWith(compareByDescending<Pair<Contact, Int>> { it.second }.thenByDescending { it.first.starred })
            .firstOrNull()?.first
}

/** Context-aware quick replies for incoming messages. */
object ReplySuggester {
    fun suggest(incoming: String, max: Int = 4): List<String> {
        val s = incoming.lowercase()
        val out = LinkedHashSet<String>()
        fun has(vararg w: String) = w.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(s) }

        if (has("namaste", "namaskar", "pranam")) out += "Namaste 🙏"
        if (has("thanks", "thank you", "thx", "dhanyavad", "shukriya")) out += listOf("You're welcome!", "Anytime 😊")
        if (has("where are you", "kahan ho", "kaha ho", "eta", "reached")) out += listOf("On my way", "Reaching in 10 min", "Running late")
        if (has("call me", "call back", "can we talk", "phone")) out += listOf("Will call you shortly", "Can't talk now, text me")
        if (has("meeting", "meet", "lunch", "dinner", "coffee")) out += listOf("Sounds good", "What time?", "Can we do later?")
        if (has("happy birthday", "congrats", "congratulations")) out += "Thank you so much! 🙏"
        if (has("sorry")) out += "No problem at all"
        if (has("nifty", "market", "stock", "share")) out += listOf("Checking the market", "Will discuss later")
        if (s.trim().endsWith("?") || has("are you", "can you", "will you", "did you", "do you", "kya")) {
            out += listOf("Yes", "No", "Let me check")
        }
        out += listOf("OK 👍", "Talk later")
        return out.take(max)
    }
}
