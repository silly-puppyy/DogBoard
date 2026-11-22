"""Tokenizer A/B for the student (private study, GPU): measures that do not depend on the tokenizer, so T0 (pilot-caps),
T1 / T2 (pilot-clitics-*, clitics.py) compare directly.
  bits per character  on the held-out pilot lines (sum of token losses / characters of the original text)
  disambiguation      from held-out text: each occurrence of a confusable word (its / it's, your / you're, there /
                      their / they're, whose / who's, lets / let's, were / we're / where, and plural / possessive:
                      cats / cat's / cats' for stems in the lexicon), scored against its rivals as whole words
                      (pieces + a word boundary next) from the left context (what the keyboard sees while typing)
                      and with the next word too (what the look-back sees). Reports accuracy, the mean margin over
                      the best rival (nats: how decisive) and confident mistakes (wrong by more than ln 4).
Logs to wandb (dogboard-llm, the run's own name + "-eval") and prints one line per set.
Usage: eval_tok_ab.py CKPT [lexicon.tsv]"""
import os, re, sys, math, json, random, collections, torch, sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from student import Config, Student
import caps

torch.set_grad_enabled(False)
CK = sys.argv[1]
LEX = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../app/src/main/assets/dogboard/en.lexicon.tsv')
s = torch.load(CK, map_location='cuda')
net = Student(Config(**s['config'])).cuda().eval(); net.load_state_dict(s['model'])
TOKDIR = s['tokdir']
m = re.search(r'clitics-(T\d)$', TOKDIR)
VARIANT = m.group(1) if m else 'T0'
if VARIANT == 'T0':
    sp = spm.SentencePieceProcessor(model_file=os.path.join(TOKDIR, 'sp16k.model'))
    def encode(text): return caps.encode(sp, text)
else:
    os.environ['CLITIC_VARIANT'] = VARIANT
    import tok_prep_clitics as tp
    sp, encode = tp.sp, tp.encode
V = sp.vocab_size()
starts = caps.word_starts(sp)
boundary = torch.tensor([i for i in range(V) if starts[i]] + [2], device='cuda')  # a new word, or the end
VAL = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/pilot/val.txt'
lines = [l.rstrip('\n') for l in open(VAL, encoding='utf-8') if l.strip()]


def logits(seqs):
    T = max(map(len, seqs)); ids = torch.zeros(len(seqs), T, dtype=torch.long, device='cuda')
    for i, q in enumerate(seqs): ids[i, :len(q)] = torch.tensor(q[:T])
    return torch.log_softmax(net(ids).float(), -1)


# bits per character
random.seed(1)
sample = random.sample(lines, 3000)
nll, chars = 0.0, 0
for b in range(0, len(sample), 32):
    batch = [l[:600] for l in sample[b:b + 32]]
    seqs = [[1] + encode(l) for l in batch]
    seqs = [q[:256] for q in seqs]
    out = logits(seqs)
    for i, q in enumerate(seqs):
        if len(q) < 2: continue
        lp = out[i, torch.arange(len(q) - 1, device='cuda'), torch.tensor(q[1:], device='cuda')]
        nll -= float(lp.sum())
        # characters covered: whole lines unless cut at 256 tokens (rare: estimated by the share of tokens kept)
        full = len(encode(batch[i])) + 1
        chars += len(batch[i]) * (len(q) - 1) / (full - 1)
bpc = nll / chars / math.log(2)

# disambiguation sets
lex = {}
for l in open(LEX, encoding='utf-8'):
    if not l.startswith('#'):
        w, f = l.rstrip('\n').split('\t')[:2]; lex.setdefault(w.lower(), int(f))
SETS = {'its': ["its", "it's"], 'your': ["your", "you're"], 'there': ["there", "their", "they're"],
        'whose': ["whose", "who's"], 'lets': ["lets", "let's"], 'were': ["were", "we're", "where"]}
member = {w: k for k, ws in SETS.items() for w in ws}
WORD = re.compile(r"[A-Za-z]+(?:['’][A-Za-z]+)*'?")


