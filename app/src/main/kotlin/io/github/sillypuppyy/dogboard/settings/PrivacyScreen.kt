package io.github.sillypuppyy.dogboard.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.settings.SetColors
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.SetLinkRow
import io.github.sillypuppyy.dogboard.design.settings.SetSwitchRow
import io.github.sillypuppyy.dogboard.design.settings.Segmented
import io.github.sillypuppyy.dogboard.design.settings.TitleSub
import kotlinx.coroutines.launch

/**
 * Privacy (settings layout v2, feature-inventory.md §8): incognito, contact names (opt-in, asks for
 * access when turned on), and the way to your words. No canvas: the shared rows.
 */
@Composable
fun PrivacyScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val nav = LocalNavController.current
    val context = LocalContext.current
    val incognito by prefs.suggestion.incognitoMode.collectAsState()
    val contacts by prefs.spelling.useContacts.collectAsState()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { prefs.spelling.useContacts.set(granted) }
    }

    FlorisScreenNg(title = "Privacy", previewFieldVisible = true) { contentPadding ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SetGroup("Incognito") {
                Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TitleSub("Incognito typing", "Nothing you type is learned or kept. “Automatic” turns it on in private browser tabs and fields that ask for it.")
                    val modes = listOf(IncognitoMode.DYNAMIC_ON_OFF, IncognitoMode.FORCE_ON, IncognitoMode.FORCE_OFF)
                    Segmented("pv:incognito", listOf("Automatic", "Always", "Never"), modes.indexOf(incognito)) { i ->
                        scope.launch { prefs.suggestion.incognitoMode.set(modes[i]) }
                    }
                }
            }
            SetGroup("Suggestions") {
                SetSwitchRow("pv:contacts", "Contact names", "Suggest the names in your contacts. They stay on this phone.", contacts) { v ->
                    if (!v) scope.launch { prefs.spelling.useContacts.set(false) }
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                        scope.launch { prefs.spelling.useContacts.set(true) }
                    } else ask.launch(Manifest.permission.READ_CONTACTS)
                }
            }
            SetGroup("Your data") {
                SetLinkRow("pv:words", { nav.navigate(Routes.Settings.Dictionary) }) {
                    TitleSub("Your words", "See, export or forget the words DogBoard learned", Modifier.weight(1f))
                }
                SetLinkRow("pv:clipboard", { nav.navigate(Routes.Settings.Clipboard) }) {
                    TitleSub("Clipboard history", "How long copied text is kept, and passwords clearing themselves", Modifier.weight(1f))
                }
            }
        }
    }
}
