"""Does the student (1) write typos, (2) recite its training text? (private study, GPU)
(1) samples continuations of held-out chat openings (temperature 0.8) and counts words not in the
lexicon, next to the same rate in the real training text and held-out text; (2) feeds 300 training
lines' first 12 tokens and greedily writes 24: how often the true continuation comes back verbatim
(>= 16 tokens), next to the same test on held-out lines (what any language model gets for
formulaic text). Usage: memo_typo_check.py CKPT train_lines.txt heldout_lines.txt lexicon.tsv"""
import os, sys, re, random, torch, sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from student import Config, Student
import caps
torch.set_grad_enabled(False)
s = torch.load(sys.argv[1], map_location='cuda')
net = Student(Config(**s['config'])).cuda().eval(); net.load_state_dict(s['model'])
sp = spm.SentencePieceProcessor(model_file=os.path.join(s['tokdir'], 'sp16k.model'))
enc = lambda t: caps.encode(sp, t)
def dec(ids):
    out, up = [], False
    for i in ids:
        if i == caps.CAP: up = True; continue
        p = sp.id_to_piece(i)
        if up: p = p.replace('\u2581', '\u2581\x00', 1) if p.startswith('\u2581') else '\x00' + p; up = False
        out.append(p)
    t = ''.join(out).replace('\u2581', ' ')
    return re.sub('\x00(.)', lambda m: m.group(1).upper(), t).strip()
lex = set(l.split('\t')[0].lower() for l in open(sys.argv[4], encoding='utf-8') if not l.startswith('#'))
WORD = re.compile(r"[A-Za-z]+(?:'[a-z]+)?")
def nonword_rate(texts):
    ws = [w.lower() for t in texts for w in WORD.findall(t)]
    return 100 * sum(w not in lex for w in ws) / max(1, len(ws)), len(ws)
def lines(path, n):
    L = [l.strip() for l in open(path, encoding='utf-8', errors='replace') if len(l.split()) >= 12]
    random.seed(7); return random.sample(L, min(n, len(L)))
def generate(ids, n, temp):
    ids = list(ids)
    for _ in range(n):
        x = torch.tensor([[1] + ids[-200:]], device='cuda')
        logits = net(x)[0, -1].float()
        nxt = int(torch.argmax(logits)) if temp == 0 else int(torch.multinomial(torch.softmax(logits / temp, -1), 1))
        if nxt in (0, 1, 2): break
        ids.append(nxt)
    return ids
train, held = lines(sys.argv[2], 300), lines(sys.argv[3], 300)
samples = []
for t in held[:150]:
    p = enc(' '.join(t.split()[:6])); samples.append(dec(generate(p, 40, 0.8)[len(p):]))
print('non-words (not in the lexicon) per 100 words: model samples %.2f (%d words), training text %.2f, held-out text %.2f'
      % (*nonword_rate(samples), nonword_rate(train)[0], nonword_rate(held)[0]))
print('   samples:', ' || '.join(random.sample(samples, 4)))
def verbatim(L):
    hit = 0
    for t in L:
        ids = enc(t)
        if len(ids) < 40: continue
        g = generate(ids[:12], 24, 0)[12:]
        hit += g[:16] == ids[12:28]
    return 100 * hit / len(L)
print('verbatim recall (16+ tokens after a 12-token prefix): training lines %.1f %%, held-out lines %.1f %%' % (verbatim(train), verbatim(held)))
