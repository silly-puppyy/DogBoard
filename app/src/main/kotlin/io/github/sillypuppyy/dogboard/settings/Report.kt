package io.github.sillypuppyy.dogboard.settings

import android.content.Context
import android.os.Build
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.subtypeManager
import io.github.sillypuppyy.dogboard.engine.TypingEngine
import io.github.sillypuppyy.dogboard.ime.EngineLoader
import io.github.sillypuppyy.dogboard.ime.LanguagePack

/**
 * Advanced › Copy a report: what's needed to find a problem on someone's phone without a cable. The app and Android
 * versions, the keyboards, each language pack's version and parts, the word engine's state, the suggestion settings,
 * and the keyboard's own recent log lines (DogBoardPerf and DogBoardJank only: counts, timings and error names, never
 * what was typed). Nothing is sent: it goes to the clipboard to paste where the person chooses.
 */
object Report {
    private const val LOG_LINES = 300

    fun build(context: Context): String = buildString {
        val prefs by FlorisPreferenceStore
        appendLine("DogBoard ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine()
        appendLine("Keyboards:")
        val subtypes = runCatching { context.subtypeManager().value.subtypes }.getOrDefault(emptyList())
        val active = runCatching { context.subtypeManager().value.activeSubtype.id }.getOrNull()
        for (s in subtypes) {
            val langs = (listOf(s.primaryLocale) + s.secondaryLocales).joinToString(" + ") { it.languageTag() }
            appendLine("  $langs, layout ${s.layoutId ?: "default"}${if (s.id == active) " (active)" else ""}")
        }
        appendLine()
        appendLine("Language packs:")
        for (p in runCatching { LanguagePack.all(context) }.getOrDefault(emptyList())) {
            val parts = listOf("lexicon", "ngrams", "lm_model", "rules").filter { p.has(it) }
            appendLine("  ${p.tag} ${p.manifest.version}${if (p.bundled) " built-in" else " downloaded"}: ${parts.joinToString(", ")}")
        }
        appendLine()
        appendLine("Engine: ${if (TypingEngine.isReady) "ready" else "NOT READY"}, language ${EngineLoader.pack?.tag ?: "none"}" +
            EngineLoader.secondaryPacks.takeIf { it.isNotEmpty() }?.let { s -> ", also ${s.joinToString { it.tag }}" }.orEmpty())
        // the layout in use and whether it types a loaded language: when not, the bar and autocorrect are off by design
        val layout = prefs.keyboard.layoutId.get()
        appendLine("Layout $layout, types a loaded language: ${if (io.github.sillypuppyy.dogboard.ime.LanguageLayouts.matchesLoaded(layout)) "yes" else "NO (no suggestions or autocorrect)"}")
        appendLine("Suggestions ${if (prefs.suggestion.enabled.get()) "on" else "off"}, autocorrect ${prefs.correction.autocorrectLevel.get()}, " +
            "neural model ${if (prefs.correction.neuralModel.get()) "on" else "off"}")
        appendLine()
        appendLine("Recent keyboard log:")
        runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "$LOG_LINES", "-s", "DogBoardPerf:V", "DogBoardJank:V").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach { appendLine(it) } }
        }.onFailure { appendLine("(couldn't read the log: ${it.javaClass.simpleName})") }
    }

    /** Builds the report and puts it on the clipboard. */
    fun copy(context: Context) {
        val text = build(context)
        val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("DogBoard report", text))
    }
}
