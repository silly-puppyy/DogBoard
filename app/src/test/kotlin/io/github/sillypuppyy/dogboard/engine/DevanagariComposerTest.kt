package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DevanagariComposerTest : FunSpec({
    test("a vowel key after a consonant writes its sign") {
        mapOf("नमसतए" to "नमसते", "हइनदई" to "हिन्दी".replace("न्", "न"), "कआम" to "काम", "भआरत" to "भारत").forEach { (k, w) ->
            DevanagariComposer.compose(k) shouldBe w
        }
    }
    test("at the start, after a vowel or after the virama: the vowel itself") {
        DevanagariComposer.compose("आम") shouldBe "आम"
        DevanagariComposer.compose("कआइ") shouldBe "काइ"
        DevanagariComposer.compose("क्आ") shouldBe "क्आ"
    }
    test("the same rule in other Indic scripts") {
        DevanagariComposer.compose("কআম") shouldBe "কাম" // Bengali
        DevanagariComposer.compose("கஆல") shouldBe "கால" // Tamil
        DevanagariComposer.compose("కఆల") shouldBe "కాల" // Telugu
        DevanagariComposer.compose("કઆમ") shouldBe "કામ" // Gujarati
        for (w in listOf("বাংলা", "தமிழ்", "తెలుగు")) DevanagariComposer.compose(DevanagariComposer.decompose(w)) shouldBe w
    }
    test("decompose gives the keys back; signs and marks are word letters") {
        for (w in listOf("नमस्ते", "भारत", "किताब")) DevanagariComposer.compose(DevanagariComposer.decompose(w)) shouldBe w
        trailingWord("मैं नमस्ते") shouldBe "नमस्ते"
    }
})
