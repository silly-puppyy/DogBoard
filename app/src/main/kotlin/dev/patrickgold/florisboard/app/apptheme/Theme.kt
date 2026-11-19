/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.app.apptheme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import dev.patrickgold.florisboard.app.AppTheme
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.snygg.color.neutralDynamicColorScheme
import org.florisboard.lib.snygg.color.systemAccentOrDefault

@Composable
fun getColorScheme(
    theme: AppTheme,
): ColorScheme {
    val prefs by FlorisPreferenceStore
    val accentColor by prefs.other.accentColor.collectAsState()
    val isDark = theme.isDark || theme.isAuto && isSystemInDarkTheme()
    // DogBoard: the app takes its colours from the keyboard theme (user 2026-11-17): the default
    // theme keeps the wallpaper's (system) accent, any other theme seeds the palette with its return key
    val themeDay by prefs.keyboard.themeDay.collectAsState()
    val themeNight by prefs.keyboard.themeNight.collectAsState()
    val themeFollow by prefs.keyboard.themeFollow.collectAsState()
    val (kbTheme, kbDark) = io.github.sillypuppyy.dogboard.design.ThemeChoice.resolve(
        io.github.sillypuppyy.dogboard.design.ThemeChoice.Choice(themeDay, themeNight, themeFollow), isDark,
    )
    // Themes › App colours (user 2026-11-19): from the keyboard theme (a photo theme's return-key colour), the
    // wallpaper, or none (DogBoard's own colours)
    val colours by prefs.other.settingsColours.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val stamp by prefs.keyboard.glassPhotoStamp.collectAsState()
    val looks by prefs.keyboard.glassLooks.collectAsState()
    val photoSeed by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.Color?>(null, colours, kbTheme, kbDark, stamp, looks) {
        value = if (colours != "keyboard" || kbTheme != "glass" || stamp == 0L) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val v = io.github.sillypuppyy.dogboard.design.PhotoSlot.versions(context, prefs)
            val useDark = if (v.hasBright && v.hasDark) kbDark else v.hasDark
            val own = useDark && v.darkPhoto
            io.github.sillypuppyy.dogboard.design.PhotoReadability.accent(v.look(useDark), io.github.sillypuppyy.dogboard.design.GlassPhoto.analysis(context, own)?.accents)
                ?.first?.let { io.github.sillypuppyy.dogboard.design.css(it) }
        }
    }
    val themeSeed = when (colours) {
        "none" -> io.github.sillypuppyy.dogboard.design.css("#435e91") // the design canvas's seed
        "wallpaper" -> null
        else -> if (kbTheme == "dogboard") null else if (kbTheme == "glass") photoSeed
            else io.github.sillypuppyy.dogboard.design.KbThemes.tokens(kbTheme, kbDark).act.takeIf { it.alpha > 0.5f }
    }
    val seedColor = themeSeed ?: systemAccentOrDefault(accentColor)
    val isAmoled = theme.isAmoled && isDark

    return com.materialkolor.dynamicColorScheme(
        seedColor = seedColor,
        // the system accent is the primary as given; a theme's colour seeds the palette, whose own
        // tones keep text readable (a light return key would make faint primary text)
        primary = if (themeSeed == null) seedColor else null,
        isDark = isDark,
        isAmoled = theme.isAmoled,
        style = PaletteStyle.TonalSpot,
        // DogBoard: the design canvas uses the 2021 tonal palettes (errorContainer #ffdad6, primaryContainer #d8e2ff)
        specVersion = ColorSpec.SpecVersion.SPEC_2021,
        modifyColorScheme = { scheme ->
            scheme.copy(
                surfaceContainer = when {
                    isAmoled -> Color.Black
                    else -> scheme.surfaceContainer
                },
                surface = when {
                    isAmoled -> scheme.surfaceContainer
                    isDark -> scheme.surfaceContainerHighest
                    else -> scheme.surface
                },
            )
        }
    )
}

@Composable
fun FlorisAppTheme(
    theme: AppTheme,
    content: @Composable () -> Unit,
) {
    val colorScheme = getColorScheme(theme = theme)

    val darkTheme = theme.isDark || theme.isAuto && isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !darkTheme
        }
    }

    // DogBoard: the redesigned settings pages take the dark roles from this scheme
    io.github.sillypuppyy.dogboard.design.settings.SetColors.scheme = colorScheme
    io.github.sillypuppyy.dogboard.design.settings.SetColors.schemeDark = darkTheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
