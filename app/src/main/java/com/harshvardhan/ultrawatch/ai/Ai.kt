package com.harshvardhan.ultrawatch.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.harshvardhan.ultrawatch.core.MiniJson
import com.harshvardhan.ultrawatch.core.TranslatorLanguages
import com.harshvardhan.ultrawatch.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Optional Gemini assistant. Uses your own API key from Google AI Studio; nothing is sent without one. */
object Gemini {
    suspend fun ask(ctx: Context, prompt: String): String = withContext(Dispatchers.IO) {
        val p = Prefs.get(ctx)
        if (p.geminiKey.isBlank()) return@withContext "Add your Gemini API key in Settings to use the AI assistant."
        val body = """{"systemInstruction":{"parts":[{"text":"You are a smartwatch assistant. Answer in at most 3 short sentences that fit a small round screen. Reply in the user's language."}]},"contents":[{"parts":[{"text":${jsonString(prompt)}}]}]}"""
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${p.geminiModel}:generateContent")
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", p.geminiKey)
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
            c.disconnect()
            if (code !in 200..299) return@withContext "Gemini error $code. Check the API key and model name in Settings."
            val root = MiniJson.parse(text) as? Map<*, *>
            val parts = ((((root?.get("candidates") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("content") as? Map<*, *>)?.get("parts") as? List<*>)
            parts?.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }?.joinToString("")?.trim()?.ifBlank { null } ?: "No answer."
        } catch (e: Exception) { "No internet connection." }
    }

    private fun jsonString(s: String) = buildString {
        append('"')
        for (ch in s) when (ch) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
        }
        append('"')
    }
}

/** On-device translation with ML Kit. Each language model downloads once (about 30 MB) and then works offline. */
object TranslatorEngine {
    private val cache = HashMap<String, Translator>()

    private fun translator(from: String, to: String): Translator? {
        val src = TranslateLanguage.fromLanguageTag(from) ?: return null
        val dst = TranslateLanguage.fromLanguageTag(to) ?: return null
        return cache.getOrPut("$from>$to") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
        }
    }

    suspend fun prepare(from: String, to: String): Boolean = runCatching {
        translator(from, to)?.downloadModelIfNeeded(DownloadConditions.Builder().build())?.await(); true
    }.getOrDefault(false)

    suspend fun translate(text: String, from: String, to: String): String {
        if (from == to) return text
        val t = translator(from, to) ?: return text
        return runCatching { t.downloadModelIfNeeded().await(); t.translate(text).await() }.getOrElse { "Translation unavailable: download the language first." }
    }
}

/** Speaks text aloud through the watch speaker or connected earbuds. */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    @Volatile private var ready = false
    override fun onInit(status: Int) { ready = status == TextToSpeech.SUCCESS }
    fun say(text: String, langCode: String) {
        if (!ready) return
        tts.language = Locale.forLanguageTag(TranslatorLanguages.speechTag(langCode))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "uw")
    }
    fun shutdown() { tts.stop(); tts.shutdown() }
}

/**
 * Continuous live transcription using the watch's speech recogniser. Restarts after each
 * phrase so it keeps listening; partial words appear while you speak.
 */
class LiveTranscriber(private val ctx: Context) {
    val partial = MutableStateFlow("")
    val lines = MutableStateFlow<List<String>>(emptyList())
    val listening = MutableStateFlow(false)
    private var recognizer: SpeechRecognizer? = null
    private var lang = "en"
    var onFinal: ((String) -> Unit)? = null

    val available get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(langCode: String) {
        lang = langCode
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply { setRecognitionListener(listener) }
        listening.value = true
        listen()
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, TranslatorLanguages.speechTag(lang))
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        recognizer?.startListening(i)
    }

    fun stop() { listening.value = false; recognizer?.stopListening() }
    fun destroy() { listening.value = false; recognizer?.destroy(); recognizer = null }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(b: Bundle) { partial.value = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty() }
        override fun onResults(b: Bundle) {
            val t = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            partial.value = ""
            if (t.isNotBlank()) { lines.value = (lines.value + t).takeLast(50); onFinal?.invoke(t) }
            if (listening.value) listen()
        }
        override fun onError(error: Int) { if (listening.value && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) listen() else listening.value = false }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }
}
