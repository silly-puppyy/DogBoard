package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Next-word benchmark (DOGBOARD_PRED_BENCH=text file, DOGBOARD_PRED_SENTENCES=n, DOGBOARD_PRED_OUT=dir):
 * for each word position, the top 3 next words from the bigram followers (what the bar shows today)
 * and from the trigram model (DOGBOARD_NGRAMS), as TSV: sentence, position, target, top1|top2|top3.
 * Compared with other models' predictions by tools outside the app.
 */
class PredictionBenchTest : FunSpec({
    val text = System.getenv("DOGBOARD_PRED_BENCH")?.let { File(it) }
    test("next-word predictions").config(enabled = text?.isFile == true) {
        val lexicon = File(System.getenv("DOGBOARD_LEXICON") ?: "src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val ngrams = System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }?.inputStream()?.use { NgramModel.readBinary(it) }
        val n = System.getenv("DOGBOARD_PRED_SENTENCES")?.toIntOrNull() ?: 200
        val out = File(System.getenv("DOGBOARD_PRED_OUT") ?: "build/predbench").apply { mkdirs() }
        // any alphabet (the per-language sets: accented Latin, Cyrillic), as hf_nwp_lang.py / student_nwp_lang.py
        val word = Regex("[\\p{L}][\\p{L}'’]*")
        fun lexId(w: String): Int {
            val node = lexicon.find(w.lowercase()).takeIf { it != Lexicon.NONE } ?: return -1
            return lexicon.wordId(node)
        }
        File(out, "bigram.tsv").printWriter().use { bi ->
            File(out, "trigram.tsv").printWriter().use { tri ->
                text!!.readLines().take(n).forEachIndexed { si, line ->
                    val words = line.split(' ').filter { it.isNotEmpty() }
                    for (i in 1 until words.size) {
                        val target = words[i].trim('.', ',', '!', '?', ';', ':', '"', '(', ')', '\'', '¡', '¿', '«', '»')
                        if (!word.matches(target)) continue
                        val prev = words[i - 1].trim('.', ',', '!', '?', ';', ':', '"', '(', ')', '\'', '¡', '¿', '«', '»').lowercase()
                        val top = bigrams.followers(lexId(prev), 3).map { (id, _) -> lexicon.word(lexicon.nodeOfWord(id))!! }
                        bi.println("$si\t$i\t$target\t${top.joinToString("|")}")
                        if (ngrams != null) {
                            val p2 = if (i >= 2) words[i - 2].trim('.', ',', '!', '?', ';', ':', '"', '(', ')', '\'', '¡', '¿', '«', '»').lowercase() else "<s>"
                            val t3 = ngrams.followers(ngrams.idOf(p2), ngrams.idOf(prev), 3).map { (id, _) -> ngrams.word(id) }
                            tri.println("$si\t$i\t$target\t${t3.joinToString("|")}")
                        }
                    }
                }
            }
        }
    }
})
