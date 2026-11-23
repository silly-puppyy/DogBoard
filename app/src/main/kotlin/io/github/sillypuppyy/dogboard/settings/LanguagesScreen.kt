package io.github.sillypuppyy.dogboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.settings.LangPack
import io.github.sillypuppyy.dogboard.design.settings.LangRow
import io.github.sillypuppyy.dogboard.design.settings.LayoutChoice
import io.github.sillypuppyy.dogboard.ime.PackDownloadQueue
import io.github.sillypuppyy.dogboard.design.settings.SetLanguages
import io.github.sillypuppyy.dogboard.ime.LanguagePack
import io.github.sillypuppyy.dogboard.ime.GeneratedLayouts
import io.github.sillypuppyy.dogboard.ime.LanguageLayouts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The letter layouts' names (tools/layouts/gen_layouts.py; SetLayout's cards). */
private fun layoutLabel(id: String, lang: String): String =
    (GeneratedLayouts.all.firstOrNull { it.id == id && it.language == lang } ?: GeneratedLayouts.all.firstOrNull { it.id == id })?.label ?: "QWERTY"

/** [tag]'s name in itself ("Italiano"), first letter capitalised; [fallback] when the system doesn't know it. */
private fun nativeName(tag: String, fallback: String): String {
    val l = java.util.Locale.forLanguageTag(tag)
    val n = l.getDisplayLanguage(l)
    return if (n.isBlank() || n.equals(l.language, true)) fallback else n.replaceFirstChar { it.titlecase(l) }
}

