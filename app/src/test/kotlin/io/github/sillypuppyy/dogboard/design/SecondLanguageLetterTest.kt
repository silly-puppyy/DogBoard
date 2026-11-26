package io.github.sillypuppyy.dogboard.design

import dev.patrickgold.florisboard.ime.keyboard3.ImeLayerIds
import dev.patrickgold.florisboard.ime.keyboard3.compileLayout
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelOptions
import dev.patrickgold.florisboard.ime.keyboard3.touch.computeTouchModel
import io.github.sillypuppyy.dogboard.ime.ExtraLetters
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.coroutineScope
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.WillRequireMigrationToRichErrors
import org.k3lp.model.K3CompileResult

/** The layout library keeps "ü" as u + U+0308. */
private fun nfc(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)

/**
 * A language typed together with the layout's own puts its letters first on the long-press, selected at first: English
 * QWERTY typed with German held à á â before ä (user, 2026-11-26).
 */
@OptIn(WillRequireMigrationToRichErrors::class)
class SecondLanguageLetterTest : FunSpec({
    test("English QWERTY + German: ä ö ü first on a o u") {
        val result = compileLayout("keyboard/qwerty-en.xml")
        result.shouldBeInstanceOf<K3CompileResult.Success>()
        val extra = ExtraLetters.forEntry("qwerty-en", "de")
        val kb = coroutineScope { computeTouchModel(result.model, TouchModelOptions.Default.copy(extraLetters = extra)) }.selectKeyboard(deviceWidthMm = 70)
        fun first(k: String) = kb.layers.getValue(ImeLayerIds.Base).keys.first { (it.attrs.output as? K3String)?.toText() == k }
            .extendedPopupKeys.first().let { (it.data.output as? K3String)?.toText()?.let(::nfc) }
        mapOf("a" to first("a"), "o" to first("o"), "u" to first("u")) shouldBe mapOf("a" to "ä", "o" to "ö", "u" to "ü")
    }

    test("English QWERTY alone keeps its own order") {
        val result = compileLayout("keyboard/qwerty-en.xml")
        result.shouldBeInstanceOf<K3CompileResult.Success>()
        val kb = coroutineScope { computeTouchModel(result.model, TouchModelOptions.Default) }.selectKeyboard(deviceWidthMm = 70)
        val a = kb.layers.getValue(ImeLayerIds.Base).keys.first { (it.attrs.output as? K3String)?.toText() == "a" }
        (a.extendedPopupKeys.first().data.output as? K3String)?.toText()?.let(::nfc) shouldBe "à"
    }
})
