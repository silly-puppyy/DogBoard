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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The letter layouts' names (tools/layouts/gen_layouts.py ids; SetLayout's cards). */
private val LAYOUT_NAMES = mapOf("qwerty-en" to "QWERTY", "dvorak-en" to "Dvorak", "colemak-en" to "Colemak", "qwertz-de" to "QWERTZ", "azerty-fr" to "AZERTY")

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
    // the bundled language packs: usable when they bring a dictionary
    val packs by produceState(emptyList<LanguagePack>()) {
        value = withContext(Dispatchers.IO) {
            (context.assets.list("dogboard/packs") ?: emptyArray()).sorted().mapNotNull { LanguagePack.open(context.assets, it) }
        }
    }
    // no language set: the keyboard types in the default one (was a pink warning)
    val list = subtypes.ifEmpty { listOf(Subtype.DEFAULT) }
    val rows = list.map { s ->
        val tag = s.primaryLocale.languageTag()
        LangRow(
            // the pack's own name (English (US)) where there is a pack, else the locale's
            s.id, packs.firstOrNull { it.tag == tag }?.manifest?.name ?: s.primaryLocale.displayName(), s.primaryLocale.displayName(s.primaryLocale).takeIf { !tag.startsWith("en") },
            // DogBoard's layouts are chosen on the Layout page; swipe typing reads QWERTY English (FeelSettings.swipeLayout)
            LAYOUT_NAMES[layoutId] ?: "QWERTY", layoutId == "qwerty-en" && tag.startsWith("en"),
            s.id == active.id || list.size == 1,
        )
    }
    val added = list.map { it.primaryLocale.languageTag() }.toSet()
    SetLanguages(
        rows,
        packs.map { p -> LangPack(p.tag, p.manifest.name, p.has("lexicon"), p.tag in added) },
        onUse = { id -> subtypeManager.switchToSubtypeById(id) },
        onLayout = { navController.navigate(Routes.Settings.Layout) },
        onRemove = { id -> subtypes.firstOrNull { it.id == id }?.let { subtypeManager.removeSubtype(it) } },
        onAdd = { tag ->
            // the first language added beside the default keeps the default as a language of its own too
            if (subtypes.isEmpty()) subtypeManager.addSubtype(Subtype.DEFAULT)
            subtypeManager.addSubtype(Subtype(0, FlorisLocale.fromTag(tag), emptyList()))
        },
        onBack = { navController.popBackStack() },
    )
}
