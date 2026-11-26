package io.github.sillypuppyy.dogboard.engine

import kotlin.math.ln

/**
 * Agreement errors ("le maison", "las casa") for the spell checker's blue underline (Gboard's "Grammar check"; iOS has
 * none). A word is checked against the other forms of its family: a closed determiner set (le / la / les) or the same
 * stem with the language's adjective endings (petit / petite / petits / petites), the forms the word list has. Each
 * form is scored by its fit with the two words before and the word after (the trigram); the word is flagged when
 * another form fits clearly better, by [DET_MARGINS] (log10, per language) for determiners and the stricter [ENDING_MARGIN] for
 * ending families (nouns and participles share the endings). With the packs' own pruned trigram (GrammarCheckEvalTest,
 * 2026-11-26, news and people's chat, 3,000 sentences each the margins were not chosen on): margins 2.5 / 4.5 flag
 * 0.13-0.17 % of chat sentences and 0.30-0.53 % of news ones, and catch 46-56 % of agreement errors with the right
 * form; 3.5 / 4.5 flagged 0.07-0.20 % but caught 31-40 %, missing the common article errors ("le maison" fits "la" by
 * 2.7, "un voiture" by 3.1); 2 / 3 caught 61-66 % but flagged 1.3-2.3 %. Italian and Portuguese at 3.5 / 4.5
 * ([DET_MARGINS]): 0.10 / 0.23 % of chat, 0.37 / 0.27 % of news, 46 / 50 % caught. French, Spanish, Italian and
 * Portuguese.
 */
object GrammarCheck {
    data class Flag(val start: Int, val length: Int, val suggestion: String)

    /**
     * Per language: Italian's and Portuguese's articles follow the next word's sound (lo / il, dei / degli) or are
     * prepositions too (pt a), so 2.5 flagged 0.57-0.83 % of chat sentences there; 3.5 flags 0.10-0.23 % and catches
     * 46-50 % (2026-11-26, as below).
     */
    val DET_MARGINS = java.util.concurrent.ConcurrentHashMap(mapOf("fr" to 2.5, "es" to 2.5, "it" to 3.5, "pt" to 3.5))
    @Volatile var ENDING_MARGIN = 4.5
    private val LN10 = ln(10.0)

    private val DETS = mapOf(
        "fr" to listOf(
            listOf("le", "la", "les"), listOf("un", "une", "des"), listOf("ce", "cet", "cette", "ces"), listOf("mon", "ma", "mes"),
            listOf("ton", "ta", "tes"), listOf("son", "sa", "ses"), listOf("notre", "nos"), listOf("votre", "vos"), listOf("leur", "leurs"),
            listOf("du", "des"), listOf("au", "aux"), listOf("quel", "quelle", "quels", "quelles"), listOf("aucun", "aucune"),
            listOf("tout", "toute", "tous", "toutes"),
        ),
        "es" to listOf(
            listOf("el", "la", "los", "las"), listOf("un", "una", "unos", "unas"), listOf("este", "esta", "estos", "estas"),
            listOf("ese", "esa", "esos", "esas"), listOf("aquel", "aquella", "aquellos", "aquellas"), listOf("mi", "mis"),
            listOf("tu", "tus"), listOf("su", "sus"), listOf("nuestro", "nuestra", "nuestros", "nuestras"),
            listOf("vuestro", "vuestra", "vuestros", "vuestras"), listOf("otro", "otra", "otros", "otras"),
            listOf("todo", "toda", "todos", "todas"), listOf("mucho", "mucha", "muchos", "muchas"), listOf("poco", "poca", "pocos", "pocas"),
        ),
        "it" to listOf(
            listOf("il", "lo", "la", "i", "gli", "le"), listOf("un", "uno", "una"), listOf("questo", "questa", "questi", "queste"),
            listOf("quel", "quello", "quella", "quei", "quegli", "quelle"), listOf("mio", "mia", "miei", "mie"),
            listOf("tuo", "tua", "tuoi", "tue"), listOf("suo", "sua", "suoi", "sue"), listOf("nostro", "nostra", "nostri", "nostre"),
            listOf("vostro", "vostra", "vostri", "vostre"), listOf("del", "dello", "della", "dei", "degli", "delle"),
            listOf("al", "allo", "alla", "ai", "agli", "alle"), listOf("nel", "nello", "nella", "nei", "negli", "nelle"),
            listOf("molto", "molta", "molti", "molte"), listOf("tutto", "tutta", "tutti", "tutte"), listOf("altro", "altra", "altri", "altre"),
        ),
        "pt" to listOf(
            listOf("o", "a", "os", "as"), listOf("um", "uma", "uns", "umas"), listOf("este", "esta", "estes", "estas"),
            listOf("esse", "essa", "esses", "essas"), listOf("aquele", "aquela", "aqueles", "aquelas"),
            listOf("meu", "minha", "meus", "minhas"), listOf("teu", "tua", "teus", "tuas"), listOf("seu", "sua", "seus", "suas"),
            listOf("nosso", "nossa", "nossos", "nossas"), listOf("do", "da", "dos", "das"), listOf("no", "na", "nos", "nas"),
            listOf("pelo", "pela", "pelos", "pelas"), listOf("todo", "toda", "todos", "todas"), listOf("outro", "outra", "outros", "outras"),
            listOf("muito", "muita", "muitos", "muitas"),
        ),
    )
    private val ENDINGS = mapOf(
        "fr" to listOf(
            listOf("", "e", "s", "es"), listOf("é", "ée", "és", "ées"), listOf("eux", "euse", "euses"), listOf("if", "ive", "ifs", "ives"),
            listOf("al", "ale", "aux", "ales"), listOf("el", "elle", "els", "elles"), listOf("en", "enne", "ens", "ennes"),
            listOf("er", "ère", "ers", "ères"),
        ),
        "es" to listOf(listOf("o", "a", "os", "as"), listOf("e", "es"), listOf("", "es"), listOf("or", "ora", "ores", "oras"), listOf("z", "ces")),
        "it" to listOf(listOf("o", "a", "i", "e"), listOf("e", "i"), listOf("co", "ca", "chi", "che"), listOf("go", "ga", "ghi", "ghe")),
        "pt" to listOf(listOf("o", "a", "os", "as"), listOf("e", "es"), listOf("or", "ora", "ores", "oras"), listOf("ês", "esa", "eses", "esas")),
    )
    private val WORD = Regex("""[\p{L}\p{M}]+(?:['’][\p{L}\p{M}]+)*""")

