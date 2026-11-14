package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The second word of context (previousContext2) and its use by a trigram model in the engine. */
class TwoWordContextTest : FunSpec({
    test("previousContext2 is the word before the previous one, or the sentence start") {
        previousContext2("I love ") shouldBe "i"
        previousContext2("Hello. I love ") shouldBe "i"
        previousContext2("Love ") shouldBe Bigrams.START
        previousContext2("") shouldBe null
        previousContext2("Done. ") shouldBe null
    }

    test("a trigram model changes the choice that one word of context can't make") {
        // after "i love", "you" (trigram) beats "your" although the pair "love your" is likelier
        val arpa = """
            \data\
            ngram 1=5
            ngram 2=2
            ngram 3=1

            \1-grams:
            -1.0	<s>	-0.5
            -1.5	i	-0.2
            -2.0	love	-0.3
            -2.0	you	-0.1
            -2.0	your	-0.1

            \2-grams:
            -0.5	love your	-0.1
            -0.9	love you	-0.1

            \3-grams:
            -0.05	i love you

            \end\
        """.trimIndent()
        val m = NgramModel.parseArpa(arpa.reader().buffered())
        val lex = Lexicon.of(mapOf("i" to 100L, "love" to 50L, "you" to 40L, "your" to 40L))
        val scorer = NgramScorer(m, lex, sentenceStart = lex.size)
        val i = lex.idOf("i"); val love = lex.idOf("love"); val you = lex.idOf("you"); val your = lex.idOf("your")
        (scorer.logProb(i, love, you, -9.0) > scorer.logProb(i, love, your, -9.0)) shouldBe true
        // with one word of context, the pair decides
        (scorer.logProb(-1, love, your, -9.0) > scorer.logProb(-1, love, you, -9.0)) shouldBe true
    }
})
