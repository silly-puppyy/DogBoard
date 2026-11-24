#!/usr/bin/env python3
"""The language students next to English (tools/lm/lang_quality.py, 2026-11-24, user: "versus English (greedy token
selection), how do the other language ones do? Cohesiveness, knowledge, grammar").

Per language, same size models (12x384), same protocol:
  - greedy continuations: the first 6 words of 40 real sentences (the language's human set; English: the Aalto typing
    set), 24 pieces greedily; written to OUT/<lang>.gen.jsonl for lang_judge.py (coherence and grammar, 1-5) and
    measured here for repetition (share of repeated word bigrams, a loop sign);
  - knowledge: 15 facts as 4-way multiple choice (the answer and three of its kind), scored by the answer's summed
    log-probability after the prompt; the same facts in every language.

    lang_quality.py OUT [langs]
"""
import json, os, re, sys
import sentencepiece as spm
import torch
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from student import Config, Student
import caps as capsmod

D = "/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data"
CK = f"{D}/lm/ckpt"
PB = "/home/syn/.claude/jobs/eb2fb8fd/tmp/predbench"
MODELS = {"en": "v3-modern12x384.pt", **{l: f"{l}-R0-12x384.pt" for l in ("es", "fr", "de", "it", "pt", "ru", "fi")}}
DEV = "cuda" if torch.cuda.is_available() else "cpu"