def plural_forms(w):
    """(stem forms) for plural / possessive: cats / cat's / cats' when the stem and its s-plural are in the lexicon."""
    lw = w.lower().replace('’', "'")
    for stem in (lw[:-2] if lw.endswith("'s") else None, lw[:-2] if lw.endswith("s'") else None, lw[:-1] if lw.endswith('s') else None):
        if stem and len(stem) >= 3 and stem in lex and stem + 's' in lex and lex[stem + 's'] >= 1000:
            return [stem + 's', stem + "'s", stem + "s'"]
    return None


cases = collections.defaultdict(list)
for li, l in enumerate(lines):
    ms = list(WORD.finditer(l))
    for j, mt in enumerate(ms):
        w = mt.group(0).replace('’', "'")
        lw = w.lower()
        key, forms = (member[lw], SETS[member[lw]]) if lw in member else ('plural', plural_forms(w))
        if not forms or lw not in forms: continue
        nxt = ms[j + 1].group(0) if j + 1 < len(ms) and l[mt.end():ms[j + 1].start()] == ' ' else None
        cases[key].append((l[:mt.start()].rstrip(), w, forms, nxt))
random.seed(2)
for k in cases:
    if k == 'plural':  # balanced by form: plurals would swamp the possessives
        by = collections.defaultdict(list)
        for c in cases[k]: by[c[2].index(c[1].lower())].append(c)
        cases[k] = [c for f in by for c in random.sample(by[f], min(300, len(by[f])))]
    else:
        cases[k] = random.sample(cases[k], min(600, len(cases[k])))


def word_scores(ctx, cands, nxt):
    """ln P(each candidate as a whole word) after ctx: its pieces, then a word boundary (or the next word)."""
    sep = ' ' if ctx else ''
    base = [1] + (encode(ctx) if ctx else [])
    seqs, spans = [], []
    for c in cands:
        full = [1] + encode(ctx + sep + c + (' ' + nxt if nxt else ''))
        word = [1] + encode(ctx + sep + c)
        assert full[:len(word)] == word and word[:len(base)] == base, (ctx, c)
        seqs.append(full); spans.append((len(base), len(word), len(full)))
    out = logits(seqs)
    res = []
    for i, (a, b, e) in enumerate(spans):
        q = seqs[i]
        lp = sum(float(out[i, j - 1, q[j]]) for j in range(a, b))
        if nxt: lp += sum(float(out[i, j - 1, q[j]]) for j in range(b, e))
        else: lp += float(torch.logsumexp(out[i, b - 1, boundary], 0))
        res.append(lp)
    return res


results = {}
for k, cs in sorted(cases.items()):
    for mode in ('left', 'next'):
        acc = margin = confident_wrong = n = 0
        for ctx, w, forms, nxt in cs:
            if mode == 'next' and not nxt: continue
            cap = w[0].isupper()
            cands = [f[0].upper() + f[1:] if cap else f for f in forms]
            sc = word_scores(ctx, cands, nxt if mode == 'next' else None)
            right = forms.index(w.lower())
            mg = sc[right] - max(x for i, x in enumerate(sc) if i != right)
            n += 1; acc += mg > 0; margin += mg; confident_wrong += mg < -math.log(4)
        if n:
            results[f'{k}/{mode}'] = dict(n=n, acc=acc / n, margin=margin / n, confident_wrong=confident_wrong / n)
            print(f'{VARIANT} {k:7s} {mode:4s} n {n:4d}  acc {100 * acc / n:5.1f} %  margin {margin / n:+.2f}  confidently wrong {100 * confident_wrong / n:4.1f} %', flush=True)
print(f'{VARIANT} bits/char {bpc:.4f}', flush=True)

import track
run = os.path.basename(CK).replace('.pt', '')
wb = track.start('llm', 'en', run + '-eval', data='pilot', config=dict(variant=VARIANT, ckpt=CK, step=s.get('step')), tags=('tokenizer-ab',))
wb.log({'eval/bpc': bpc, **{f'disambig/{k}/{m}': v for k, r in results.items() for m, v in r.items()}})
wb.finish()
json.dump(dict(variant=VARIANT, bpc=bpc, results=results), open(CK.replace('.pt', '') + '.tokab.json', 'w'), indent=1)
