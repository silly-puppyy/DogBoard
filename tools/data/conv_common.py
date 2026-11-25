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
}
PRON = {k: set(v.split()) for k, v in PRONOUNS.items()}

SPLIT = re.compile(r'(?<=[.!?…])\s+|\n+')


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
