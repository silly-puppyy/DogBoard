"""Russian stem + ending split for the R1 tokenizer (private study; "tokenize the case endings
differently on purpose"). For each word type of the training text (seen at least twice), pymorphy3 (MIT; OpenCorpora
dictionaries, CC BY-SA) gives the lexeme of its most likely parse; the stem is the longest prefix every form of that
lexeme shares ("километров" -> километр + ов), the ending the rest. Only inflecting parts of speech; a split is kept
when the stem has at least 2 letters and the ending 1-4 letters (suppletive lexemes like человек / люди share no
stem and stay whole). Then only endings that are frequent enough (ENDING_MIN tokens in the text) become tokens.
Writes <out>/ru-endings.tsv: word, stem, ending (lowercase), and <out>/ru-ending-list.txt.
Usage: ru_morph.py train.txt OUT_DIR"""
import os, re, sys, collections
from multiprocessing import Pool

TEXT, OUT = sys.argv[1:3]
ENDING_MIN = 20000
INFLECTING = {'NOUN', 'ADJF', 'ADJS', 'COMP', 'VERB', 'INFN', 'PRTF', 'PRTS', 'NPRO', 'NUMR'}
WORD = re.compile(r'[а-яё]+')
_m = None


def split(word):
    global _m
    if _m is None:
        import pymorphy3
        _m = pymorphy3.MorphAnalyzer()
    p = _m.parse(word)[0]
    if p.tag.POS not in INFLECTING: return None
    forms = {f.word for f in p.lexeme}
    stem = os.path.commonprefix(sorted(forms))
    if not word.startswith(stem): return None
    ending = word[len(stem):]
    if len(stem) >= 2 and 1 <= len(ending) <= 4: return word, stem, ending
    return None


if __name__ == '__main__':
    counts = collections.Counter()
    with open(TEXT, encoding='utf-8') as f:
        for line in f: counts.update(WORD.findall(line.lower()))
    types = [w for w, c in counts.items() if c >= 2]
    print(f'{len(counts):,} types, {len(types):,} seen twice or more', flush=True)
    with Pool(4) as pool: rows = [r for r in pool.imap_unordered(split, types, chunksize=2000) if r]
    by_ending = collections.Counter()
    for w, stem, e in rows: by_ending[e] += counts[w]
    keep = {e for e, c in by_ending.items() if c >= ENDING_MIN}
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, 'ru-endings.tsv'), 'w', encoding='utf-8') as o:
        for w, stem, e in sorted(rows):
            if e in keep: o.write(f'{w}\t{stem}\t{e}\n')
    with open(os.path.join(OUT, 'ru-ending-list.txt'), 'w', encoding='utf-8') as o:
        for e, c in by_ending.most_common():
            if e in keep: o.write(f'{e}\t{c}\n')
    covered = sum(counts[w] for w, _, e in rows if e in keep)
    print(f'{len(keep)} endings kept; {sum(1 for r in rows if r[2] in keep):,} word types split, '
          f'{100 * covered / sum(counts.values()):.1f} % of word tokens', flush=True)
