package io.github.sillypuppyy.dogboard.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import io.github.sillypuppyy.dogboard.design.settings.setText

/** About › Data licenses: the shipped word data's sources and licenses (assets/dogboard/ATTRIBUTION.txt). */
@Composable
fun DataLicensesScreen() {
    val context = LocalContext.current
    val text = remember {
        runCatching { context.assets.open("dogboard/ATTRIBUTION.txt").bufferedReader().use { it.readText() } }
            .getOrDefault("The attribution file is missing from this build.")
    }
    FlorisScreenNg(title = "Data licenses", previewFieldVisible = false) { contentPadding ->
        SelectionContainer {
            Text(
                text, style = setText(14f),
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
            )
        }
    }
}
