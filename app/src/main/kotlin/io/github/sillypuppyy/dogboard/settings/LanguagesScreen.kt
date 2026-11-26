package io.github.sillypuppyy.dogboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
fun LanguagesScreen(startPage: String? = null, embedded: Boolean = false) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val navController = LocalNavController.current
    io.github.sillypuppyy.dogboard.design.MaterialYouTheme.Remember()
    val subtypeManager by context.subtypeManager()
    val subtypes by subtypeManager.subtypesFlow.collectAsState()
    val active by subtypeManager.activeSubtypeFlow.collectAsState()
    val layoutId by prefs.keyboard.layoutId.collectAsState()
    val memory by prefs.keyboard.layoutByLanguage.collectAsState()
    val scope = rememberCoroutineScope()
    val index by PackDownloadQueue.index.collectAsState()
    val installedCount by PackDownloadQueue.installed.collectAsState()
    val downloading by PackDownloadQueue.progress.collectAsState()
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
    fun nameOfTag(tag: String) = packs.firstOrNull { it.tag == tag }?.manifest?.name ?: FlorisLocale.fromTag(tag).displayName()
    // a combined keyboard is named by its languages: "English and French (France)", "A, B and C"
    fun joined(names: List<String>) = if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()
    fun scriptOfTag(tag: String) = GeneratedLayouts.all.filter { it.language == tag.substringBefore('-') }.sortedByDescending { it.default }
        .firstOrNull()?.rows?.firstOrNull()?.firstOrNull { it.isLetter() }?.let { Character.UnicodeScript.of(it.code) } ?: Character.UnicodeScript.LATIN
    fun offersDictionary(tag: String) = hasDictionary(tag) || index?.packs.orEmpty().any { it.tag.equals(tag, true) || it.tag.equals(tag.substringBefore('-'), true) }
    // a language can join a keyboard when both have a dictionary, share the alphabet and the keyboard has room
    fun canCombine(tag: String, s: Subtype): Boolean {
        val lang = tag.substringBefore('-')
        val langs = listOf(s.primaryLocale.language) + s.secondaryLocales.map { it.language }
        return lang !in langs && offersDictionary(tag) && hasDictionary(s.primaryLocale.languageTag()) && scriptOfTag(tag) == script(s) &&
            langs.size < io.github.sillypuppyy.dogboard.ime.EngineLoader.MAX_TOGETHER
    }
    val regionsNeeded = io.github.sillypuppyy.dogboard.ime.SystemSubtypes.twoRegions(list)
    val rows = list.map { s ->
        val tag = s.primaryLocale.languageTag()
        val layout = layoutOfEntry(s)
        LangRow(
            // each language in its own name, as Gboard ("English + Français"); a region only to tell two apart
            s.id, (listOf(s.primaryLocale) + s.secondaryLocales).joinToString(" + ") { l ->
                io.github.sillypuppyy.dogboard.ime.SystemSubtypes.nativeName(l.languageTag(), l.language in regionsNeeded) },
            null,
            // each entry's own layout (a language can be added with several)
            layoutLabel(layout, s.primaryLocale.language),
            io.github.sillypuppyy.dogboard.engine.swipe.SwipeSupport.layoutSwipes(layout),
            s.id == active.id || list.size == 1,
            // the host has its dictionary and the phone doesn't yet: downloading, or waiting to try again
            gettingDictionary = index != null && !hasDictionary(tag) && PackDownloadQueue.offered(tag) != null,
            dictionaryProgress = PackDownloadQueue.offered(tag)?.let { downloading[it.tag] },
            together = s.secondaryLocales.map { second -> list.firstOrNull { it.primaryLocale == second }?.let { packName(second.languageTag(), it) } ?: second.displayName() },
            togetherTags = s.secondaryLocales.map { it.languageTag() },
            tag = tag,
            // every language's code; the badge stacks them (EN over FR)
            code = (listOf(s.primaryLocale) + s.secondaryLocales).joinToString("+") { it.language.uppercase() },
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
        startPage = startPage,
        embedded = embedded,
        onOpenPage = { p -> navController.navigate(Routes.Settings.LanguagesAt(start = p)) },
        onTogether = { id, tags ->
            subtypes.firstOrNull { it.id == id }?.let { s -> subtypeManager.modifySubtypeWithSameId(s.copy(secondaryLocales = tags.map { FlorisLocale.fromTag(it) })) }
        },
        combineChoices = { id -> list.firstOrNull { it.id == id }?.let { s -> choices.filter { canCombine(it.tag, s) } }.orEmpty() },
        combineTargets = { tag ->
            val targets = list.filter { canCombine(tag, it) }.map { it to joined(listOf(packName(it.primaryLocale.languageTag(), it)) + it.secondaryLocales.map { l -> nameOfTag(l.languageTag()) }) }
            // a language added with two layouts: the layout tells the keyboards apart
            targets.map { (s, name) -> s.id to if (targets.count { it.second == name } > 1) "$name, ${layoutLabel(layoutOfEntry(s), s.primaryLocale.language)}" else name }
        },
        layoutsForAll = { id, tag ->
            val s = list.firstOrNull { it.id == id }
            val langs = (listOfNotNull(s?.primaryLocale?.language) + s?.secondaryLocales.orEmpty().map { it.language } + tag.substringBefore('-')).distinct()
            val current = s?.let { layoutOfEntry(it) }
            // each language brings its own copy of a layout: one per name, a language's own first (French's AZERTY with
            // its ' key, English's Colemak with ;) over the generic copies ("-aa") other languages carry
            langs.flatMap { lang -> GeneratedLayouts.all.filter { it.language == lang }.sortedByDescending { it.default } }
                .sortedWith(compareBy({ it.id != current }, { it.id.endsWith("-aa") })).distinctBy { it.label }.map { LayoutChoice(it.id, it.label, it.rows, added = false) }
        },
        // combined: the language joins the keyboard, which takes the chosen layout; one keyboard of that language alone
        // is folded in (the one with that layout, else the first): another layout of it stays
        onCombine = { id, tag, layout ->
            val s = subtypes.firstOrNull { it.id == id } ?: list.firstOrNull { it.id == id }
            if (s != null) {
                val combined = s.copy(secondaryLocales = s.secondaryLocales + FlorisLocale.fromTag(tag), layoutId = layout)
                subtypeManager.updateSubtypes { now ->
                    // no languages stored yet: the default entry is the one being combined
                    val all = now.ifEmpty { listOf(s) }
                    val alone = all.filter { it.id != id && it.primaryLocale.language == tag.substringBefore('-') && it.secondaryLocales.isEmpty() }
                    val folded = (alone.firstOrNull { it.layoutId == layout } ?: alone.firstOrNull())?.id
                    all.filter { it.id != folded }.map { if (it.id == id) combined else it }
                }
                PackDownloadQueue.ensure(context, listOf(tag))
            }
        },
        // split up: each joined language becomes a keyboard of its own again, with its usual layout
        // a keyboard's page: its own layout (and the live one, when it's the keyboard in use; remembered for its language)
        onSetLayout = { id, layout ->
            val s = subtypes.firstOrNull { it.id == id } ?: list.firstOrNull { it.id == id }
            if (s != null) scope.launch {
                subtypeManager.updateSubtypes { now -> now.ifEmpty { listOf(s) }.map { if (it.id == id) it.copy(layoutId = layout) else it } }
                prefs.keyboard.layoutByLanguage.set(LanguageLayouts.remember(prefs.keyboard.layoutByLanguage.get(), s.primaryLocale.language, layout))
                if (id == active.id) prefs.keyboard.layoutId.set(layout)
            }
        },
        onSplit = { id ->
            subtypes.firstOrNull { it.id == id }?.let { s ->
                val alone = s.secondaryLocales.map { l -> Subtype(0, l, emptyList(), layoutId = LanguageLayouts.layoutOf(l.language, memory)) }
                val single = s.copy(secondaryLocales = emptyList(), layoutId = LanguageLayouts.layoutOf(s.primaryLocale.language, memory) ?: s.layoutId)
                subtypeManager.updateSubtypes { now ->
                    // a language that already has a keyboard of its own isn't added twice
                    val have = now.filter { it.id != id && it.secondaryLocales.isEmpty() }.map { it.primaryLocale.language }.toSet()
                    now.flatMap { if (it.id == id) listOf(single) + alone.filter { a -> a.primaryLocale.language !in have } else listOf(it) }
                }
            }
        },
    )
}
