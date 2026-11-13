package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/** Only a single emoji counts as emoji use; text that contains a symbol is never recorded. */
class EmojiUsageTest : FunSpec({
    test("single emoji, including ZWJ sequences and skin tones") {
        EmojiUsage.isSingleEmoji("😀") shouldBe true
        EmojiUsage.isSingleEmoji("👍🏽") shouldBe true
        EmojiUsage.isSingleEmoji("👩‍💻") shouldBe true
        EmojiUsage.isSingleEmoji("🇩🇪") shouldBe true
        EmojiUsage.isSingleEmoji("🇩🇪🇫🇷") shouldBe false
    }
    test("text containing a symbol is not an emoji") {
        EmojiUsage.isSingleEmoji("my pin is 1234 ★") shouldBe false
        EmojiUsage.isSingleEmoji("😀😀") shouldBe false
        EmojiUsage.isSingleEmoji("a") shouldBe false
        EmojiUsage.isSingleEmoji("😀\n") shouldBe false
    }
    test("a damaged usage file loads without throwing") {
        val f = File.createTempFile("emoji", ".tsv")
        f.writeText("#dogboard-emoji-usage 1\t5\nu\t😀\tnot-a-number\nu\t👍\t3\nu\tmy pin ★\t4\na\tapp\tx\n")
        EmojiUsage(f).score("👍") shouldBe 1.0 / (0.1 * (5 - 3) + 1)
        f.delete()
    }
})