    fun supports(lang: String) = lang.substringBefore('-') in DETS

    /**
     * Which of a keyboard's languages [text] is in: the one whose word list ([known], the first language's first) knows
     * the most of its words, the first on a tie. A text is checked in that language only: Spanish rules on English text
     * ("a la carte") would flag nonsense.
     */
    fun languageOf(text: String, known: List<(String) -> Boolean>): Int {
        if (known.size < 2) return 0
        val words = WORD.findAll(text).map { it.value.lowercase() }.toList()
        val counts = known.map { k -> words.count(k) }
        return counts.indices.maxByOrNull { counts[it] * 1000 - it } ?: 0
    }

    /** The other forms of [w]'s family that [known] holds, and whether they are a determiner set. */
    internal fun family(lang: String, w: String, known: (String) -> Boolean): Pair<List<String>, Boolean> {
        DETS[lang]?.firstOrNull { w in it }?.let { set -> return set.filter { it != w } to true }
        val out = LinkedHashSet<String>()
        for (ends in ENDINGS[lang].orEmpty()) for (e in ends) {
            if (e.isNotEmpty() && !w.endsWith(e)) continue
            val stem = w.dropLast(e.length)
            if (stem.length < 2) continue
            for (e2 in ends) { val f = stem + e2; if (f != w && known(f)) out.add(f) }
        }
        // an adjective has its gender and number forms (petit petite petits petites): a family of two is a noun's number
        // (projet / projets) or a verb's person (vayas / vaya), both often right either way (false alarms, 2026-11-26)
        if (out.size < 2) return emptyList<String>() to false
        return out.toList() to false
    }

    /**
     * The agreement errors in [text] for [lang] ("fr", "es"); [model]'s words are lowercase; [known] tells a word of the
     * language. Words the model lacks are not judged.
     */
    fun check(text: String, lang: String, model: NgramModel, known: (String) -> Boolean): List<Flag> {
        val l = lang.substringBefore('-')
        if (l !in DETS) return emptyList()
        val words = WORD.findAll(text).toList()
        val ids = words.map { model.idOf(it.value.lowercase().replace('’', '\'')) }
        val out = ArrayList<Flag>()
        for (i in words.indices) {
            val w = words[i].value.lowercase().replace('’', '\'')
            // an elided word (d'avalanches, l'ancienne) is left alone: its form depends on more than the next word
            if (ids[i] < 0 || '\'' in w) continue
            val (forms, det) = family(l, w, known)
            if (forms.isEmpty()) continue
            val a = if (i >= 2) ids[i - 2] else -1
            val b = if (i >= 1) ids[i - 1] else -1
            if (i >= 1 && b < 0) continue // an unknown word right before: no context to judge by
            val next = if (i + 1 < words.size) ids[i + 1] else -1
            fun fit(id: Int) = model.logProbOf(a, b, id) + (if (next >= 0) model.logProbOf(b, id, next) else 0.0)
            val own = fit(ids[i])
            val best = forms.mapNotNull { f -> model.idOf(f).takeIf { it >= 0 }?.let { f to fit(it) } }.maxByOrNull { it.second } ?: continue
            val margin = (if (det) DET_MARGINS[l] ?: 3.5 else ENDING_MARGIN) * LN10
            if (best.second - own >= margin) {
                val orig = words[i].value
                val suggestion = if (orig.first().isUpperCase()) best.first.replaceFirstChar(Char::uppercaseChar) else best.first
                out.add(Flag(words[i].range.first, orig.length, suggestion))
            }
        }
        return out
    }
}
