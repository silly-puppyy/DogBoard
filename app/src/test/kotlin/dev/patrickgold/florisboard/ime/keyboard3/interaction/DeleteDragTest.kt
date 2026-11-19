package dev.patrickgold.florisboard.ime.keyboard3.interaction

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Delete's slide left selects whole words back from the cursor (startOfLastWords). */
class DeleteDragTest : FunSpec({
    test("words are taken off the end with the spaces after them") {
        val t = "The quick brown fox "
        startOfLastWords(t, 0) shouldBe t.length
        t.substring(startOfLastWords(t, 1)) shouldBe "fox "
        t.substring(startOfLastWords(t, 2)) shouldBe "brown fox "
        startOfLastWords(t, 9) shouldBe 0
        "word".let { it.substring(startOfLastWords(it, 1)) } shouldBe "word"
        startOfLastWords("", 3) shouldBe 0
    }
})
