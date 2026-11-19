package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.Symbol
import io.github.sillypuppyy.dogboard.design.ThemeChoice
import io.github.sillypuppyy.dogboard.design.UserThemes

private val START = TextAlign.Start

/**
 * Settings › Themes › Manage packs (user 2026-11-19: replaces the show / hide switches at the bottom of Themes):
 * your packs in order (move, show or hide, rename, share, copy, delete), the built-in packs (show or hide), a new
 * pack and importing one. [onPackAction] takes ThemesScreen's actions ("up", "down", "hide", "show", "rename",
 * "share", "copy", "delete"); [onBuiltIn] shows (false) or hides (true) a built-in pack.
 */
@Composable
fun SetPacks(
    packs: List<UserThemes.Pack>, hiddenBuiltIn: Set<String>,
    onPackAction: (String, String) -> Unit, onBuiltIn: (String, Boolean) -> Unit,
    onNewPack: () -> Unit, onImport: () -> Unit, onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(SetColors.screen)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars).padding(top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { onBack() }, contentAlignment = Alignment.Center) { Symbol("arrow_back", 24.sp, SetColors.onSurface) }
                DText("Theme packs", Modifier.weight(1f), style = setText(22f).copy(textAlign = START))
            }

            SetGroup("Your packs", Modifier.padding(horizontal = 16.dp)) {
                if (packs.isEmpty()) Box(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp)) {
                    DText("No packs yet. Make one, or import a pack someone shared.", style = setText(14f, color = SetColors.onSurfaceVariant).copy(textAlign = START), maxLines = 4)
                }
                for ((i, p) in packs.withIndex()) PackRow(p, i, packs.size, onPackAction)
            }

            SetGroup("Built in", Modifier.padding(horizontal = 16.dp)) {
                for ((id, icon, name, sub) in ThemeChoice.PACKS) {
                    val shown = id !in hiddenBuiltIn
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).background(SetColors.row).clickable { onBuiltIn(id, shown) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.surfaceContainerHighest), contentAlignment = Alignment.Center) { Symbol(icon, 22.sp, SetColors.onSurfaceVariant) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            DText(name, style = setText(16f).copy(textAlign = START))
                            DText(if (shown) sub else "Hidden", style = setText(14f, color = SetColors.onSurfaceVariant).copy(textAlign = START))
                        }
                        Symbol(if (shown) "visibility" else "visibility_off", 22.sp, SetColors.onSurfaceVariant)
                    }
                }
            }

            SetGroup("Add", Modifier.padding(horizontal = 16.dp)) {
                ActionRow("add", "New pack", "An empty pack for your themes", onNewPack)
                ActionRow("file_open", "Import a theme pack", "A .dogtheme file someone shared with you. It stays as they made it; copy it to change it", onImport)
            }
            DText("A hidden pack stays installed, and a theme you are using from it keeps working.",
                Modifier.padding(horizontal = 20.dp), style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START), maxLines = 4)
        }
        StatusBarBand()
    }
}

/** One of your packs: name and state, move up and down, show or hide, and its menu. */
@Composable
private fun PackRow(p: UserThemes.Pack, i: Int, count: Int, onAction: (String, String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).background(SetColors.row).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).then(if (p.hidden) Modifier.alpha(0.6f) else Modifier), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DText(p.name, style = setText(16f).copy(textAlign = START), maxLines = 2)
            val n = "${p.themes.size} ${if (p.themes.size == 1) "theme" else "themes"}"
            DText(listOfNotNull(n, "shared with you".takeIf { p.shared }, "hidden".takeIf { p.hidden }).joinToString(", ").replaceFirstChar { it.uppercase() },
                style = setText(14f, color = SetColors.onSurfaceVariant).copy(textAlign = START), maxLines = 2)
        }
        IconButton("keyboard_arrow_up", i > 0) { onAction(p.id, "up") }
        IconButton("keyboard_arrow_down", i < count - 1) { onAction(p.id, "down") }
        IconButton(if (p.hidden) "visibility_off" else "visibility", true) { onAction(p.id, if (p.hidden) "show" else "hide") }
        Box {
            IconButton("more_vert", true) { menu = true }
            val items = if (p.shared) listOf("copy" to "Copy to my themes", "share" to "Share pack", "delete" to "Remove pack")
                else listOf("rename" to "Rename", "share" to "Share pack", "copy" to "Duplicate", "delete" to "Delete pack")
            SetMenu(menu, { menu = false }, items.map { (a, label) -> label to { onAction(p.id, a) } })
        }
    }
}

@Composable
private fun IconButton(icon: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier.alpha(0.3f)), contentAlignment = Alignment.Center) {
        Symbol(icon, 22.sp, SetColors.onSurfaceVariant)
    }
}

@Composable
private fun ActionRow(icon: String, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).background(SetColors.row).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.secondaryContainer), contentAlignment = Alignment.Center) { Symbol(icon, 22.sp, SetColors.onSecondaryContainer) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DText(title, style = setText(16f).copy(textAlign = START))
            DText(sub, style = setText(14f, color = SetColors.onSurfaceVariant).copy(textAlign = START), maxLines = 4)
        }
    }
}
