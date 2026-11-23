package io.github.sillypuppyy.dogboard.ime

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.lib.FlorisLocale
import java.io.File

/**
 * The first start in another language (user 2026-11-23: "maybe the user is not English"): as Gboard does, the phone's
 * own languages become the keyboard's, each with its default layout (built in for ~300 languages), and their dictionaries
 * download in the background (PackDownloadQueue). Once per install; a user with saved languages is never changed.
 */
object FirstLanguages {
    /**
     * The keyboard languages for the phone's [system] languages (in the phone's order): those with a layout, one per
     * language; English as "en-US" (the built-in pack). Empty when the phone is in English only: the default stays.
     */
    fun choose(system: List<java.util.Locale>, hasLayout: (String) -> Boolean): List<String> {
        val tags = system.map { it.language.lowercase() }.filter { it.isNotEmpty() && hasLayout(it) }.distinct()
            .map { if (it == "en") LanguagePack.DEFAULT_TAG else it }
        return if (tags.all { it == LanguagePack.DEFAULT_TAG }) emptyList() else tags
    }

    fun run(context: Context) {
        val app = context.applicationContext
        val marker = File(app.filesDir, "dogboard/first_languages_done")
        if (marker.exists()) return
        marker.parentFile?.mkdirs()
        val prefs by FlorisPreferenceStore
        if (prefs.localization.subtypes.get().isBlank()) {
            val locales = app.resources.configuration.locales.let { ls -> (0 until ls.size()).map { ls[it] } }
            val tags = choose(locales) { lang -> LanguageLayouts.default.containsKey(lang) }
            if (tags.isNotEmpty()) {
                val manager = dev.patrickgold.florisboard.FlorisApplication.getHack().subtypeManager.value
                manager.addSubtypes(*tags.map { Subtype(0, FlorisLocale.fromTag(it), emptyList()) }.toTypedArray())
                android.util.Log.i("DogBoardPerf", "first start: languages ${tags.joinToString()}")
                PackDownloadQueue.ensure(app, tags)
            }
        }
        marker.createNewFile()
    }
}
