/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard

import android.service.textservice.SpellCheckerService
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.nlp.SpellingLanguageMode
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.devtools.LogTopic
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.florisboard.lib.kotlin.map

class FlorisSpellCheckerService : SpellCheckerService() {
    private val prefs by FlorisPreferenceStore
    private val dictionaryManager get() = DictionaryManager.default()
    private val nlpManager by nlpManager()
    private val subtypeManager by subtypeManager()

    override fun onCreate() {
        flogInfo(LogTopic.SPELL_EVENTS)

        super.onCreate()
        dictionaryManager.loadUserDictionariesIfNecessary()
        // DogBoard: spelling comes from the typing engine; load it if the keyboard hasn't (until
        // then words are "unspecified", not flagged), in the keyboard's saved languages: alone the
        // spell checker loaded the built-in English, so an app opened before the keyboard had every
        // Bulgarian word underlined (2026-11-25)
        kotlin.concurrent.thread(name = "dogboard-spell-load") {
            val loader = io.github.sillypuppyy.dogboard.ime.EngineLoader
            loader.ensureLoaded(this)
            val active = subtypeManager.activeSubtype
            loader.switchTo(this, active.primaryLocale.languageTag())
            loader.setSecondaries(this, active.secondaryLocales.map { it.languageTag() })
        }
    }

    override fun createSession(): Session {
        flogInfo(LogTopic.SPELL_EVENTS)

        return FlorisSpellCheckerSession()
    }

    override fun onDestroy() {
        flogInfo(LogTopic.SPELL_EVENTS)

        super.onDestroy()
    }

