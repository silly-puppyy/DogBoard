package io.github.sillypuppyy.dogboard.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Every FlorisBoard touch layer is drawn as itself (number fields drew letters but typed digits). */
class LiveKbTest : FunSpec({
    test("number and phone pads are drawn as pads") {
        LiveKb.plane("numpad") shouldBe "numpad"
        LiveKb.plane("telpad") shouldBe "telpad"
        LiveKb.plane("telpad2") shouldBe "telpad2"
    }
    test("text layers") {
        LiveKb.plane("base") shouldBe "base"
        LiveKb.plane("shift") shouldBe "base"
        LiveKb.plane("caps") shouldBe "base"
        LiveKb.plane("symbol-1") shouldBe "symbol-1"
        LiveKb.plane("symbol-2") shouldBe "symbol-2"
    }
    test("every pad plane renders four rows") {
        for (p in listOf("numpad", "telpad", "telpad2")) KbSpec.render(KbProps(plane = p, only = "live")).rows.size shouldBe 4
    }
})
