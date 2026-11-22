package io.github.sillypuppyy.dogboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.settings.LayoutElements
import io.github.sillypuppyy.dogboard.design.settings.SetLayout
import io.github.sillypuppyy.dogboard.design.settings.SetLayoutState
import kotlinx.coroutines.launch

/** The layout elements as stored (FlorisPreferenceModel.keyboard): numberRow / numberRowLongPress, bottomRow, hintKind, spaceLabel, actionKeyWords, capsLabels. */
object LayoutPrefs {
    fun read(prefs: FlorisPreferenceModel) = LayoutElements(
        numRow = if (prefs.keyboard.numberRow.get()) "on" else if (prefs.keyboard.numberRowLongPress.get()) "hints" else "off",
        bottom = prefs.keyboard.bottomRow.get(),
        hints = prefs.keyboard.hintKind.get(),
        space = prefs.keyboard.spaceLabel.get(),
        action = if (prefs.keyboard.actionKeyWords.get()) "word" else "icon",
        caseMode = if (prefs.keyboard.capsLabels.get()) "caps" else "shift",
        symbols = prefs.keyboard.symbolPages.get(),
        pill = prefs.keyboard.pillKeys.get(),
        symbolsReturn = prefs.keyboard.symbolsReturn.get(),
    )

    suspend fun write(prefs: FlorisPreferenceModel, e: LayoutElements) {
        prefs.keyboard.numberRow.set(e.numRow == "on")
        prefs.keyboard.numberRowLongPress.set(e.numRow == "hints")
        prefs.keyboard.bottomRow.set(e.bottom)
        prefs.keyboard.hintKind.set(e.hints)
        prefs.keyboard.spaceLabel.set(e.space)
        prefs.keyboard.actionKeyWords.set(e.action == "word")
        prefs.keyboard.capsLabels.set(e.caseMode == "caps")
        prefs.keyboard.symbolPages.set(e.symbols)
        prefs.keyboard.pillKeys.set(e.pill)
        prefs.keyboard.symbolsReturn.set(e.symbolsReturn)
    }

    /** Settings home "Layout style": 0 iPhone-style, 1 Android-style, 2 Custom (any mix). */
    fun presetIndex(e: LayoutElements) = when (e) { LayoutElements.SIMPLE -> 0; LayoutElements.CLASSIC -> 1; else -> 2 }
}

/** Settings › Layout, live (design SetLayout.dc.html): the mix-and-match elements, saved as they change. */
@Composable
fun LayoutScreen() {
    val prefs by FlorisPreferenceStore
    io.github.sillypuppyy.dogboard.design.MaterialYouTheme.Remember() // previews in the default look
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val subtypeManager by LocalContext.current.subtypeManager()
    val subtypes by subtypeManager.subtypesFlow.collectAsState()
    // read the stored values through their flows so the page follows every change
    prefs.keyboard.numberRow.collectAsState().value
    prefs.keyboard.numberRowLongPress.collectAsState().value
    prefs.keyboard.bottomRow.collectAsState().value
    prefs.keyboard.hintKind.collectAsState().value
    prefs.keyboard.spaceLabel.collectAsState().value
    prefs.keyboard.actionKeyWords.collectAsState().value
    prefs.keyboard.capsLabels.collectAsState().value
    prefs.keyboard.symbolPages.collectAsState().value
    prefs.keyboard.pillKeys.collectAsState().value
    prefs.keyboard.symbolsReturn.collectAsState().value
    SetLayout(SetLayoutState(
        elements = LayoutPrefs.read(prefs),
        onChange = { e -> scope.launch { LayoutPrefs.write(prefs, e) } },
        onBack = { navController.popBackStack() },
        layoutId = prefs.keyboard.layoutId.collectAsState().value,
        languages = subtypes.map { it.primaryLocale.language }.ifEmpty { listOf("en") },
        onLayoutId = { id -> scope.launch { prefs.keyboard.layoutId.set(id) } },
        heightPercent = prefs.keyboard.heightPercent.collectAsState().value,
        onHeightPercent = { v -> scope.launch { prefs.keyboard.heightPercent.set(v) } },
    ))
}
