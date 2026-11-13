package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

class EmojiSuggestTest : FunSpec({
    val index = File("src/main/assets/dogboard/en.emoji.tsv").bufferedReader().use { EmojiSuggest.parse(it) }

    test("the last word, and phrases longest first") {
        val s = EmojiSuggest(index, EmojiUsage())
        s.suggest("my dog").first() shouldBe "🐕"
        s.suggest("my dog.").first() shouldBe "🐕" // punctuation around the word is ignored
        s.suggest("Thumbs up").first() shouldBe "👍"
        s.suggest("I love it, lol").first() shouldBe "😂"
        s.suggest("qwzx").shouldBeEmpty()
    }
    test("the user's own use reorders the matches (iOS recency score)") {
        val usage = EmojiUsage()
        val s = EmojiSuggest(index, usage)
        s.suggest("dog").first() shouldBe "🐕"
        repeat(3) { usage.record("🐶", "chat.app") }
        s.suggest("dog").first() shouldBe "🐶"
    }
    test("shown only after the user has used emoji, overall and in this app") {
        val usage = EmojiUsage()
        usage.hasUsedEmoji("chat.app") shouldBe false
        usage.record("😂", "chat.app")
        usage.hasUsedEmoji("chat.app") shouldBe true
        usage.hasUsedEmoji("bank.app") shouldBe false
    }
    test("the score fades with later emoji use and is saved") {
        val f = File.createTempFile("emoji", ".tsv").apply { deleteOnExit() }
        val usage = EmojiUsage(f)
        usage.record("🐶", "a")
        val fresh = usage.score("🐶")
        repeat(10) { usage.record("😂", "a") }
        (usage.score("🐶") < fresh) shouldBe true
        EmojiUsage(f).score("😂") shouldBe usage.score("😂")
    }
    test("the default skin tone is applied where the emoji has one; uses count across tones") {
        val tones = File("src/main/assets/dogboard/en.emoji-tones.tsv").bufferedReader().use { EmojiSuggest.parseTones(it) }
        val usage = EmojiUsage()
        val s = EmojiSuggest(index, usage, tones)
        s.suggest("thumbs up", tone = 0x1F3FD).first() shouldBe "👍🏽"
        s.suggest("my dog", tone = 0x1F3FD).first() shouldBe "🐕" // no tones for a dog
        usage.record("👍🏿", null)
        usage.score("👍") shouldBe usage.score("👍🏿")
    }
    test("the strip puts the emoji in the third slot") {
        val c = { w: String, s: Double -> Candidate(w, s, s, s, 0, isCompletion = false) }
        val strip = SuggestionStrip.build("dog", listOf(c("dog", -1.0), c("dogs", -2.0), c("doge", -3.0)), null, emoji = "🐕")
        strip.map { it.word } shouldBe listOf("dog", "dogs", "🐕")
        strip.last().kind shouldBe Suggestion.Kind.EMOJI
    }
})
