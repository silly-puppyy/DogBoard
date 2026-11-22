"""First-person gender agreement in generated Spanish / French chat (private study), as filter_gender_ru.py for
Russian: after the first-person forms that make an adjective or participle agree with the speaker (fr "je suis",
"je me suis", "j'étais", "je serai"; es "estoy", "soy", "estaba", "era", "me siento", "me quedé"), with at most one
adverb between ("très", "muy", ...), the word's ending is set to the speaker's gender (seeds/<lang>.json "gender")
when the other form is a word of the pack's lexicon: es -o <-> -a, -os <-> -as; fr masculine <-> +e. Words with no
lexicon partner stay as written ("je suis malade", "estoy bien").
Input: gen_groupchat.py output with KEEP_NAMES=1 (index, name, message). Output: index, message.
Usage: filter_gender_romance.py es|fr in.tsv seeds/<lang>.json lexicon.tsv out.tsv"""
import re, sys, json, collections

lang, src, seeds, lexicon, out = sys.argv[1:6]
gender = json.load(open(seeds, encoding='utf-8'))['gender']
lex = {l.split('\t')[0].lower() for l in open(lexicon, encoding='utf-8') if not l.startswith('#')}
ADV = r"(?:(?:très|trop|tellement|vraiment|si|un peu|muy|tan|bastante|super|re|algo)\s+)?"
TRIGGER = {'fr': r"\b(je suis|je me suis|j['’]étais|je serai|je me sens|je reste)\s+" + ADV + r"(\w+)",
           'es': r"\b(estoy|soy|estaba|era|estaré|me siento|me quedé|quedé)\s+" + ADV + r"(\w+)"}[lang]
PAT = re.compile(TRIGGER, re.I)
stats = collections.Counter()


def partner(word, g):
    """The word in gender g, if the lexicon has that form; None if it is already g or has no partner."""
    w = word.lower()
    if lang == 'es':
        pairs = [('os', 'as'), ('o', 'a')]
        for m_end, f_end in pairs:
            if g == 'femn' and w.endswith(m_end) and w[:-len(m_end)] + f_end in lex: return w[:-len(m_end)] + f_end
            if g == 'masc' and w.endswith(f_end) and w[:-len(f_end)] + m_end in lex: return w[:-len(f_end)] + m_end
            if w.endswith(m_end) or w.endswith(f_end): return None
        return None
    if g == 'femn' and not w.endswith('e') and w + 'e' in lex: return w + 'e'
    if g == 'femn' and w.endswith('s') and not w.endswith('es') and w[:-1] + 'es' in lex: return w[:-1] + 'es'
    if g == 'masc' and w.endswith('e') and w[:-1] in lex and w[:-1][-1:] in 'éisuntr': return w[:-1]
    return None


def fix(msg, g):
    def repl(m):
        word = m.group(2); stats['checked'] += 1
        new = partner(word, g)
        if not new: return m.group(0)
        stats['corrected'] += 1
        if word[0].isupper(): new = new[0].upper() + new[1:]
        return m.group(0)[:m.start(2) - m.start(0)] + new
    return PAT.sub(repl, msg)


with open(src, encoding='utf-8') as f, open(out, 'w', encoding='utf-8') as o:
    for line in f:
        idx, name, msg = line.rstrip('\n').split('\t', 2)
        g = gender.get(name); stats['lines'] += 1
        o.write(f'{idx}\t{fix(msg, g) if g else msg}\n')
print(dict(stats), file=sys.stderr)
