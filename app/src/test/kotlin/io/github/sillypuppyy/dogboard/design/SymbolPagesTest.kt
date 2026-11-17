package io.github.sillypuppyy.dogboard.design

import dev.patrickgold.florisboard.ime.keyboard3.compileLayout
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelOptions
import dev.patrickgold.florisboard.ime.keyboard3.touch.computeTouchModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.coroutineScope
import org.k3lp.lib.text.WillRequireMigrationToRichErrors
import org.k3lp.model.K3CompileResult
import org.k3lp.model.layer.K3LayerId
import org.k3lp.lib.text.K3String

/** The symbol pages (Settings › Layout › Symbols): the touch layout types what the drawing shows. */
@OptIn(WillRequireMigrationToRichErrors::class)
class SymbolPagesTest : FunSpec({
    for (style in listOf("iphone", "android")) for (layer in listOf("symbol-1", "symbol-2")) test("$style $layer: drawn = typed") {
        val result = compileLayout("keyboard/qwerty-en.xml")
        result.shouldBeInstanceOf<K3CompileResult.Success>()
        val keyboard = coroutineScope { computeTouchModel(result.model, TouchModelOptions.Default.copy(symbolsAndroid = style == "android")) }
            .selectKeyboard(deviceWidthMm = 70)
        val touch = keyboard.layers.getValue(K3LayerId(layer)).keys
            .mapNotNull { (it.attrs.output as? K3String)?.toText() }.filter { it != " " }.take(27)
        val drawn = KbSpec.render(KbProps(width = 375f, keyH = 216f, plane = layer, symbols = style)).rows.take(3)
            .flatMap { row -> row.keys.filter { it.kind == "char" }.map { it.base } }
        touch shouldBe drawn
    }
})
