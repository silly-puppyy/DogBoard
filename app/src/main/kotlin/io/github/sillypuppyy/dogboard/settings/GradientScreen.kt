package io.github.sillypuppyy.dogboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import io.github.sillypuppyy.dogboard.design.GradientSpec
import io.github.sillypuppyy.dogboard.design.ThemeChoice
import io.github.sillypuppyy.dogboard.design.UserThemes
import io.github.sillypuppyy.dogboard.design.settings.SetGradient
import io.github.sillypuppyy.dogboard.design.settings.SetGradientState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Themes › New gradient (or a saved gradient, [id]): nothing is stored until Save, which saves the
 * theme into [pack] (empty: the default pack) and puts it to use.
 */
@Composable
fun GradientScreen(id: String, pack: String, solid: Boolean = false) {
    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    io.github.sillypuppyy.dogboard.design.MaterialYouTheme.Remember()
    var name by remember { mutableStateOf("") }
    // a bright and a dark version (user 2026-11-19), each its own colours and labels; the direction is shared
    var versions by remember { mutableStateOf("both") }
    var tabDark by remember { mutableStateOf(false) }
    var darkColors by remember { mutableStateOf(listOf("#2b2350", "#9e3b56")) }
    var brightColors by remember { mutableStateOf(GradientSpec.derive(listOf("#2b2350", "#9e3b56"), toDark = false)) }
    var brightLabels by remember { mutableStateOf("auto") }
    var darkLabels by remember { mutableStateOf("auto") }
    var slot by remember { mutableStateOf(0) }
    var angle by remember { mutableStateOf(180) }
    var oldSpec by remember { mutableStateOf<String?>(null) }
    fun labelsOf(g: GradientSpec, dark: Boolean) = if (g.white == (if (GradientSpec.weakest(g.colors, dark) >= 4.5) dark else GradientSpec.autoWhite(g.colors))) "auto" else if (g.white) "white" else "dark"
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            val t = id.takeIf { it.isNotEmpty() }?.let { UserThemes.theme(context, it) }
            val pair = t?.spec?.let { GradientSpec.parsePair(it) }
            val g = t?.spec?.let { GradientSpec.parse(it) }
            if (t != null && pair != null) {
                name = t.name; versions = "both"; oldSpec = t.spec; angle = pair.first.angle
                brightColors = pair.first.colors; darkColors = pair.second.colors
                brightLabels = labelsOf(pair.first, false); darkLabels = labelsOf(pair.second, true)
            } else if (t != null && g != null) {
                // a gradient from before versions: white labels make it the dark version
                name = t.name; oldSpec = t.spec; angle = g.angle
                if (g.white) { versions = "dark"; darkColors = g.colors; darkLabels = labelsOf(g, true); brightColors = GradientSpec.derive(g.colors, false) }
                else { versions = "bright"; brightColors = g.colors; brightLabels = labelsOf(g, false); darkColors = GradientSpec.derive(g.colors, true) }
                tabDark = versions == "dark"
            } else if (solid) {
                // a solid colour theme (user 2026-11-19): one colour, its bright and dark versions
                darkColors = listOf("#2b2350"); brightColors = GradientSpec.derive(darkColors, toDark = false)
                name = "Colour ${UserThemes.gradientCount(context) + 1}"
            } else name = "Gradient ${UserThemes.gradientCount(context) + 1}"
        }
    }
    val editingDark = if (versions == "both") tabDark else versions == "dark"
    val colors = if (editingDark) darkColors else brightColors
    val labels = if (editingDark) darkLabels else brightLabels
    fun specOf(dark: Boolean) = SetGradientState(name, if (dark) darkColors else brightColors, 0, angle, if (dark) darkLabels else brightLabels, false,
        "both", dark, {}, {}, {}, {}, {}, {}, {}, {}, {}).spec
    val state = SetGradientState(
        name, colors, slot.coerceAtMost(colors.size - 1), angle, labels, id.isNotEmpty(), versions, tabDark,
        onName = { name = it }, onColors = { if (editingDark) darkColors = it else brightColors = it }, onSlot = { slot = it }, onAngle = { angle = it },
        onLabels = { if (editingDark) darkLabels = it else brightLabels = it },
        onVersions = { v -> versions = v; tabDark = v == "dark"; slot = 0 },
        onTab = { tabDark = it; slot = 0 },
        onSave = {
            val spec = when (versions) {
                "bright" -> specOf(false).id
                "dark" -> specOf(true).id
                else -> GradientSpec.pairId(specOf(false), specOf(true))
            }
            val title = name.trim().ifEmpty { "Gradient" }
            scope.launch {
                val tid = withContext(Dispatchers.IO) {
                    if (id.isNotEmpty()) { UserThemes.updateGradient(context, id, title, spec); id }
                    else UserThemes.createGradient(context, title, spec, pack.ifEmpty { null })
                }
                ThemeChoice.userNames = ThemeChoice.userNames + (spec to title)
                val c = ThemeChoice.Choice(prefs.keyboard.themeDay.get(), prefs.keyboard.themeNight.get(), prefs.keyboard.themeFollow.get())
                // an edited gradient in use stays in use with its new colours; a new one is put to use
                val next = if (oldSpec != null && (c.day == oldSpec || c.night == oldSpec))
                    c.copy(day = if (c.day == oldSpec) spec else c.day, night = if (c.night == oldSpec) spec else c.night)
                else ThemeChoice.pick(c, spec).first
                prefs.keyboard.themeDay.set(next.day)
                prefs.keyboard.themeNight.set(next.night)
                prefs.keyboard.glassEnabled.set(false)
                prefs.keyboard.userTheme.set(tid)
                navController.popBackStack()
            }
        },
        onBack = { navController.popBackStack() },
    )
    SetGradient(state)
}
