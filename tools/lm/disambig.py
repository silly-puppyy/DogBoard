"""Can the student tell a real-word slip from the intended word? (private study, GPU) From the Aalto pairs:
real-word slips (typed is a lexicon word != intended) vs the same confusion pairs typed correctly.
For each: ln P(intended) vs ln P(typed) with the left context, and with the next word too (as
retrocorrection sees it); baseline: word frequency. Correction fires when the rival beats the typed
word by > margin nats. Reports slips fixed and correct words broken per margin.
Usage: disambig.py CKPT pairs.tsv lexicon.tsv"""
import os, sys, math, random, collections, torch, sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from student import Config, Student
import caps
torch.set_grad_enabled(False)
s = torch.load(sys.argv[1], map_location='cuda')
net = Student(Config(**s['config'])).cuda().eval(); net.load_state_dict(s['model'])
sp = spm.SentencePieceProcessor(model_file=os.path.join(s['tokdir'], 'sp16k.model'))
enc = (lambda t: caps.encode(sp, t)) if s.get('caps') else (lambda t: sp.encode(t))
freq = {}
for l in open(sys.argv[3], encoding='utf-8'):
    if not l.startswith('#'):
        w, f = l.rstrip('\n').split('\t')[:2]; freq.setdefault(w.lower(), int(f))

def lp(ctx, conts):
    c = [1] + (enc(ctx) if ctx.strip() else [])
    seqs = [c + enc(x) for x in conts]
    T = max(map(len, seqs)); ids = torch.zeros(len(seqs), T, dtype=torch.long, device='cuda')
    for i, q in enumerate(seqs): ids[i, :len(q)] = torch.tensor(q)
    out = torch.log_softmax(net(ids).float(), -1)
    return [sum(float(out[i, j - 1, q[j]]) for j in range(len(c), len(q))) for i, q in enumerate(seqs)]

rows = [l.rstrip('\n').split('\t') for l in open(sys.argv[2], encoding='utf-8')]
sent = collections.defaultdict(dict)
for sid, i, ctx, typed, want in rows: sent[sid][int(i)] = want
slips, conf = [], collections.Counter()
for sid, i, ctx, typed, want in rows:
    t, w = typed.lower(), want.lower()
    if t != w and t in freq and t.isalpha() and w.isalpha():
        slips.append((sid, int(i), ctx, typed, want)); conf[(t, w)] += 1
pairs = {k for k, v in conf.items() if v >= 3}           # confusions seen at least 3 times
rival = collections.defaultdict(list)
for t, w in pairs: rival[t].append(w)                    # a correctly typed t could be "corrected" to w
random.seed(3)
slips = [x for x in slips if (x[3].lower(), x[4].lower()) in pairs]
slips = random.sample(slips, min(3000, len(slips)))
clean = [(sid, int(i), ctx, typed, random.choice(rival[typed.lower()])) for sid, i, ctx, typed, want in rows
         if typed == want and typed.lower() in rival]
clean = random.sample(clean, min(3000, len(clean)))
def nxt(sid, i): return sent[sid].get(i + 1)
def margins(cases, typed_is_wrong):
    out = []
    for sid, i, ctx, typed, other in cases:
        # slips: typed (wrong) vs intended; clean: typed (right) vs rival; margin = other - typed
        cand = other if not typed_is_wrong else other
        a, b = typed.lower(), cand.lower()
        if typed[:1].isupper(): b = b[:1].upper() + b[1:]
        sep = ' ' if ctx.strip() else ''
        left = lp(ctx, [sep + typed, sep + b]); n = nxt(sid, i)
        both = lp(ctx, [f'{sep}{typed} {n}', f'{sep}{b} {n}']) if n else left
        f = math.log(freq.get(b.lower(), 1)) - math.log(freq.get(a, 1))
        out.append((left[1] - left[0], both[1] - both[0], f))
    return out
S = margins([(a, b, c, d, e) for a, b, c, d, e in slips], True)
C = margins(clean, False)
print(f'{len(S)} real-word slips ({len(pairs)} confusion pairs seen >= 3x), {len(C)} correct words with such a rival')
for k, name in ((2, 'frequency'), (0, 'v3 left context'), (1, 'v3 + next word')):
    print(name + ':', '  '.join(f'margin {m}: fixed {100*sum(x[k] > m for x in S)/len(S):.0f}% broken {100*sum(x[k] > m for x in C)/len(C):.1f}%' for m in (0, 2, 4, 6)))
