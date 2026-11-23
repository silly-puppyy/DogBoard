"""sim_typos.py + word-level slips: real typing lands on another real word far more than finger noise
explains (Aalto: 22 % of 7+ letter typos vs 5 % simulated: "others" -> "other", "has" -> "had"). With
probability SLIP[length] a typo is a slip to a frequent lexicon word within 2 edits that shares the
word's first two letters (frequency^0.5 weighted), otherwise the motor typo. Any language with a lexicon.
  sim_typos2.py pairs typo_model.json lexicon.tsv real_pairs.tsv out.tsv
  sim_typos2.py text  typo_model.json lexicon.tsv in.txt out.txt [rows]"""
import sys, random, re, collections
from sim_typos import load, typo

# slip share of typos per length: (real - motor real-word share) / (1 - motor), Aalto vs sim_typos
SLIP = {2: 0.5, 3: 0.31, 4: 0.29, 5: 0.28, 6: 0.23}
SLIP_LONG = 0.18

def dl(a, b, cap=2):
    if abs(len(a) - len(b)) > cap: return cap + 1
    prev2 = None; prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] != b[j - 1]))
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]: cur[j] = min(cur[j], prev2[j - 2] + 1)
        if min(cur) > cap: return cap + 1
        prev2, prev = prev, cur
    return prev[-1]

class Slips:
    def __init__(self, lexicon, top=60000):
        words = []
        for l in open(lexicon, encoding='utf-8'):
            if l.startswith('#'): continue
            w, f = l.rstrip('\n').split('\t')[:2]
            if w.isalpha() and w.islower(): words.append((w, int(f)))
            if len(words) >= top: break
        self.by = collections.defaultdict(list)
        for w, f in words: self.by[w[:2]].append((w, f ** 0.5))
        self.cache = {}
    def pick(self, w, rnd):
        if w not in self.cache:
            self.cache[w] = [(c, f) for c, f in self.by.get(w[:2], []) if c != w and dl(w, c) <= 2]
        cs = self.cache[w]
        return rnd.choices([c for c, _ in cs], weights=[f for _, f in cs])[0] if cs else None

def typo2(word, m, pos, slips, rnd):
    t = typo(word, m, pos, rnd)
    if t == word: return word
    w = word.lower()
    if rnd.random() < SLIP.get(len(w), SLIP_LONG):
        s = slips.pick(w, rnd)
        if s: return s[0].upper() + s[1:] if word[:1].isupper() else s
    return t

if __name__ == '__main__':
    mode, model, lexicon = sys.argv[1:4]
    m, pos = load(model, sys.argv[6] if len(sys.argv) > 6 else None)
    slips = Slips(lexicon); rnd = random.Random(17)
    with open(sys.argv[5], 'w', encoding='utf-8') as out:
        if mode == 'pairs':
            for line in open(sys.argv[4], encoding='utf-8'):
                s, i, ctx, typed, want = line.rstrip('\n').split('\t')
                out.write(f'{s}\t{i}\t{ctx}\t{typo2(want, m, pos, slips, rnd)}\t{want}\n')
        else:
            WORD = re.compile(r'([^A-Za-zÀ-ÿ]*)([A-Za-zÀ-ÿ]+)([^A-Za-zÀ-ÿ]*)')
            for line in open(sys.argv[4], encoding='utf-8'):
                ws = []
                for tok in line.split():
                    mm = WORD.fullmatch(tok)
                    ws.append(mm.group(1) + typo2(mm.group(2), m, pos, slips, rnd) + mm.group(3) if mm else tok)
                out.write(' '.join(ws) + '\n')