    private inner class FlorisSpellCheckerSession : Session() {
        private var cachedSpellingSubtype: Subtype? = null

        override fun onCreate() {
            flogInfo(LogTopic.SPELL_EVENTS) { "Session requested locale: $locale" }

            setupSpellingIfNecessary()
        }

        private fun setupSpellingIfNecessary() {
            val evaluatedSubtype = when (prefs.spelling.languageMode.get()) {
                SpellingLanguageMode.USE_KEYBOARD_SUBTYPES -> {
                    subtypeManager.activeSubtype
                }
                else -> {
                    Subtype.DEFAULT.copy(primaryLocale = FlorisLocale.default())
                }
            }

            if (evaluatedSubtype != cachedSpellingSubtype) {
                cachedSpellingSubtype = evaluatedSubtype
                nlpManager.preload(evaluatedSubtype)
            }
            flogInfo(LogTopic.SPELL_EVENTS) {
                "Session actual locale: ${cachedSpellingSubtype?.primaryLocale?.languageTag()}"
            }
        }

        private fun spellMultiple(
            spellingSubtype: Subtype,
            textInfos: Array<out TextInfo>,
            suggestionsLimit: Int,
        ): Array<SpellingResult> = runBlocking {
            val retInfos = Array(textInfos.size) { n ->
                val word = textInfos[n].text ?: ""
                async { nlpManager.spell(spellingSubtype, word, emptyList(), emptyList(), suggestionsLimit) }
            }
            Array(textInfos.size) { n ->
                retInfos[n].await().apply {
                    suggestionsInfo.setCookieAndSequence(textInfos[n].cookie, textInfos[n].sequence)
                }
            }
        }

        override fun onGetSuggestions(textInfo: TextInfo?, suggestionsLimit: Int): SuggestionsInfo {
            flogInfo(LogTopic.SPELL_EVENTS) { "text=${textInfo?.text}, limit=$suggestionsLimit" }

            textInfo?.text ?: return SpellingResult.unspecified().suggestionsInfo
            setupSpellingIfNecessary()
            val spellingSubtype = cachedSpellingSubtype ?: return SpellingResult.unspecified().suggestionsInfo

            return runBlocking {
                nlpManager
                    .spell(spellingSubtype, textInfo.text, emptyList(), emptyList(), suggestionsLimit)
                    .sendToDebugOverlayIfEnabled(textInfo)
                    .suggestionsInfo
            }
        }

        override fun onGetSuggestionsMultiple(
            textInfos: Array<out TextInfo>?,
            suggestionsLimit: Int,
            sequentialWords: Boolean,
        ): Array<SuggestionsInfo> {
            flogInfo(LogTopic.SPELL_EVENTS)

            textInfos ?: return emptyArray()
            setupSpellingIfNecessary()
            val spellingSubtype = cachedSpellingSubtype ?: return emptyArray()

            return spellMultiple(spellingSubtype, textInfos, suggestionsLimit)
                .sendToDebugOverlayIfEnabled(textInfos)
                .map { it.suggestionsInfo }
        }

        override fun onGetSentenceSuggestionsMultiple(
            textInfos: Array<out TextInfo>?,
            suggestionsLimit: Int,
        ): Array<SentenceSuggestionsInfo> {
            flogInfo(LogTopic.SPELL_EVENTS)

            // the words' spelling (Android's default splits the sentences into words and asks onGetSuggestionsMultiple),
            // DogBoard: and agreement errors as grammar ("le maison" -> "la maison": the blue underline, Android 12+)
            val words = super.onGetSentenceSuggestionsMultiple(textInfos, suggestionsLimit)
            if (textInfos == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || !prefs.spelling.grammarCheck.get()) return words
            return Array(words.size) { i ->
                val base = words[i]
                val info = textInfos.getOrNull(i) ?: return@Array base
                val flags = io.github.sillypuppyy.dogboard.engine.TypingEngine.grammarFlags(info.text ?: "")
                if (flags.isEmpty() || base == null) return@Array base
                // the default lists every word, correct ones too: a grammar flag replaces its word's entry unless that
                // entry is a typo (a misspelling comes first); a flagged word the default did not list is added
                fun grammar(f: io.github.sillypuppyy.dogboard.engine.GrammarCheck.Flag) = SuggestionsInfo(
                    SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS,
                    arrayOf(f.suggestion), info.cookie, info.sequence)
                val byStart = flags.associateBy { it.start }
                val infos = ArrayList<SuggestionsInfo>(); val offsets = ArrayList<Int>(); val lengths = ArrayList<Int>()
                val used = HashSet<Int>()
                for (k in 0 until base.suggestionsCount) {
                    val entry = base.getSuggestionsInfoAt(k); val off = base.getOffsetAt(k)
                    val f = byStart[off]
                    val typo = entry != null && entry.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0
                    if (f != null && !typo) { infos.add(grammar(f)); offsets.add(off); lengths.add(f.length); used.add(off) }
                    else { infos.add(entry); offsets.add(off); lengths.add(base.getLengthAt(k)); if (f != null) used.add(off) }
                }
                for (f in flags) if (f.start !in used) { infos.add(grammar(f)); offsets.add(f.start); lengths.add(f.length) }
                SentenceSuggestionsInfo(infos.toTypedArray(), offsets.toIntArray(), lengths.toIntArray())
            }
        }

        override fun onCancel() {
            flogInfo(LogTopic.SPELL_EVENTS)

            super.onCancel()
            if (prefs.devtools.showSpellingOverlay.get()) {
                nlpManager.clearDebugOverlay()
            }
        }

        override fun onClose() {
            flogInfo(LogTopic.SPELL_EVENTS)

            super.onClose()
            if (prefs.devtools.showSpellingOverlay.get()) {
                nlpManager.clearDebugOverlay()
            }
        }

        fun SpellingResult.sendToDebugOverlayIfEnabled(
            textInfo: TextInfo,
        ): SpellingResult {
            if (prefs.devtools.showSpellingOverlay.get()) {
                nlpManager.addToDebugOverlay(textInfo.text, this)
            }
            return this
        }

        fun Array<SpellingResult>.sendToDebugOverlayIfEnabled(
            textInfos: Array<out TextInfo>,
        ): Array<SpellingResult> {
            if (prefs.devtools.showSpellingOverlay.get()) {
                for ((n, info) in this.withIndex()) {
                    nlpManager.addToDebugOverlay(textInfos[n].text, info)
                }
            }
            return this
        }
    }
}
