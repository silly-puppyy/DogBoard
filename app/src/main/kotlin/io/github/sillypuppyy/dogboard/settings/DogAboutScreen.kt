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
    SetPage("About") {
        SetGroup("DogBoard") {
            SetPageLink("ab:version", "Version", "$version. Tap to copy") {
                runCatching { clipboard.addNewPlaintext(version) }
                Toast.makeText(context, "Version copied", Toast.LENGTH_SHORT).show()
            }
            SetPageLink("ab:floris", "Built on FlorisBoard", "The open-source keyboard DogBoard started from") { uri.openUri("https://github.com/florisboard/florisboard") }
        }
        SetGroup("Licenses") {
            SetPageLink("ab:license", "DogBoard’s license", "Apache License 2.0, as FlorisBoard") { nav.navigate(Routes.Settings.ProjectLicense) }
            SetPageLink("ab:thirdparty", "Open-source libraries", "The libraries DogBoard uses and their licenses") { nav.navigate(Routes.Settings.ThirdPartyLicenses) }
            SetPageLink("ab:data", "Word data", "Where the word lists, language models and link rules come from") { nav.navigate(Routes.Settings.DataLicenses) }
        }
    }
}
