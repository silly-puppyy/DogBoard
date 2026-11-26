"""Shared helpers for the conversational-source study (notes/research/conversational-sources.md, 2026-11-25):
sentence splitting, the pack pipeline's tokens (as leipzig_sentences.py), and the "chattiness" measures.

Chattiness of a sentence set: share with a first/second-person pronoun (the PRONOUNS word lists below, written by hand
per language; verb endings are not counted, so pro-drop languages read low), share ending in a question mark, and the
median length in words."""
import os, re, statistics, sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from leipzig_sentences import WORD, lower  # the pack pipeline's tokenizer and lowercaser

PRONOUNS = {
    'bg': 'аз ти ние вие мен теб нас вас ми ни ви ме те ти моя мой моето твой твоя ваш наш',
    'es': 'yo tú tu vos usted ustedes nosotros nosotras vosotros vosotras me te mi mí ti nos os mío mía tuyo tuya tengo quiero',
    'ru': 'я ты мы вы меня мне тебя тебе нас нам вас вам мой моя твой ваш наш',
    'de': 'ich du wir ihr sie mich mir dich dir uns euch mein dein unser euer',
    'fr': 'je tu nous vous me te moi toi mon ma ton ta notre votre j',
    'it': 'io tu noi voi mi ti ci vi me te mio mia tuo tua nostro vostro',
    'pt': 'eu tu você vocês nós vós me te mim ti nos meu minha teu seu',
    'pl': 'ja ty my wy mnie mi ciebie ci nas nam was wam mój twój nasz wasz',
    'cs': 'já ty my vy mě mi tebe ti nás nám vás vám můj tvůj náš váš',
    'nl': 'ik jij je jullie wij we mij me jou u mijn jouw ons',
    'tr': 'ben sen biz siz beni bana seni sana bizi bize sizi size benim senin bizim sizin',
    'sv': 'jag du vi ni mig dig oss er min din vår',
    'ro': 'eu tu noi voi mă mi te ți ne vă îmi mea tău',
    'uk': 'я ти ми ви мене мені тебе тобі нас нам вас вам мій твій наш ваш',
    # 2026-11-25 (second batch): from grammar references (subject, object, possessive forms; clitics that are written apart),
    # not checked by native speakers. Words that are mostly something else are left out: Hebrew את (object marker), Hungarian mi
    # (what), Greek με / σε (with / in), Croatian and Serbian te / те (and), Danish i (in)
    'fi': 'minä mä sinä sä me te minut sinut meidät teidät minua sinua meitä teitä minun sinun meidän teidän minulle sinulle meille teille minulla sinulla meillä teillä minusta sinusta mun sun mua sua mulle sulle mulla sulla',
    'hu': 'én te ő ti engem téged minket titeket nekem neked nekünk nektek tőlem tőled tőlünk tőletek rólam rólad velem veled velünk veletek enyém tied miénk tiétek magam magad ön önök',
    'el': 'εγώ εγώ εσύ εσείς εμείς εγω εσυ εσεις εμεις εμένα εσένα εμάς εσάς εμενα εσενα εμας εσας μου σου μας σας δικός δική δικό',
    'id': 'aku saya kamu anda engkau kau kalian kita kami gue gua loe lu lo daku dikau',
    'he': 'אני אתה אתם אתן אנחנו אנו אותי אותך אותנו אתכם אתכן שלי שלך שלנו שלכם שלכן לי לך לנו לכם לכן ממני ממך אלי אליך איתי איתך איתנו',
    'ar': 'أنا انا أنت انت أنتِ نحن أنتم انتم أنتما أنتن انتن إياي إياك إيانا لي لك لنا لكم بي بك بنا بكم مني منك منا منكم معي معك معنا معكم عندي عندك عندنا',
    'fa': 'من تو ما شما مرا منو تورا شمارا خودم خودت خودمان خودتان خودمون خودتون',
    'hi': 'मैं मै मैंने हम हमने तुम तुमने तू तूने आप आपने मुझे मुझ मुझको मुझसे हमें हमको हमसे तुम्हें तुझे तुझको तेरा तेरी तेरे मेरा मेरी मेरे हमारा हमारी हमारे तुम्हारा तुम्हारी तुम्हारे आपका आपकी आपके आपको आपसे',
    'vi': 'tôi bạn mình anh chị em tớ cậu tao mày ta',
    'ko': '나 너 저 우리 당신 제 내 네 나는 난 나도 나를 날 나의 나만 나랑 나한테 내가 내게 저는 전 저도 저를 절 저의 저한테 제가 제게 너는 넌 너도 너를 널 너의 너한테 네가 니가 네게 우리는 우리가 우리의 우린 우리를 우리도 우리랑 당신은 당신이 당신의 당신을 여러분',
    'ca': 'jo tu vostè nosaltres vosaltres vostès em et ens us mi ti meu meva meus meves teu teva teus teves nostre nostra vostre vostra m t',
    'da': 'jeg du vi mig dig os jer min mit mine din dit dine vores jeres',
    'nb': 'jeg du vi dere meg deg oss min mi mitt mine din di ditt dine vår vårt våre',
    'sk': 'ja ty my vy mňa ma ťa teba mne mi ti tebe nám nás vám vás nami vami mnou tebou môj moja moje tvoj tvoja tvoje náš naša naše váš vaša vaše',
    'hr': 'ja ti mi vi mene me meni tebe tebi nas nama nam vas vama vam moj moja moje tvoj tvoja tvoje naš naša naše vaš vaša vaše',
    'sr': 'ја ти ми ви мене ме мени тебе теби нас нама нам вас вама вам мој моја моје твој твоја твоје наш наша наше ваш ваша ваше',
    'lt': 'aš tu mes jūs mane manęs man manimi tave tavęs tau tavimi mus mūsų mums jus jūsų jums mano tavo',
    'lv': 'es tu mēs jūs mani manis man tevi tevis tev mūs mūsu mums jūsu jums mans mana tavs tava',
    'et': 'mina sina meie teie mind sind meid teid mulle sulle meile teile minu sinu minul sinul meil teil minult sinult minust sinust minuga sinuga ma sa me te mu su mul sul',
}
PRON = {k: set(v.split()) for k, v in PRONOUNS.items()}