# (prompt, answer, three wrong answers of the same kind); one set, written in each language
FACTS = {
    "en": [("The capital of France is", "Paris", ["Berlin", "Madrid", "Rome"]),
           ("The capital of Japan is", "Tokyo", ["Beijing", "Seoul", "Bangkok"]),
           ("Water freezes at zero degrees", "Celsius", ["Fahrenheit", "Kelvin", "minutes"]),
           ("The largest ocean on Earth is the", "Pacific", ["Atlantic", "Indian", "Arctic"]),
           ("Romeo and Juliet was written by", "Shakespeare", ["Dickens", "Tolstoy", "Cervantes"]),
           ("The planet closest to the Sun is", "Mercury", ["Mars", "Jupiter", "Saturn"]),
           ("A week has seven", "days", ["months", "hours", "years"]),
           ("The Eiffel Tower is in", "Paris", ["London", "Rome", "Berlin"]),
           ("Bees make", "honey", ["milk", "bread", "wine"]),
           ("The opposite of hot is", "cold", ["big", "fast", "loud"]),
           ("Christmas is celebrated in", "December", ["June", "March", "August"]),
           ("The sun rises in the", "east", ["west", "north", "south"]),
           ("A triangle has three", "sides", ["wheels", "legs", "eyes"]),
           ("The Mona Lisa was painted by", "Leonardo", ["Picasso", "Monet", "Rembrandt"]),
           ("Cows give us", "milk", ["wool", "honey", "eggs"])],
    "es": [("La capital de Francia es", "París", ["Berlín", "Madrid", "Roma"]),
           ("La capital de Japón es", "Tokio", ["Pekín", "Seúl", "Bangkok"]),
           ("El agua se congela a cero grados", "Celsius", ["Fahrenheit", "Kelvin", "minutos"]),
           ("El océano más grande de la Tierra es el", "Pacífico", ["Atlántico", "Índico", "Ártico"]),
           ("Don Quijote fue escrito por", "Cervantes", ["Shakespeare", "Tolstói", "Dickens"]),
           ("El planeta más cercano al Sol es", "Mercurio", ["Marte", "Júpiter", "Saturno"]),
           ("Una semana tiene siete", "días", ["meses", "horas", "años"]),
           ("La Torre Eiffel está en", "París", ["Londres", "Roma", "Berlín"]),
           ("Las abejas hacen", "miel", ["leche", "pan", "vino"]),
           ("Lo contrario de caliente es", "frío", ["grande", "rápido", "alto"]),
           ("La Navidad se celebra en", "diciembre", ["junio", "marzo", "agosto"]),
           ("El sol sale por el", "este", ["oeste", "norte", "sur"]),
           ("Un triángulo tiene tres", "lados", ["ruedas", "patas", "ojos"]),
           ("La Mona Lisa fue pintada por", "Leonardo", ["Picasso", "Monet", "Rembrandt"]),
           ("Las vacas nos dan", "leche", ["lana", "miel", "huevos"])],
    "fr": [("La capitale de la France est", "Paris", ["Berlin", "Madrid", "Rome"]),
           ("La capitale du Japon est", "Tokyo", ["Pékin", "Séoul", "Bangkok"]),
           ("L'eau gèle à zéro degré", "Celsius", ["Fahrenheit", "Kelvin", "minute"]),
           ("Le plus grand océan de la Terre est l'océan", "Pacifique", ["Atlantique", "Indien", "Arctique"]),
           ("Les Misérables a été écrit par", "Hugo", ["Shakespeare", "Tolstoï", "Dickens"]),
           ("La planète la plus proche du Soleil est", "Mercure", ["Mars", "Jupiter", "Saturne"]),
           ("Une semaine compte sept", "jours", ["mois", "heures", "ans"]),
           ("La tour Eiffel se trouve à", "Paris", ["Londres", "Rome", "Berlin"]),
           ("Les abeilles fabriquent du", "miel", ["lait", "pain", "vin"]),
           ("Le contraire de chaud est", "froid", ["grand", "rapide", "fort"]),
           ("Noël est fêté en", "décembre", ["juin", "mars", "août"]),
           ("Le soleil se lève à l'", "est", ["ouest", "nord", "sud"]),
           ("Un triangle a trois", "côtés", ["roues", "pattes", "yeux"]),
           ("La Joconde a été peinte par", "Léonard", ["Picasso", "Monet", "Rembrandt"]),
           ("Les vaches nous donnent du", "lait", ["laine", "miel", "œufs"])],
    "de": [("Die Hauptstadt von Frankreich ist", "Paris", ["Berlin", "Madrid", "Rom"]),
           ("Die Hauptstadt von Japan ist", "Tokio", ["Peking", "Seoul", "Bangkok"]),
           ("Wasser gefriert bei null Grad", "Celsius", ["Fahrenheit", "Kelvin", "Minuten"]),
           ("Der größte Ozean der Erde ist der", "Pazifik", ["Atlantik", "Indik", "Arktik"]),
           ("Faust wurde geschrieben von", "Goethe", ["Shakespeare", "Tolstoi", "Dickens"]),
           ("Der Planet, der der Sonne am nächsten ist, ist", "Merkur", ["Mars", "Jupiter", "Saturn"]),
           ("Eine Woche hat sieben", "Tage", ["Monate", "Stunden", "Jahre"]),
           ("Der Eiffelturm steht in", "Paris", ["London", "Rom", "Berlin"]),
           ("Bienen machen", "Honig", ["Milch", "Brot", "Wein"]),
           ("Das Gegenteil von heiß ist", "kalt", ["groß", "schnell", "laut"]),
           ("Weihnachten feiert man im", "Dezember", ["Juni", "März", "August"]),
           ("Die Sonne geht im", "Osten", ["Westen", "Norden", "Süden"]),
           ("Ein Dreieck hat drei", "Seiten", ["Räder", "Beine", "Augen"]),
           ("Die Mona Lisa wurde gemalt von", "Leonardo", ["Picasso", "Monet", "Rembrandt"]),
           ("Kühe geben uns", "Milch", ["Wolle", "Honig", "Eier"])],
    "it": [("La capitale della Francia è", "Parigi", ["Berlino", "Madrid", "Roma"]),
           ("La capitale del Giappone è", "Tokyo", ["Pechino", "Seul", "Bangkok"]),
           ("L'acqua gela a zero gradi", "Celsius", ["Fahrenheit", "Kelvin", "minuti"]),
           ("L'oceano più grande della Terra è il", "Pacifico", ["Atlantico", "Indiano", "Artico"]),
           ("La Divina Commedia è stata scritta da", "Dante", ["Shakespeare", "Tolstoj", "Dickens"]),
           ("Il pianeta più vicino al Sole è", "Mercurio", ["Marte", "Giove", "Saturno"]),
           ("Una settimana ha sette", "giorni", ["mesi", "ore", "anni"]),
           ("La Torre Eiffel si trova a", "Parigi", ["Londra", "Roma", "Berlino"]),
           ("Le api producono il", "miele", ["latte", "pane", "vino"]),
           ("Il contrario di caldo è", "freddo", ["grande", "veloce", "alto"]),
           ("Il Natale si festeggia a", "dicembre", ["giugno", "marzo", "agosto"]),
           ("Il sole sorge a", "est", ["ovest", "nord", "sud"]),
           ("Un triangolo ha tre", "lati", ["ruote", "zampe", "occhi"]),
           ("La Gioconda è stata dipinta da", "Leonardo", ["Picasso", "Monet", "Rembrandt"]),
           ("Le mucche ci danno il", "latte", ["lana", "miele", "uova"])],
    "pt": [("A capital da França é", "Paris", ["Berlim", "Madrid", "Roma"]),
           ("A capital do Japão é", "Tóquio", ["Pequim", "Seul", "Bangkok"]),
           ("A água congela a zero graus", "Celsius", ["Fahrenheit", "Kelvin", "minutos"]),
           ("O maior oceano da Terra é o", "Pacífico", ["Atlântico", "Índico", "Ártico"]),
           ("Os Lusíadas foi escrito por", "Camões", ["Shakespeare", "Tolstói", "Dickens"]),
           ("O planeta mais próximo do Sol é", "Mercúrio", ["Marte", "Júpiter", "Saturno"]),
           ("Uma semana tem sete", "dias", ["meses", "horas", "anos"]),
           ("A Torre Eiffel fica em", "Paris", ["Londres", "Roma", "Berlim"]),
           ("As abelhas fazem", "mel", ["leite", "pão", "vinho"]),
           ("O contrário de quente é", "frio", ["grande", "rápido", "alto"]),
           ("O Natal é celebrado em", "dezembro", ["junho", "março", "agosto"]),
           ("O sol nasce no", "leste", ["oeste", "norte", "sul"]),
           ("Um triângulo tem três", "lados", ["rodas", "patas", "olhos"]),
           ("A Mona Lisa foi pintada por", "Leonardo", ["Picasso", "Monet", "Rembrandt"]),
           ("As vacas nos dão", "leite", ["lã", "mel", "ovos"])],
    "ru": [("Столица Франции —", "Париж", ["Берлин", "Мадрид", "Рим"]),
           ("Столица Японии —", "Токио", ["Пекин", "Сеул", "Бангкок"]),
           ("Вода замерзает при нуле градусов по", "Цельсию", ["Фаренгейту", "Кельвину", "минутам"]),
           ("Самый большой океан на Земле —", "Тихий", ["Атлантический", "Индийский", "Северный"]),
           ("«Войну и мир» написал", "Толстой", ["Шекспир", "Пушкин", "Диккенс"]),
           ("Ближайшая к Солнцу планета —", "Меркурий", ["Марс", "Юпитер", "Сатурн"]),
           ("В неделе семь", "дней", ["месяцев", "часов", "лет"]),
           ("Эйфелева башня находится в", "Париже", ["Лондоне", "Риме", "Берлине"]),
           ("Пчёлы делают", "мёд", ["молоко", "хлеб", "вино"]),
           ("Противоположность горячего —", "холодный", ["большой", "быстрый", "громкий"]),
           ("Католическое Рождество празднуют в", "декабре", ["июне", "марте", "августе"]),
           ("Солнце встаёт на", "востоке", ["западе", "севере", "юге"]),
           ("У треугольника три", "стороны", ["колеса", "ноги", "глаза"]),
           ("Мону Лизу написал", "Леонардо", ["Пикассо", "Моне", "Рембрандт"]),
           ("Коровы дают нам", "молоко", ["шерсть", "мёд", "яйца"])],
    "fi": [("Ranskan pääkaupunki on", "Pariisi", ["Berliini", "Madrid", "Rooma"]),
           ("Japanin pääkaupunki on", "Tokio", ["Peking", "Soul", "Bangkok"]),
           ("Vesi jäätyy nollassa asteessa", "Celsiusta", ["Fahrenheitia", "Kelviniä", "minuuttia"]),
           ("Maapallon suurin valtameri on", "Tyynimeri", ["Atlantti", "Intian valtameri", "Jäämeri"]),
           ("Seitsemän veljestä kirjoitti", "Kivi", ["Shakespeare", "Tolstoi", "Dickens"]),
           ("Aurinkoa lähin planeetta on", "Merkurius", ["Mars", "Jupiter", "Saturnus"]),
           ("Viikossa on seitsemän", "päivää", ["kuukautta", "tuntia", "vuotta"]),
           ("Eiffel-torni on", "Pariisissa", ["Lontoossa", "Roomassa", "Berliinissä"]),
           ("Mehiläiset tekevät", "hunajaa", ["maitoa", "leipää", "viiniä"]),
           ("Kuuman vastakohta on", "kylmä", ["iso", "nopea", "kova"]),
           ("Joulua vietetään", "joulukuussa", ["kesäkuussa", "maaliskuussa", "elokuussa"]),
           ("Aurinko nousee", "idästä", ["lännestä", "pohjoisesta", "etelästä"]),
           ("Kolmiossa on kolme", "sivua", ["pyörää", "jalkaa", "silmää"]),
           ("Mona Lisan maalasi", "Leonardo", ["Picasso", "Monet", "Rembrandt"]),
           ("Lehmät antavat meille", "maitoa", ["villaa", "hunajaa", "munia"])],
}


