package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class RuleComposerTest : FunSpec({
    fun rules(script: String) = RuleComposer.parse(File("src/main/assets/dogboard/dynamic_rules/$script.json").readText())
    val hi = rules("devanagari")

    test("a vowel key after a consonant writes its sign") {
        mapOf("नमसतए" to "नमसते", "हइनदई" to "हिनदी", "कआम" to "काम", "भआरत" to "भारत").forEach { (k, w) -> hi.compose(k) shouldBe w }
    }
    test("after a cluster too; at the start, after a vowel or after the virama: the vowel itself") {
        hi.compose("नमस्तए") shouldBe "नमस्ते"
        hi.compose("आम") shouldBe "आम"
        hi.compose("कआइ") shouldBe "काइ"
        hi.compose("क्आ") shouldBe "क्आ"
    }
    test("Gboard's other rules: a sign from the long-press after a consonant gives the vowel; अ keeps the consonant bare") {
        hi.compose("कि") shouldBe "कइ"
        hi.compose("कअआ") shouldBe "कआ"
        hi.compose("कअ") shouldBe "क"
    }
    test("each script's own rules") {
        rules("bengali").compose("কআম") shouldBe "কাম"
        rules("tamil").compose("கஆல") shouldBe "கால"
        rules("telugu").compose("కఆల") shouldBe "కాల"
        rules("gujarati").compose("કઆમ") shouldBe "કામ"
    }
    test("decompose gives the keys back") {
        for ((script, words) in mapOf(
            "devanagari" to listOf("नमस्ते", "भारत", "किताब", "हिन्दी", "मैं"),
            "bengali" to listOf("বাংলা", "কাম"), "tamil" to listOf("தமிழ்", "கால"), "telugu" to listOf("తెలుగు"),
        )) {
            val r = rules(script)
            for (w in words) r.compose(r.decompose(w)) shouldBe w
        }
        trailingWord("मैं नमस्ते") shouldBe "नमस्ते"
    }
    test("a Hindi word list along its keys (rules path): typed vowel keys reach the word") {
        val lex = Lexicon.parse("#dogboard-lexicon 1\nकिताब\t500\nनमस्ते\t100\n".reader().buffered(), TypingEngine.pathOf(LanguageRules(path = "rules:devanagari")))
        lex.firstForm(lex.find("कइतआब")) shouldBe "किताब"
        lex.firstForm(lex.find("नमस्तए")) shouldBe "नमस्ते"
        RuleComposer.forLanguage("mr-IN") shouldBe RuleComposer.forScript("devanagari_eyelash_reph")
        RuleComposer.forLanguage("hi") shouldBe null // compact: the keys as typed, the dictionary corrects
        RuleComposer.forLayout("hindi-hi", "hi") shouldBe RuleComposer.forScript("devanagari") // Gboard's full Hindi
    }
    test("context labels: what a key writes after the text before it") {
        val d = rules("devanagari")
        d.labelFor("क", 'आ') shouldBe "का"
        d.labelFor("क्ष", 'ई') shouldBe "क्षी"
        d.labelFor("", 'आ') shouldBe null // nothing to join: the key's own letter
        d.labelFor("क", 'क') shouldBe null
    }
    test("every language's rule set loads") {
        for (script in RuleComposer.SCRIPT_OF.values.toSet()) rules(script).compose("") shouldBe ""
    }
})