# ।॥ (Devanagari danda) and ؟ (Arabic question mark) end sentences as . and ? do
SPLIT = re.compile(r'(?<=[.!?…।॥؟])\s+|\n+')


def sentences(text, min_words=1):
    """Raw sentences (with their end mark) of a text."""
    for part in SPLIT.split(text):
        part = part.strip()
        if part and len(WORD.findall(part)) >= min_words:
            yield part


def words(sentence, lang=''):
    return [lower(w, lang).replace('’', "'") for w in WORD.findall(sentence)]


class Chat:
    """Accumulates the chattiness measures over raw sentences."""
    def __init__(self, lang):
        self.pron = PRON.get(lang, set()); self.lang = lang
        self.n = self.p = self.q = 0; self.lens = []

    def add(self, sentence):
        w = words(sentence, self.lang)
        if not w: return
        self.n += 1; self.lens.append(len(w))
        if self.pron & set(w): self.p += 1
        if sentence.rstrip().endswith('?'): self.q += 1

    def row(self):
        n = max(1, self.n)
        return dict(n=self.n, pronoun=round(100 * self.p / n, 1), question=round(100 * self.q / n, 1),
                    median_words=statistics.median(self.lens) if self.lens else 0)


# --- hosts left out of the conversational slice (adult, gambling, dating sites), 2026-11-25. FineWeb-2's long tail holds
# many such sites (German "pornogeschichte", Turkish "kizlarsoruyor", Czech "flirtkontakt"), and chatty-document ranking
# picks them (they are full of "you"). A host goes when its name carries one of HOST_WORDS or when at least HOST_SHARE of its
# lines (at least 50 lines) hold an explicit, gambling or dating word of the multilingual lists below (lists written by hand,
# substrings of lowercase words; approximate: a legitimate site with that much of such talk goes too, which costs nothing here).
import re as _re
HOST_WORDS = _re.compile(r'porn|xxx|sex|erot|escort|casino|kasino|cassino|poker|dating|flirt|randki|nackt|bordell|puff|livecam|gokken|bahis|bukmacher|sexcam')
EXPLICIT = _re.compile(r'sex|porn|xxx|erot|geil|fick|schwanz|orgas|penis|vagin|\b(?:pussy|cock|dick|tits|fuck\w*|boobs?|cum)\b|bdsm|nackt|\bnudes\b|секс|порн|хуй|пизд|ебат|ебал|seks|jebat|sikiş|sikis|amcık|yarak|orgazm|escort|prostitu|puttan|troia|bukkake|masturb|nippl|кінч|сперм|sperm|bordell')
GAMBLING = _re.compile(r'casino|kasino|cassino|poker|betting|bahis|apuest|казино|букмекер|sázk|bukmacher|gokken|spelautomat|slot machine|jackpot|roulette|ruletă|ruleta|lotto|aposta(?!l)')
DATING = _re.compile(r'dating|flirt|singles|знакомств|tanışma|randki|namoro|incontri|rencontre|partnervermittlung|seznamk|lesb|знайомств')
# 2026-11-25 second batch: terms for Arabic, Hebrew, Greek, Devanagari, Hangul, Vietnamese and the Latin/Cyrillic languages added since
# (hu id ca da nb sk hr sr lt lv et fi), written from the obvious adult / gambling / dating words of each language, not checked by native
# speakers. Words with an everyday meaning are left out (Arabic جنس "nationality / gender", Hebrew כוס "glass", Korean 보지 "not see",
# Greek γαμ "marriage"), so a few explicit sites pass and the host list per language (CONV_DROP_HOSTS) covers them.
HOST_WORDS = _re.compile(HOST_WORDS.pattern + r'|seks|szex|porno|bokep|togel|judi(?!c|t)|kasiino|kaszin|kazino|kazin|seksi|gokk|bet365|1xbet|xbet|fuck|pornhub|xvideos|redtube|sexo|sexe|sekss|sekas|сэкс|секс|порн|казино|casin|ставк'
    # Indonesian gambling and adult hosts too small for the share test (2026-11-26: 110 hosts, 3,600 lines in the id slice):
    # online slots ("slot" alone is a castle in Danish / Norwegian / Swedish, so only in a gambling combination), "judi"
    # before "tembak ikan", nude / obscene words, adult-story sites
    r'|(?:agen|situs|daftar|demo|jud[i1]|dewa|hoki|lucky|joker|gacor|idn|macau|\d)slots?|slots?(?:\d|online|gacor|joker|pragmatic|deposit|pulsa|jackpot|game|gaming|club|id\b|rank|mania)'
    r'|jud[i1](?:slot|tembak|capsa|online|corona|terpercaya)|gacor|sbobet|joker123|mega888|pussy888|918kiss|bugil|mesum|ngentot|ceritadewasa|cerita-?se[kx]s|taruhan')
