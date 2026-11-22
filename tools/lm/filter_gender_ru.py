"""First-person gender agreement in generated Russian chat (private study). Past-tense verbs and short adjectives
agree with the speaker ("я пришёл" / "я пришла", "я рад" / "я рада"); the teacher writes lines for made-up speakers
and can give a woman's name a masculine verb, which would teach the student wrong agreement. For every
"я <word>" or "я <adverb> <word>" (one word between) where pymorphy3 (MIT) reads the word as a singular past verb or
short adjective with a gender, a form that disagrees with the speaker (seeds/ru.json "gender") is CORRECTED with
pymorphy3's inflect (the line is kept); a form it cannot inflect drops the line.
Input: gen_groupchat.py output with KEEP_NAMES=1 (index, name, message). Output: index, message.
Usage: filter_gender_ru.py in.tsv seeds/ru.json out.tsv"""
import re, sys, json, collections
import pymorphy3

src, seeds, out = sys.argv[1:4]
gender = json.load(open(seeds, encoding='utf-8'))['gender']
m = pymorphy3.MorphAnalyzer()
TOKEN = re.compile(r'\w+|\W+')
stats = collections.Counter()


def agreeing(word):
    """The parse to check, if the word is a singular past verb or a short adjective with a gender."""
    for p in m.parse(word.lower())[:2]:
        if p.tag.gender in ('masc', 'femn') and (('VERB' in p.tag and 'past' in p.tag and 'sing' in p.tag) or 'ADJS' in p.tag):
            return p
    return None


def fix(msg, g):
    toks = TOKEN.findall(msg); words = [i for i, t in enumerate(toks) if t[0].isalnum()]
    for k, i in enumerate(words):
        if toks[i].lower() != 'я': continue
        for j in words[k + 1:k + 3]:  # the next word, or the one after an adverb
            p = agreeing(toks[j])
            if not p: continue
            stats['checked'] += 1
            if p.tag.gender == g: break
            q = p.inflect({g})
            if q is None: return None
            w = q.word if toks[j].islower() else q.word.capitalize()
            stats['corrected'] += 1; toks[j] = w; break
    return ''.join(toks)


with open(src, encoding='utf-8') as f, open(out, 'w', encoding='utf-8') as o:
    for line in f:
        idx, name, msg = line.rstrip('\n').split('\t', 2)
        g = gender.get(name)
        new = fix(msg, g) if g else msg
        stats['lines'] += 1
        if new is None: stats['dropped'] += 1; continue
        o.write(f'{idx}\t{new}\n')
print(dict(stats), file=sys.stderr)
