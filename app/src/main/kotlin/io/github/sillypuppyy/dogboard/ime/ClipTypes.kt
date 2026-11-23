package io.github.sillypuppyy.dogboard.ime

import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a copied text is a phone number, an address or a one-time code, as Android's own on-device text
 * classifier sees it ("should we not include a clip type if it can't be 100% reliable?"): a
 * clip gets the type only when the classifier gives it to the whole text with [MIN_CONFIDENCE]; numbers alone
 * are never guessed to be a code. Asked off the main thread, once per text.
 */
object ClipTypes {
    private const val MIN_CONFIDENCE = 0.8f
    private val TYPES = listOf(TextClassifier.TYPE_PHONE, TextClassifier.TYPE_ADDRESS, "otp")
    private val known = ConcurrentHashMap<String, String>()
    private const val NONE = ""

    /** The type found for [text] earlier ("phone", "address", "otp"), or null (none, or not asked yet). */
    fun of(text: String): String? = known[text]?.takeIf { it != NONE }

    fun isKnown(text: String): Boolean = known.containsKey(text)

    internal fun looksLikePhone(t: String): Boolean =
        t.count(Char::isDigit) in 7..15 && t.all { it.isDigit() || it in "+ -.()" } && t.any { it in "+ -.()" }

    /** Asks the system classifier about [text] (blocking: call off the main thread). */
    fun classify(context: Context, text: String): String? {
        known[text]?.let { return it.takeIf { t -> t != NONE } }
        val t = text.trim()
        val type = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || t.isEmpty() || t.length > 200) null else runCatching {
            val tc = context.getSystemService(TextClassificationManager::class.java)?.textClassifier ?: return@runCatching null
            val r = tc.classifyText(android.view.textclassifier.TextClassification.Request.Builder(t, 0, t.length).build())
            (0 until r.entityCount).map { r.getEntity(it) }.filter { it in TYPES }
                .maxByOrNull { r.getConfidenceScore(it) }?.takeIf { r.getConfidenceScore(it) >= MIN_CONFIDENCE }
        }.getOrNull()
        // the classifier calls a bare number a phone number ("482913", emulator 2026-11-20): a phone number also
        // needs a phone's form, 7-15 digits with a +, spaces, dashes, dots or brackets
        val kept = type?.takeIf { it != TextClassifier.TYPE_PHONE || looksLikePhone(t) }
        known[text] = kept ?: NONE
        return kept
    }
}