EXPLICIT = _re.compile(EXPLICIT.pattern +
    r'|سكس|بورن|إباحي|اباحي|نيك|شرموط|عاهر|قحب|ممحون|متناك'
    r'|سکس|پورن|جنده|کونی|کیری|شهوتی|ساک زدن'
    r'|סקס|פורנו|זונה|זונות|נערות ליווי|חרמן|מציצה|אורגזמה'
    r'|πορν|πόρν|σεξ|πουτάν|πούτσ|μουνί|καύλα|τσόντα|γαμήσ|γαμήθ|γαμώ|γαμάω|γαμημ|πίπα|καβλ|καύλ|μουνάκ'
    r'|सेक्स|पोर्न|चुदाई|चूत|लंड|रंडी|नंगी|अश्लील|कामुक|हस्तमैथुन|चोद|चुदा|गांड|गाँड|लौड़|चूचि|मुठ मार|कामवासना'
    r'|섹스|야동|포르노|야설|성인용품|성인방송|성인영화|성인만화|자위|섹파|딸치'
    r'|địt|\bđụ\b|\blồn\b|cặc|buồi|khiêu dâm|mại dâm|gái gọi|phim sex|dâm đãng'
    r'|szex|\bpina\b|punci|fasz|kurva|csöcs|segg|pillu|kyrpä|ngentot|memek|\bkontol\b|bokep|ngewe|bugil|mesum|cabul|colmek'
    r'|follar|cony|xupa|fisse|fitte|knulle|\bkåt|kokot|pi[čc]ka|prdel|jebat|jebanj|jebem|kurac|pizd|kekš|šliundr|dirsa'
    r'|порно|јеб|курац|пичка|курва|дупе|сиса')
