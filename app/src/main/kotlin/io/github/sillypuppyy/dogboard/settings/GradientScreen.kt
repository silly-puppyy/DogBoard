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
fun GradientScreen(id: String, pack: String) {
    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    io.github.sillypuppyy.dogboard.design.MaterialYouTheme.Remember()
    var name by remember { mutableStateOf("") }
    var colors by remember { mutableStateOf(listOf("#2b2350", "#9e3b56")) }
    var slot by remember { mutableStateOf(0) }
    var angle by remember { mutableStateOf(180) }
    var labels by remember { mutableStateOf("auto") }
    var oldSpec by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            val t = id.takeIf { it.isNotEmpty() }?.let { UserThemes.theme(context, it) }
            val g = t?.spec?.let { GradientSpec.parse(it) }
            if (t != null && g != null) {
                name = t.name; colors = g.colors; angle = g.angle; oldSpec = t.spec
                labels = if (g.white == GradientSpec.autoWhite(g.colors)) "auto" else if (g.white) "white" else "dark"
            } else name = "Gradient ${UserThemes.gradientCount(context) + 1}"
        }
    }
    val state = SetGradientState(
        name, colors, slot.coerceAtMost(colors.size - 1), angle, labels, id.isNotEmpty(),
        onName = { name = it }, onColors = { colors = it }, onSlot = { slot = it }, onAngle = { angle = it }, onLabels = { labels = it },
        onSave = {
            val spec = SetGradientState(name, colors, 0, angle, labels, false, {}, {}, {}, {}, {}, {}, {}).spec.id
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
