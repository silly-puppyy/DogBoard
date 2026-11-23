package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HangulComposerTest : FunSpec({
    test("syllables as typed, a final opening the next syllable before a vowel") {
        mapOf(
            "ㅎㅏㄴㄱㅜㄱㅇㅓ" to "한국어", "ㅎㅏㄴㅏ" to "하나", "ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ" to "안녕하세요",
            "ㄱㅗㅏ" to "과", "ㅇㅜㅣ" to "위", "ㄷㅏㄹㄱ" to "닭", "ㄷㅏㄹㄱㅏ" to "달가", "ㄷㅏㄹㄱㅇㅣ" to "닭이",
            "ㅇㅓㅂㅅㄷㅏ" to "없다", "ㅃㅏㅇ" to "빵", "ㅆㅏ" to "싸",
        ).forEach { (keys, word) -> HangulComposer.compose(keys) shouldBe word }
    }
    test("as typed so far, mid-syllable") {
        HangulComposer.compose("ㅎ") shouldBe "ㅎ"
        HangulComposer.compose("ㅎㅏ") shouldBe "하"
        HangulComposer.compose("ㅎㅏㄴ") shouldBe "한"
        HangulComposer.compose("ㅎㅏㄴㄱ") shouldBe "한ㄱ"
        HangulComposer.compose("ㅏ") shouldBe "ㅏ"
    }
    test("tense consonants can't close a syllable; they start the next") {
        HangulComposer.compose("ㅏㅃ") shouldBe "ㅏㅃ"
        HangulComposer.compose("ㄱㅏㄸ") shouldBe "가ㄸ"
    }
    test("a Korean word list along jamo keys (rules path hangul): typed jamo reach the word") {
        val rules = LanguageRules(path = "hangul")
        val lex = Lexicon.parse("#dogboard-lexicon 1\n한국어\t500\n닭\t100\n과\t300\n".reader().buffered(), TypingEngine.pathOf(rules))
        lex.firstForm(lex.find("ㅎㅏㄴㄱㅜㄱㅇㅓ")) shouldBe "한국어"
        lex.firstForm(lex.find("ㄱㅗㅏ")) shouldBe "과"
        lex.contains("닭") shouldBe true
    }
    test("decompose gives the keys back") {
        for (w in listOf("한국어", "하나", "과", "닭이", "없다", "빵")) HangulComposer.compose(HangulComposer.decompose(w)) shouldBe w
        HangulComposer.decompose("닭") shouldBe "ㄷㅏㄹㄱ"
        HangulComposer.decompose("과") shouldBe "ㄱㅗㅏ"
    }
})