/** Settings › Languages, live (DogBoard's page in place of FlorisBoard's subtypes page). */
@Composable
fun LanguagesScreen() {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val navController = LocalNavController.current
    io.github.sillypuppyy.dogboard.design.MaterialYouTheme.Remember()
    val subtypeManager by context.subtypeManager()
    val subtypes by subtypeManager.subtypesFlow.collectAsState()
    val active by subtypeManager.activeSubtypeFlow.collectAsState()
    val layoutId by prefs.keyboard.layoutId.collectAsState()
    val memory by prefs.keyboard.layoutByLanguage.collectAsState()
    val index by PackDownloadQueue.index.collectAsState()
    val installedCount by PackDownloadQueue.installed.collectAsState()
    // the language packs, built in and downloaded (read again when one arrives in the background)
    val packs by produceState(emptyList<LanguagePack>(), installedCount) {
        value = withContext(Dispatchers.IO) { LanguagePack.all(context) }
    }
    // no language set: the keyboard types in the default one (was a pink warning)
    val list = subtypes.ifEmpty { listOf(Subtype.DEFAULT) }
    val addedTags = list.map { it.primaryLocale.languageTag() }
    // the dictionaries of added languages that the host has and the phone doesn't: fetched now (again after a failure)
    LaunchedEffect(addedTags) { PackDownloadQueue.ensure(context, addedTags) }
    val withWords = packs.filter { it.hasWords }
    fun hasDictionary(tag: String) = withWords.any { it.tag.equals(tag, true) || it.tag.equals(tag.substringBefore('-'), true) }
    fun layoutOfEntry(s: Subtype) = s.layoutId ?: LanguageLayouts.layoutOf(s.primaryLocale.language, memory) ?: layoutId
    // the alphabet an entry types (its layout's first letter): languages typed together share the keys
    fun script(s: Subtype) = GeneratedLayouts.all.firstOrNull { it.id == layoutOfEntry(s) }?.rows?.firstOrNull()?.firstOrNull { it.isLetter() }
        ?.let { Character.UnicodeScript.of(it.code) } ?: Character.UnicodeScript.LATIN
    fun packName(tag: String, s: Subtype) = packs.firstOrNull { it.tag == tag }?.manifest?.name ?: s.primaryLocale.displayName()
    val rows = list.map { s ->
        val tag = s.primaryLocale.languageTag()
        val layout = layoutOfEntry(s)
        LangRow(
            // the pack's own name (English (US)) where there is a pack, else the locale's
            s.id, packs.firstOrNull { it.tag == tag }?.manifest?.name ?: s.primaryLocale.displayName(), s.primaryLocale.displayName(s.primaryLocale).takeIf { !tag.startsWith("en") },
            // each entry's own layout (a language can be added with several)
            layoutLabel(layout, s.primaryLocale.language),
            layout == "qwerty-en" && tag.startsWith("en"),
            s.id == active.id || list.size == 1,
            // the host has its dictionary and the phone doesn't yet: downloading, or waiting to try again
            gettingDictionary = index != null && !hasDictionary(tag) && PackDownloadQueue.offered(tag) != null,
            together = s.secondaryLocales.map { second -> list.firstOrNull { it.primaryLocale == second }?.let { packName(second.languageTag(), it) } ?: second.displayName() },
            togetherTags = s.secondaryLocales.map { it.languageTag() },
            togetherChoices = if (!hasDictionary(tag)) emptyList() else list
                .filter { o -> o.primaryLocale.language != s.primaryLocale.language && hasDictionary(o.primaryLocale.languageTag()) && script(o) == script(s) }
                .distinctBy { it.primaryLocale.language }
                .map { o -> o.primaryLocale.languageTag() to packName(o.primaryLocale.languageTag(), o) },
            togetherMax = io.github.sillypuppyy.dogboard.ime.EngineLoader.MAX_TOGETHER,
        )
    }
    // what can be added: the packs with words, the host's packs, then every language with layouts; the phone's languages first
    val phone = context.resources.configuration.locales.let { ls -> (0 until ls.size()).map { ls[it].language } }.toSet()
    val choices = buildList {
        for (p in withWords) add(LangPack(p.tag, p.manifest.name, nativeName(p.tag, p.manifest.name), dictionary = true))
        for (e in index?.packs.orEmpty()) if (!hasDictionary(e.tag)) add(LangPack(e.tag, e.name, nativeName(e.tag, e.name), dictionary = true, downloadBytes = e.size))
        val covered = map { it.tag.substringBefore('-') }.toSet()
        for (l in GeneratedLayouts.all.distinctBy { it.language }) if (l.language !in covered)
            add(LangPack(l.language, l.languageName, nativeName(l.language, l.languageName), dictionary = false))
    }.map { it.copy(suggested = it.tag.substringBefore('-') in phone) }.sortedBy { it.name.lowercase() }
    SetLanguages(
        rows, choices,
        layoutsFor = { tag ->
            val lang = tag.substringBefore('-')
            val have = list.filter { it.primaryLocale.language == lang }.map { layoutOfEntry(it) }.toSet()
            GeneratedLayouts.all.filter { it.language == lang }.sortedByDescending { it.default }
                .map { LayoutChoice(it.id, it.label, it.rows, added = it.id in have) }
        },
        onUse = { id -> subtypeManager.switchToSubtypeById(id) },
        onLayout = { navController.navigate(Routes.Settings.Layout) },
        onRemove = { id -> subtypes.firstOrNull { it.id == id }?.let { subtypeManager.removeSubtype(it) } },
        onAdd = { tag, layouts ->
            // one entry per chosen layout; the first language added beside the default keeps the default as its own too
            val new = layouts.map { Subtype(0, FlorisLocale.fromTag(tag), emptyList(), layoutId = it) }.toTypedArray()
            if (subtypes.isEmpty()) subtypeManager.addSubtypes(Subtype.DEFAULT, *new) else subtypeManager.addSubtypes(*new)
            // its dictionary arrives in the background; the language types with its layout meanwhile
            PackDownloadQueue.ensure(context, listOf(tag))
        },
        onBack = { navController.popBackStack() },
        onTogether = { id, tags ->
            subtypes.firstOrNull { it.id == id }?.let { s -> subtypeManager.modifySubtypeWithSameId(s.copy(secondaryLocales = tags.map { FlorisLocale.fromTag(it) })) }
        },
    )
}