GAMBLING = _re.compile(GAMBLING.pattern +
    r'|كازينو|قمار|مراهنات|بوكر|روليت|يانصيب'
    r'|کازینو|شرط بندی|پوکر|قمار|رولت'
    r'|קזינו|הימורים|פוקר|רולטה|לוטו'
    r'|καζίνο|στοίχημα|πόκερ|ρουλέτα|λόττο|κουλοχέρη|φρουτάκια'
    r'|कैसीनो|सट्टा|जुआ|पोकर|लॉटरी|रम्मी|तीन पत्ती|सट्टेबाजी'
    r'|카지노|바카라|토토|베팅|배팅|슬롯|경마|도박|룰렛|블랙잭|포커|먹튀'
    r'|sòng bạc|cá cược|cá độ|nhà cái|đánh bài|tài xỉu|bầu cua|lô đề'
    r'|kaszinó|fogadóiroda|sportfogadás|nyerőgép|rulett|lottó|póker|\bjudi\b|togel|slot gacor|taruhan|sbobet|apostes|pòquer|ruleta|spilleautomat|væddemål|veddemål'
    r'|kasíno|stávk|hracie automaty|lotéria|kladionic|klađenj|kockanj|kazino|ruletė|lažyb|pokeris|bukmek|derības|azartsp|kasiino|kihlveo|pokker|loterii|vedonly|kolikkopeli'
    r'|казино|клађ|кладионица|коцк|рулет')
DATING = _re.compile(DATING.pattern +
    r'|تعارف|مواعدة|دوستیابی|دوست یابی|همسریابی|היכרויות|הכרויות|דייטינג|γνωριμίες|γνωριμιών|डेटिंग|फ्लर्ट|소개팅|랜덤채팅|조건만남|애인대행|hẹn hò|tìm bạn gái|tìm người yêu'
    r'|társkereső|ismerkedés|kencan|cari jodoh|biro jodoh|solters|singler|zoznamk|pažintys|tutvusportaal|tutvuskuulutus|kohting|treffit|deittipalvelu|seuranhaku')
HOST_SHARE = 0.10


def unwanted_hosts(lines, hosts):
    """Hosts to drop from a slice: (host -> reason) for lines and the host of each line, in order."""
    n, hit = {}, {}
    for line, host in zip(lines, hosts):
        n[host] = n.get(host, 0) + 1
        for name, rx in (('explicit', EXPLICIT), ('gambling', GAMBLING), ('dating', DATING)):
            if rx.search(line): hit[(host, name)] = hit.get((host, name), 0) + 1
    out = {h: 'name' for h in n if HOST_WORDS.search(h)}
    for (h, name), c in hit.items():
        if n[h] >= 50 and c / n[h] >= HOST_SHARE and h not in out: out[h] = f'{name} {100 * c / n[h]:.0f} %'
    return out, n
