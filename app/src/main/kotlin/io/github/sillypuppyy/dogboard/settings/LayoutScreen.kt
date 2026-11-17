package io.github.sillypuppyy.dogboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
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
    )

    suspend fun write(prefs: FlorisPreferenceModel, e: LayoutElements) {
        prefs.keyboard.numberRow.set(e.numRow == "on")
        prefs.keyboard.numberRowLongPress.set(e.numRow == "hints")
        prefs.keyboard.bottomRow.set(e.bottom)
        prefs.keyboard.hintKind.set(e.hints)
        prefs.keyboard.spaceLabel.set(e.space)
        prefs.keyboard.actionKeyWords.set(e.action == "word")
        prefs.keyboard.capsLabels.set(e.caseMode == "caps")
    }

    /** Settings home "Layout style": 0 Simple, 1 Comma & period (Classic), 2 Custom (any mix). */
    fun presetIndex(e: LayoutElements) = when (e) { LayoutElements.SIMPLE -> 0; LayoutElements.CLASSIC -> 1; else -> 2 }
}

/** Settings › Layout, live (design SetLayout.dc.html): the mix-and-match elements, saved as they change. */
@Composable
fun LayoutScreen() {
    val prefs by FlorisPreferenceStore
    io.github.sillypuppyy.dogboard.design.MaterialYouTheme.Remember() // previews in the default look
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    // read the stored values through their flows so the page follows every change
    prefs.keyboard.numberRow.collectAsState().value
    prefs.keyboard.numberRowLongPress.collectAsState().value
    prefs.keyboard.bottomRow.collectAsState().value
    prefs.keyboard.hintKind.collectAsState().value
    prefs.keyboard.spaceLabel.collectAsState().value
    prefs.keyboard.actionKeyWords.collectAsState().value
    prefs.keyboard.capsLabels.collectAsState().value
    SetLayout(SetLayoutState(
        elements = LayoutPrefs.read(prefs),
        onChange = { e -> scope.launch { LayoutPrefs.write(prefs, e) } },
        onBack = { navController.popBackStack() },
    ))
}
