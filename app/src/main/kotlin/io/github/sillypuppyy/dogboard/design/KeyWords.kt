package io.github.sillypuppyy.dogboard.design

/**
 * The words on the keys in the keyboard's own language, as iOS writes them: the space bar ("интервал" on a Bulgarian
 * keyboard), the action key (search, go, send, next, done, ...), shift / delete / return on themes that spell them out,
 * and the letters key on the symbol pages ("АБВ"). By language code; a word a language lacks here shows the key's
 * icon rather than an English word. English, and languages not listed, keep KbSpec's English words.
 * Written for DogBoard (not taken from another keyboard), checked against common usage; native corrections welcome.
 */
object KeyWords {
    private const val KEYS = "space return shift delete search go send next previous done join route continue abc"

    private fun row(vararg w: String): Map<String, String> =
        KEYS.split(' ').zip(w.toList()).filter { it.second.isNotEmpty() }.toMap()

    private val CYR = "АБВ"

    //                        space          return       shift      delete       search        go          send          next         previous     done        join          route        continue     abc
    private val T: Map<String, Map<String, String>> = mapOf(
        "bg" to row("интервал", "нов ред", "", "", "Търсене", "Отиди", "Изпрати", "Напред", "Назад", "Готово", "", "", "", CYR),
        "ru" to row("пробел", "ввод", "", "", "Найти", "Перейти", "Отправить", "Далее", "Назад", "Готово", "Войти", "Маршрут", "Продолжить", CYR),
        "uk" to row("пробіл", "ввід", "", "", "Пошук", "Перейти", "Надіслати", "Далі", "Назад", "Готово", "", "", "Продовжити", CYR),
        "be" to row("прабел", "", "", "", "Пошук", "Перайсці", "Адправіць", "Далей", "Назад", "Гатова", "", "", "", CYR),
        "sr" to row("размак", "", "", "", "Тражи", "Иди", "Пошаљи", "Даље", "Назад", "Готово", "", "", "", CYR),
        "mk" to row("празно место", "", "", "", "Барај", "Оди", "Испрати", "Следно", "Назад", "Готово", "", "", "", CYR),
        "kk" to row("бос орын", "", "", "", "Іздеу", "Өту", "Жіберу", "Келесі", "Артқа", "Дайын", "", "", "", CYR),
        "de" to row("Leerzeichen", "", "", "", "Suchen", "Los", "Senden", "Weiter", "Zurück", "Fertig", "Beitreten", "Route", "Weiter", ""),
        "fr" to row("espace", "retour", "maj", "suppr", "Rechercher", "Aller", "Envoyer", "Suivant", "Précédent", "OK", "Rejoindre", "Itinéraire", "Continuer", ""),
        "es" to row("espacio", "intro", "", "", "Buscar", "Ir", "Enviar", "Siguiente", "Anterior", "OK", "Unirse", "Ruta", "Continuar", ""),
        "it" to row("spazio", "invio", "", "", "Cerca", "Vai", "Invia", "Avanti", "Indietro", "Fine", "Partecipa", "Percorso", "Continua", ""),
        "pt" to row("espaço", "retorno", "", "", "Buscar", "Ir", "Enviar", "Seguinte", "Anterior", "OK", "Entrar", "Rota", "Continuar", ""),
        "nl" to row("spatie", "", "", "", "Zoek", "Ga", "Verstuur", "Volgende", "Vorige", "Gereed", "Doe mee", "Route", "Ga door", ""),
        "sv" to row("mellanslag", "retur", "", "", "Sök", "Gå", "Skicka", "Nästa", "Föregående", "Klar", "Anslut", "Rutt", "Fortsätt", ""),
        "da" to row("mellemrum", "retur", "", "", "Søg", "Gå", "Send", "Næste", "Forrige", "OK", "Deltag", "Rute", "Fortsæt", ""),
        "nb" to row("mellomrom", "retur", "", "", "Søk", "Gå", "Send", "Neste", "Forrige", "Ferdig", "Bli med", "Rute", "Fortsett", ""),
        "fi" to row("välilyönti", "rivinvaihto", "", "", "Hae", "Siirry", "Lähetä", "Seuraava", "Edellinen", "Valmis", "Liity", "Reitti", "Jatka", ""),
        "pl" to row("spacja", "", "", "", "Szukaj", "Idź", "Wyślij", "Dalej", "Wstecz", "Gotowe", "Dołącz", "Trasa", "Kontynuuj", ""),
        "cs" to row("mezera", "", "", "", "Hledat", "Jdi", "Odeslat", "Další", "Předchozí", "Hotovo", "Připojit", "Trasa", "Pokračovat", ""),
        "sk" to row("medzera", "", "", "", "Hľadať", "Choď", "Odoslať", "Ďalej", "Späť", "Hotovo", "", "", "Pokračovať", ""),
        "sl" to row("preslednica", "", "", "", "Išči", "Pojdi", "Pošlji", "Naprej", "Nazaj", "Končano", "", "", "Nadaljuj", ""),
        "hr" to row("razmak", "", "", "", "Traži", "Idi", "Pošalji", "Dalje", "Natrag", "Gotovo", "", "", "Nastavi", ""),
        "bs" to row("razmak", "", "", "", "Traži", "Idi", "Pošalji", "Dalje", "Nazad", "Gotovo", "", "", "Nastavi", ""),
        "ro" to row("spațiu", "", "", "", "Caută", "Mergi", "Trimite", "Înainte", "Înapoi", "Gata", "", "", "Continuă", ""),
        "hu" to row("szóköz", "", "", "", "Keresés", "Ugrás", "Küldés", "Tovább", "Vissza", "Kész", "", "", "Folytatás", ""),
        "el" to row("διάστημα", "", "", "", "Αναζήτηση", "Μετάβαση", "Αποστολή", "Επόμενο", "Προηγούμενο", "Τέλος", "", "", "Συνέχεια", "ΑΒΓ"),
        "tr" to row("boşluk", "", "", "", "Ara", "Git", "Gönder", "İleri", "Geri", "Bitti", "Katıl", "Rota", "Devam", ""),
        "ca" to row("espai", "", "", "", "Cerca", "Ves", "Envia", "Següent", "Anterior", "Fet", "", "", "Continua", ""),
        "et" to row("tühik", "", "", "", "Otsi", "Mine", "Saada", "Edasi", "Tagasi", "Valmis", "", "", "Jätka", ""),
        "lv" to row("atstarpe", "", "", "", "Meklēt", "Aiziet", "Sūtīt", "Tālāk", "Atpakaļ", "Gatavs", "", "", "Turpināt", ""),
        "lt" to row("tarpas", "", "", "", "Ieškoti", "Eiti", "Siųsti", "Toliau", "Atgal", "Atlikta", "", "", "Tęsti", ""),
        "vi" to row("dấu cách", "", "", "", "Tìm", "Đi", "Gửi", "Tiếp", "Trước", "Xong", "", "", "Tiếp tục", ""),
        "id" to row("spasi", "", "", "", "Cari", "Buka", "Kirim", "Berikutnya", "Sebelumnya", "Selesai", "", "", "Lanjutkan", ""),
        "ms" to row("ruang", "", "", "", "Cari", "Pergi", "Hantar", "Seterusnya", "Sebelumnya", "Selesai", "", "", "Teruskan", ""),
        "he" to row("רווח", "", "", "", "חפש", "עבור", "שלח", "הבא", "הקודם", "סיום", "", "", "המשך", ""),
        "ar" to row("مسافة", "", "", "", "بحث", "انتقال", "إرسال", "التالي", "السابق", "تم", "", "", "متابعة", ""),
        "fa" to row("فاصله", "", "", "", "جستجو", "برو", "ارسال", "بعدی", "قبلی", "تمام", "", "", "ادامه", ""),
        "hi" to row("स्पेस", "", "", "", "खोजें", "जाएं", "भेजें", "अगला", "पिछला", "हो गया", "", "", "जारी रखें", ""),
        "ja" to row("空白", "改行", "", "", "検索", "開く", "送信", "次へ", "前へ", "完了", "", "", "続ける", ""),
        "ko" to row("스페이스", "", "", "", "검색", "이동", "보내기", "다음", "이전", "완료", "", "", "계속", ""),
        "zh" to row("空格", "换行", "", "", "搜索", "前往", "发送", "下一项", "上一项", "完成", "", "", "继续", ""),
        "th" to row("เว้นวรรค", "", "", "", "ค้นหา", "ไป", "ส่ง", "ถัดไป", "ก่อนหน้า", "เสร็จ", "", "", "ดำเนินการต่อ", ""),
    )

    /** The words for a keyboard in [language] (an ISO 639 code; "no" reads as "nb"), or empty: English. */
    fun of(language: String): Map<String, String> = T[if (language == "no") "nb" else language] ?: emptyMap()
}
