"""Would the neural model help autocorrect? (private study, GPU) Rescores the decoder's top candidates
from RealTypoEvalTest's dump (DOGBOARD_DUMP_CANDIDATES: context, typed, intended, outcome,
candidates word,score,geometry,lm|...): each candidate's language part becomes
(1 - a) * trigram + a * neural, the neural part = ln P(word | the true words before), from a student
checkpoint (CAP-aware). Proxy metric: the share of cases whose top candidate is the intended word,
for typos and for correctly typed words, per a. (The keyboard's keep-the-typed-word rules are not
modelled: this measures the ranking the rules start from.)
Usage: rescore_eval.py CKPT dump.tsv"""
import os, sys, math, torch, sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from student import Config, Student
import caps
torch.set_grad_enabled(False)
ckpt, dump = sys.argv[1], sys.argv[2]
s = torch.load(ckpt, map_location='cuda')
net = Student(Config(**s['config'])).cuda().eval(); net.load_state_dict(s['model'])
sp = spm.SentencePieceProcessor(model_file=os.path.join(s['tokdir'], 'sp16k.model'))
enc = (lambda t: caps.encode(sp, t)) if s.get('caps') else (lambda t: sp.encode(t))
W = 0.24  # the decoder's language power (score = geometry + W * language)

def neural_lp(context, words):
    """ln P(word | context) for each candidate word, one batched forward."""
    ctx = [1] + (enc(context) if context.strip() else [])
    seqs = [ctx + enc(w) for w in words]
    T = max(len(q) for q in seqs)
    ids = torch.zeros(len(seqs), T, dtype=torch.long, device='cuda')
    for i, q in enumerate(seqs): ids[i, :len(q)] = torch.tensor(q)
    lp = torch.log_softmax(net(ids).float(), -1)
    out = []
    for i, q in enumerate(seqs):
        out.append(sum(float(lp[i, j - 1, q[j]]) for j in range(len(ctx), len(q))))
    return out

cases = []
for line in open(dump, encoding='utf-8'):
    ctx, typed, intended, outcome, cands = line.rstrip('\n').split('\t')
    cs = [c.rsplit(',', 3) for c in cands.split('|') if c]
    words = [c[0] for c in cs]; geo = [float(c[2]) for c in cs]; tri = [float(c[3]) for c in cs]
    cases.append((typed.lower() != intended.lower(), intended.lower(), words, geo, tri, neural_lp(ctx, words)))
print(f'{len(cases)} cases ({sum(c[0] for c in cases)} typos)')
for w in (W, 0.32, 0.40, 0.50):  # the language power (the decoder's 0.24 was tuned for the trigram)
    for a in (0.0, 0.25, 0.5, 0.75, 1.0):
        pass  # a = 0 at every w: the control (is it the weight or the model?)
        right = {True: 0, False: 0}; n = {True: 0, False: 0}
        for typo, want, words, geo, tri, neu in cases:
            best = max(range(len(words)), key=lambda i: geo[i] + w * ((1 - a) * tri[i] + a * neu[i]))
            right[typo] += words[best].lower() == want; n[typo] += 1
        print(f'  w={w:.2f} a={a:.2f}: typos -> intended on top {100 * right[True] / n[True]:5.1f} % | correct words kept on top {100 * right[False] / n[False]:5.1f} %')
