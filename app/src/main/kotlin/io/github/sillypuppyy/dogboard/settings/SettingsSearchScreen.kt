package io.github.sillypuppyy.dogboard.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import io.github.sillypuppyy.dogboard.design.settings.SetColors
import io.github.sillypuppyy.dogboard.design.settings.setText

/** Settings search (settings layout v2): type to find a setting, tap it to open its page. */
@Composable
fun SettingsSearchScreen(open: (route: String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    FlorisScreenNg(title = "Search settings", previewFieldVisible = false) { contentPadding ->
        Column(Modifier.fillMaxWidth().padding(contentPadding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Vibration, quotes, accents…") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            val results = SettingsSearch.search(query)
            if (query.isNotBlank() && results.isEmpty()) {
                Text("No setting matches “${query.trim()}”", Modifier.padding(16.dp), style = setText(14f, color = SetColors.onSurfaceVariant))
            }
            for (e in results) {
                Column(Modifier.fillMaxWidth().clickable { open(e.route) }.padding(horizontal = 4.dp, vertical = 12.dp)) {
                    Text(e.title, style = setText(16f))
                    Text(e.page, style = setText(14f, color = SetColors.onSurfaceVariant))
                }
            }
        }
    }
}
