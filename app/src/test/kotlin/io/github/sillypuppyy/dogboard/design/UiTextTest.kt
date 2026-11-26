package io.github.sillypuppyy.dogboard.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UiTextTest : FunSpec({
    test("exact text, then a template with the numbers put back in order") {
        UiText.use(mapOf("Learned" to "Gelernt", "Step {n} of {n}" to "Schritt {n} von {n}", "You used it {n} times" to "{n}-mal verwendet"))
        UiText.tr("Learned") shouldBe "Gelernt"
        UiText.tr("Step 2 of 5") shouldBe "Schritt 2 von 5"
        UiText.tr("You used it 12 times") shouldBe "12-mal verwendet"
        UiText.tr("Undone 3 times") shouldBe "Undone 3 times"
        UiText.tr("Unknown") shouldBe "Unknown"
        UiText.use(emptyMap())
    }
})
