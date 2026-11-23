package dev.patrickgold.florisboard.ime.keyboard3.touch

import dev.patrickgold.florisboard.ime.keyboard3.ImeLayerIds
import dev.patrickgold.florisboard.ime.keyboard3.compileLayout
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.k3lp.model.K3CompileResult

/**
 * Phonetic Georgian types seven letters with Shift (ტ -> თ, iOS's Capital-Letters plane): the decoder's targets for
 * either layer hold both letters on the same key, so a word typed across the layers ("თბილისი": Shift for its first
 * letter) can be scored. Latin layouts keep their targets.
 */
class GeorgianPhoneticTargetsTest : FunSpec({
    suspend fun targets(path: String, layer: org.k3lp.model.layer.K3LayerId): Map<Char, io.github.sillypuppyy.dogboard.touch.KeyTarget> {
        val compiled = compileLayout(path) as K3CompileResult.Success
        val keyboard = kotlinx.coroutines.coroutineScope { computeTouchModel(compiled.model, TouchModelOptions.Default) }.selectKeyboard(deviceWidthMm = 70)
        return keyboard.letterTargets(layer, 375f, 216f, 0.3).first
    }

    test("the Shift letters sit on their base keys in both layers") {
        val base = targets("keyboard/georgian-qwerty-ka.xml", ImeLayerIds.Base)
        val shift = targets("keyboard/georgian-qwerty-ka.xml", ImeLayerIds.Shift)
        for ((small, big) in listOf('ტ' to 'თ', 'წ' to 'ჭ', 'რ' to 'ღ', 'ს' to 'შ', 'ჯ' to 'ჟ', 'ზ' to 'ძ', 'ც' to 'ჩ')) {
            base[big] shouldNotBe null
            base[big]!!.centerX shouldBe base[small]!!.centerX
            shift[small] shouldNotBe null
            shift[small]!!.centerX shouldBe shift[big]!!.centerX
        }
        base.keys.count { it.isLetter() } shouldBe 26 + 7 // 26 keys, 7 more letters on Shift
    }

    test("a Latin layout's targets are unchanged by the other layer") {
        val base = targets("keyboard/qwerty-es.xml", ImeLayerIds.Base)
        base.keys.filter { it.isLetter() }.sorted().joinToString("") shouldBe "abcdefghijklmnopqrstuvwxyzñ"
    }
})