def load(lang):
    s = torch.load(f"{CK}/{MODELS[lang]}", map_location=DEV, weights_only=False)
    sp = spm.SentencePieceProcessor(model_file=os.path.join(s.get("tokdir", f"{D}/lm-text/pilot/"), "sp16k.model"))
    m = Student(Config(**s["config"])).to(DEV).eval(); m.load_state_dict(s["model"])
    return m, sp, s.get("caps", False)


def encode(sp, caps, text):
    return capsmod.encode(sp, text) if caps else sp.encode(text)


def decode(sp, ids):
    """Pieces to text; a CAP id capitalises the next word's first letter."""
    out, cap = [], False
    for i in ids:
        if i == capsmod.CAP:
            cap = True; continue
        out.append(("\x01" if cap else "") + sp.id_to_piece(i)); cap = False
    text = "".join(out).replace("<s>", " ").replace("</s>", " ").replace("▁", " ")
    text = re.sub("\x01(\\s*)(\\S)", lambda m: m.group(1) + m.group(2).upper(), text)
    return text.replace("\x01", "").strip()


@torch.no_grad()
def greedy(m, sp, caps, prompt, n=int(os.environ.get("PIECES", "24"))):
    ids = [1] + encode(sp, caps, prompt)
    gen = []
    for _ in range(n):
        t = torch.tensor([ids[-m.cfg.ctx:] if hasattr(m, "cfg") else ids[-256:]], device=DEV)
        nxt = int(m(t)[0, -1].argmax())
        # the end of the text, or the sentence separator: one continuation is one sentence; FIXED=1 writes the same
        # length for every model instead (short and empty outputs made the judged scores incomparable)
        if os.environ.get("FIXED") != "1" and (nxt in (2, 0) or "<s>" in sp.id_to_piece(nxt)): break
        ids.append(nxt); gen.append(nxt)
    return decode(sp, gen)


