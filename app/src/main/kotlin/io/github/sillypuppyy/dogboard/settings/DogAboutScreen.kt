package io.github.sillypuppyy.dogboard.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.clipboardManager
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import kotlinx.coroutines.launch
import dev.patrickgold.jetpref.datastore.model.collectAsState
import androidx.compose.runtime.setValue

/**
 * About (settings tree v3). FlorisBoard's About linked to FlorisBoard's changelog, repository and
 * privacy policy, which don't describe DogBoard; those come back when DogBoard has its own. FlorisBoard
 * is credited as what DogBoard is built on (its Apache-2.0 licence under Licenses).
 */
@Composable
fun DogAboutScreen() {
    val nav = LocalNavController.current
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val clipboard by context.clipboardManager()
    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
    val noBackupPrefs by dev.patrickgold.florisboard.app.NoBackupPreferenceStore
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var taps by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    SetPage("About") {
        SetGroup("DogBoard") {
            SetPageLink("ab:version", "Version", version, icon = "content_copy") {
                runCatching { clipboard.addNewPlaintext(version) }
                // 7 taps unlock Developer options in Advanced (as Android's build number)
                taps++
                val unlocked = prefs.other.devOptionsUnlocked.get()
                val msg = when {
                    unlocked -> "Version copied"
                    taps >= 7 -> { scope.launch { prefs.other.devOptionsUnlocked.set(true) }; "Developer options are in Advanced" }
                    taps >= 4 -> "${7 - taps} more taps for developer options"
                    else -> "Version copied"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            if (io.github.sillypuppyy.dogboard.settings.AppUpdates.supported) {
                val check by prefs.other.updateCheck.collectAsState()
                io.github.sillypuppyy.dogboard.design.settings.SetSwitchRow("ab:updates", "Check for updates", "Once a day, DogBoard asks GitHub whether a newer version is out", check) { on ->
                    scope.launch { prefs.other.updateCheck.set(on) }
                }
            }
            SetPageLink("ab:new", "What’s new", "Every version and its changes", icon = "open_in_new") { uri.openUri("https://github.com/${io.github.sillypuppyy.dogboard.settings.AppUpdates.REPO}/releases") }
            SetPageLink("ab:source", "Source code", "DogBoard on GitHub: code, issues and discussions", icon = "open_in_new") { uri.openUri("https://github.com/${io.github.sillypuppyy.dogboard.settings.AppUpdates.REPO}") }
            SetPageLink("ab:privacy", "Privacy policy", "What stays on your phone, and when DogBoard goes online", icon = "open_in_new") { uri.openUri("https://github.com/${io.github.sillypuppyy.dogboard.settings.AppUpdates.REPO}/blob/main/PRIVACY.md") }
            SetPageLink("ab:floris", "Built on FlorisBoard", "The open-source keyboard DogBoard started from", icon = "open_in_new") { uri.openUri("https://github.com/florisboard/florisboard") }
        }
        SetGroup("Licenses") {
            SetPageLink("ab:license", "DogBoard’s license", "Apache License 2.0, as FlorisBoard") { nav.navigate(Routes.Settings.ProjectLicense) }
            SetPageLink("ab:thirdparty", "Open-source libraries", "The libraries DogBoard uses and their licenses") { nav.navigate(Routes.Settings.ThirdPartyLicenses) }
            SetPageLink("ab:data", "Word data", "Where the word lists, language models and link rules come from") { nav.navigate(Routes.Settings.DataLicenses) }
        }
    }
}
