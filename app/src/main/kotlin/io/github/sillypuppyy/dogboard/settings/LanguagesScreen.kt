package io.github.sillypuppyy.dogboard.settings

import androidx.compose.runtime.Composable
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
import io.github.sillypuppyy.dogboard.design.settings.SetLanguages
import io.github.sillypuppyy.dogboard.ime.LanguagePack
import io.github.sillypuppyy.dogboard.ime.GeneratedLayouts
import io.github.sillypuppyy.dogboard.ime.LanguageLayouts
import io.github.sillypuppyy.dogboard.ime.PackDownloads
import io.github.sillypuppyy.dogboard.ime.PackIndex
import io.github.sillypuppyy.dogboard.ime.PackInstaller
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The letter layouts' names (tools/layouts/gen_layouts.py; SetLayout's cards). */
private fun layoutLabel(id: String, lang: String): String =
    (GeneratedLayouts.all.firstOrNull { it.id == id && it.language == lang } ?: GeneratedLayouts.all.firstOrNull { it.id == id })?.label ?: "QWERTY"

/** The languages with layouts but no pack (GeneratedLayouts), added by their bare code ("el"). */
private fun layoutOnly(packs: List<LanguagePack>, added: Set<String>, offered: Set<String> = emptySet()): List<LangPack> {
    val withPack = packs.filter { it.hasWords }.map { it.tag.substringBefore('-') }.toSet() + offered.map { it.substringBefore('-') }
    val addedCodes = added.map { it.substringBefore('-') }.toSet()
    return GeneratedLayouts.all.distinctBy { it.language }.filter { it.language !in withPack }.sortedBy { it.languageName }
        .map { LangPack(it.language, it.languageName, usable = true, added = it.language in addedCodes, dictionary = false) }
}

/** Settings › Languages, live (DogBoard's page in place of FlorisBoard's subtypes page; user 2026-11-19). */
@Composable
fun LanguagesScreen() {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    io.github.sillypuppyy.dogboard.design.MaterialYouTheme.Remember()
    val subtypeManager by context.subtypeManager()
    val subtypes by subtypeManager.subtypesFlow.collectAsState()
    val active by subtypeManager.activeSubtypeFlow.collectAsState()
    val layoutId by prefs.keyboard.layoutId.collectAsState()
    val memory by prefs.keyboard.layoutByLanguage.collectAsState()
    // the language packs, built in and downloaded: usable when they bring a dictionary
    var reload by remember { mutableIntStateOf(0) }
    val packs by produceState(emptyList<LanguagePack>(), reload) {
        value = withContext(Dispatchers.IO) { LanguagePack.all(context) }
    }
    // the packs the download host offers (none until a host is chosen: PackDownloads.BASE_URL)
    val downloads = remember { PackDownloads.of(context) }
    val index by produceState<PackIndex?>(null) {
        value = if (downloads.enabled) withContext(Dispatchers.IO) { downloads.index() } else null
    }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // no language set: the keyboard types in the default one (was a pink warning)
    val list = subtypes.ifEmpty { listOf(Subtype.DEFAULT) }
    val rows = list.map { s ->
        val tag = s.primaryLocale.languageTag()
        LangRow(
            // the pack's own name (English (US)) where there is a pack, else the locale's
            s.id, packs.firstOrNull { it.tag == tag }?.manifest?.name ?: s.primaryLocale.displayName(), s.primaryLocale.displayName(s.primaryLocale).takeIf { !tag.startsWith("en") },
            // DogBoard's layouts are chosen on the Layout page; swipe typing reads QWERTY English (FeelSettings.swipeLayout)
            // each language's own layout (one global layout showed QWERTZ on every row)
            layoutLabel(LanguageLayouts.layoutOf(s.primaryLocale.language, memory) ?: layoutId, s.primaryLocale.language),
            (LanguageLayouts.layoutOf(s.primaryLocale.language, memory) ?: layoutId) == "qwerty-en" && tag.startsWith("en"),
            s.id == active.id || list.size == 1,
        )
    }
    val added = list.map { it.primaryLocale.languageTag() }.toSet()
    val installed = packs.filter { it.hasWords }.map { it.tag }.toSet()
    val offered = index?.packs.orEmpty().filter { it.tag !in installed }
    fun addLanguage(tag: String) {
        // the first language added beside the default keeps the default as a language of its own too
        val new = Subtype(0, FlorisLocale.fromTag(tag), emptyList())
        if (subtypes.isEmpty()) subtypeManager.addSubtypes(Subtype.DEFAULT, new) else subtypeManager.addSubtypes(new)
    }
    SetLanguages(
        rows,
        packs.map { p -> LangPack(p.tag, p.manifest.name, p.hasWords, p.tag in added) } +
            offered.map { e -> LangPack(e.tag, e.name, usable = !busy, added = e.tag in added, downloadBytes = e.size) } +
            layoutOnly(packs, added, offered.map { it.tag }.toSet()),
        onUse = { id -> subtypeManager.switchToSubtypeById(id) },
        onLayout = { navController.navigate(Routes.Settings.Layout) },
        onRemove = { id -> subtypes.firstOrNull { it.id == id }?.let { subtypeManager.removeSubtype(it) } },
        onAdd = { tag ->
            val entry = offered.firstOrNull { it.tag == tag }
            if (entry == null) addLanguage(tag)
            else if (!busy) scope.launch {
                // a pack from the host: downloaded and checked first (PackInstaller), then the language is added
                busy = true
                status = "Downloading ${entry.name}"
                var shown = -1L
                val result = withContext(Dispatchers.IO) {
                    downloads.install(tag) { done, total ->
                        // the callback runs every 64 KB: the line changes once a percent
                        val pct = if (total > 0) done * 100 / total else -1
                        if (pct != shown) { shown = pct; status = "Downloading ${entry.name}, $pct %" }
                    }
                }
                busy = false
                when (result) {
                    is PackInstaller.Result.Installed -> { status = null; reload++; addLanguage(tag) }
                    is PackInstaller.Result.Failed -> status = "Couldn't download ${entry.name}. Check the connection and try again."
                }
            }
        },
        onBack = { navController.popBackStack() },
        status = status,
    )
}
