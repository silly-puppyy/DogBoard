package io.github.sillypuppyy.dogboard.design

import androidx.compose.ui.geometry.Offset
import dev.patrickgold.florisboard.ime.keyboard3.compileLayout
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelOptions
import dev.patrickgold.florisboard.ime.keyboard3.touch.computeTouchModel
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.coroutineScope
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.WillRequireMigrationToRichErrors
import org.k3lp.model.K3CompileResult
import org.k3lp.model.layer.K3LayerId

/**
 * The symbol pages, every row including the bottom one: a tap on the centre of each drawn key types what the key shows.
 * Every combination of symbol style (iPhone / Android), bottom row (iOS / Classic) and field (text / email / URL): in
 * 1.2.8's Android-style row the drawn period beside space sat over the touch layout's return key (tapping "." sent a
 * line break), a bottom-row mismatch the letter tests (DrawnEqualsTouchTest) and the top-rows check
 * (SymbolPagesTest) didn't cover.
 */
@OptIn(WillRequireMigrationToRichErrors::class)
class SymbolPagesTouchTest : FunSpec({
    val fields = listOf("text" to KeyVariation.NORMAL, "email" to KeyVariation.EMAIL_ADDRESS, "url" to KeyVariation.URI)
    for (symbols in listOf("iphone", "android")) for (classic in listOf(false, true)) for ((field, variation) in fields)
        for (plane in listOf("symbol-1", "symbol-2")) {
            test("$symbols symbols, ${if (classic) "Classic" else "iOS"} bottom row, $field field, $plane: each drawn key types itself") {
                val result = compileLayout("keyboard/qwerty-en.xml")
                result.shouldBeInstanceOf<K3CompileResult.Success>()
                val keyboard = coroutineScope {
                    computeTouchModel(result.model, TouchModelOptions.Default.copy(
                        symbolsAndroid = symbols == "android", bottomRowClassic = classic, keyVariation = variation))
                }.selectKeyboard(deviceWidthMm = 70)
                val w = 375f
                val r = KbSpec.render(KbProps(width = w, keyH = 216f, plane = plane, symbols = symbols, field = field,
                    kstyle = if (classic) "android" else "ios", numRow = "off"))
                val h = r.rowTops.last() + r.rowHs.last()
                val wrong = buildList {
                    r.rows.forEachIndexed { ri, row ->
                        for (k in row.keys) {
                            if (k.gap || k.kind != "char" || k.base.isBlank()) continue
                            val at = Offset((k.x + k.w / 2) / w, (r.rowTops[ri] + r.rowHs[ri] / 2) / h)
                            val typed = keyboard.findKey(K3LayerId(plane), at)?.let { (it.attrs.output as? K3String)?.toText() }
                            // the URL field's period is named apart for its .com long-press (KbSpec.URL_DOT); it types "."
                            val shows = if (k.base == KbSpec.URL_DOT) "." else k.base
                            if (typed != shows) add("row $ri: drawn \"$shows\" types \"$typed\"")
                        }
                    }
                }
                wrong.shouldBeEmpty()
            }
        }

    // the number pages: the letters' numeric layer and the number / phone fields' pads, drawn key for key
    for (plane in listOf("numeric", "numpad", "telpad", "telpad2")) test("$plane: each drawn key types itself") {
        val result = compileLayout("keyboard/qwerty-en.xml")
        result.shouldBeInstanceOf<K3CompileResult.Success>()
        val keyboard = coroutineScope { computeTouchModel(result.model, TouchModelOptions.Default) }.selectKeyboard(deviceWidthMm = 70)
        keyboard.layers.keys.map { it.toString() }.any { it.contains(plane) } shouldBe true
        val w = 375f
        val r = KbSpec.render(KbProps(width = w, keyH = 216f, plane = plane, numRow = "off"))
        val h = r.rowTops.last() + r.rowHs.last()
        var checked = 0
        val wrong = buildList {
            r.rows.forEachIndexed { ri, row ->
                for (k in row.keys) {
                    if (k.gap || k.kind !in setOf("char", "pad") || k.base.isBlank()) continue
                    checked++
                    val at = Offset((k.x + k.w / 2) / w, (r.rowTops[ri] + r.rowHs[ri] / 2) / h)
                    val typed = keyboard.findKey(K3LayerId(plane), at)?.let { (it.attrs.output as? K3String)?.toText() }
                    if (typed != k.base) add("row $ri: drawn \"${k.base}\" types \"$typed\"")
                }
            }
        }
        wrong.shouldBeEmpty()
        (checked >= 10) shouldBe true // the digits at least: an empty match would pass anything
    }
})