@torch.no_grad()
def answer_logp(m, sp, caps, prompt, answer):
    ctx = [1] + encode(sp, caps, prompt)
    full = [1] + encode(sp, caps, prompt + " " + answer)
    k = len(ctx)
    while k > 0 and full[:k] != ctx[:k]: k -= 1  # where the two encodings part (a piece may merge across the space)
    t = torch.tensor([full], device=DEV)
    lp = torch.log_softmax(m(t)[0].float(), -1)
    return sum(float(lp[i - 1, full[i]]) for i in range(k, len(full)))


def prompts(lang, n=40):
    path = f"{PB}/aalto-clean.txt" if lang == "en" else f"{PB}/lang/human-{lang}.txt"
    out = []
    for line in open(path, encoding="utf-8"):
        w = line.split()
        if len(w) >= 10: out.append(" ".join(w[:6]))
        if len(out) >= n: break
    return out


def repetition(text):
    w = text.lower().split()
    bg = list(zip(w, w[1:]))
    return 0.0 if not bg else 1 - len(set(bg)) / len(bg)


def main():
    out = sys.argv[1]; os.makedirs(out, exist_ok=True)
    langs = sys.argv[2].split(",") if len(sys.argv) > 2 else list(MODELS)
    for lang in langs:
        m, sp, caps = load(lang)
        rows = [{"lang": lang, "prompt": p, "cont": greedy(m, sp, caps, p)} for p in prompts(lang)]
        with open(f"{out}/{lang}.gen.jsonl", "w", encoding="utf-8") as f:
            for r in rows: f.write(json.dumps(r, ensure_ascii=False) + "\n")
        rep = sum(repetition(r["cont"]) for r in rows) / len(rows)
        right = 0
        for prompt, ans, wrong in FACTS[lang]:
            scores = {a: answer_logp(m, sp, caps, prompt, a) for a in [ans] + wrong}
            right += max(scores, key=scores.get) == ans
        res = {"lang": lang, "knowledge": round(100 * right / len(FACTS[lang]), 1), "repeated_bigrams": round(100 * rep, 1)}
        json.dump(res, open(f"{out}/{lang}.scores.json", "w"))
        print(res, "| e.g.", rows[0]["prompt"], "→", rows[0]["cont"][:80], flush=True)
        del m; torch.cuda.empty_cache()


if __name__ == "__main__":
    main()
