package io.github.sillypuppyy.dogboard.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.setText

/** One source of shipped data: its name, its licence, where it lives. */
private data class Source(val name: String, val license: String, val url: String)

/** A kind of shipped data and what it is made from. */
private data class DataKind(val title: String, val sources: List<Source>)

private val CC_BY_SA = "CC BY-SA 4.0"
private val FINEWEB = Source("FineWeb, Hugging Face", "ODC-By 1.0, built from Common Crawl", "https://huggingface.co/datasets/HuggingFaceFW/fineweb")
private val CLDR = Source("Unicode CLDR", "Unicode License v3", "https://github.com/unicode-org/cldr")

/**
 * About › Word data (user 2026-11-22: "word data could use a revamp"): the shipped data by kind, each source with
 * its licence and a link; the complete attribution (assets/dogboard/ATTRIBUTION.txt) stays one tap away. Keep this
 * list in step with ATTRIBUTION.txt when data is added.
 */
private val KINDS = listOf(
    DataKind("Dictionary", listOf(
        Source("SCOWL word lists, Kevin Atkinson", "SCOWL copyright notice", "http://wordlist.aspell.net/"),
        Source("wordfreq word frequencies, Robyn Speer", CC_BY_SA, "https://github.com/rspeer/wordfreq"),
        Source("Wikidata names", "CC0", "https://www.wikidata.org"),
        Source("GeoNames place names", "CC BY 4.0", "https://www.geonames.org"),
        Source("US Census name lists", "Public domain", "https://www.census.gov/topics/population/genealogy/data.html"),
    )),
    DataKind("Corrections", listOf(
        Source("Wikipedia's lists of common misspellings", CC_BY_SA, "https://en.wikipedia.org/wiki/Wikipedia:Lists_of_common_misspellings/For_machines"),
        Source("Abbreviations, from Unicode CLDR", CLDR.license, CLDR.url),
        Source("Words never suggested: LDNOOBW list", "CC BY 4.0", "https://github.com/LDNOOBW"),
    )),
    DataKind("Next-word models", listOf(
        Source("Word pairs from Tatoeba sentences", "CC BY 2.0 FR", "https://tatoeba.org"),
        FINEWEB.copy(name = "Word triples and the letter model: FineWeb"),
        Source("Neural model: DogBoard's own, trained on public text only", "Its training text is listed in the full text below", ""),
    )),
    DataKind("Emoji", listOf(
        Source("Emoji names and keywords, Unicode CLDR", CLDR.license, CLDR.url),
        Source("Emoji ranking, wordfreq", CC_BY_SA, "https://github.com/rspeer/wordfreq"),
    )),
    DataKind("Swipe typing", listOf(
        Source("Trained on the FUTO swipe dataset", "MIT License", "https://huggingface.co/datasets/futo-org/swipe.futo.org"),
    )),
    DataKind("Key sounds", listOf(
        Source("Recordings from Freesound, credited in the full text", "CC0", "https://freesound.org"),
    )),
    DataKind("Links", listOf(
        Source("Brave's clean-link list", "Mozilla Public License 2.0", "https://github.com/brave/adblock-lists"),
    )),
    DataKind("Fonts", listOf(
        Source("Roboto Flex", "SIL Open Font License 1.1", "https://github.com/googlefonts/roboto-flex"),
        Source("Material Symbols Rounded, Google", "Apache License 2.0", "https://github.com/google/material-design-icons"),
    )),
)

@Composable
fun DataLicensesScreen() {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var full by remember { mutableStateOf(false) }
    val text = remember {
        runCatching { context.assets.open("dogboard/ATTRIBUTION.txt").bufferedReader().use { it.readText() } }
            .getOrDefault("The attribution file is missing from this build.")
    }
    SetPage("Word data") {
        for (kind in KINDS) SetGroup(kind.title) {
            // a source with a page opens it; one without (DogBoard's own model) opens the full text
            for (s in kind.sources) SetPageLink("data:${kind.title}:${s.name}", s.name, s.license, icon = if (s.url.isEmpty()) "keyboard_arrow_down" else "open_in_new") {
                if (s.url.isEmpty()) full = true else runCatching { uri.openUri(s.url) }
            }
        }
        SetGroup("Licences in full") {
            SetPageLink("data:full", if (full) "Hide the full text" else "Show the full text", "Every source, its notice and what was built from it",
                icon = if (full) "keyboard_arrow_up" else "keyboard_arrow_down") { full = !full }
        }
        if (full) SelectionContainer {
            Text(text, style = setText(13f), modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        }
    }
}
