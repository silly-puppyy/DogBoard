"""Next-word benchmark for our student checkpoints (private study): same protocol and TSV as
hf_nwp.py. Standard SentencePiece: a new word starts with a '▁' piece, so the context is BOS + the
text (no trailing space) and a word is a '▁'-letter piece completed greedily by letter-only pieces.
DEVICE=cpu QUANT=int8 runs the int8 dynamic-quantised model on the CPU (post-training quantisation check).
Usage: student_nwp.py CKPT text.txt n_sentences out.tsv

student_nwp_lang.py (2026-11-24): the same with words of any alphabet (accented Latin, Cyrillic), as hf_nwp_lang.py
is to hf_nwp.py, for the per-language students on make_lang_predbench.py sets."""
import os, sys, re, time, torch
import sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from student import Config, Student

P = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/pilot/'
ckpt, text, n_sent, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
DEV = os.environ.get('DEVICE', 'cuda')
s = torch.load(ckpt, map_location=DEV)
sp = spm.SentencePieceProcessor(model_file=os.path.join(s.get('tokdir', P), 'sp16k.model'))
# CAP models (caps.py): context lowercased with a CAP id before capitalised words; a word's
# probability = its lowercase path + CAP followed by it (the benchmark ignores case)
CAPS = s.get('caps', False)
import caps as capsmod
model = Student(Config(**s['config'])).to(DEV).eval(); model.load_state_dict(s['model'])
if os.environ.get('QUANT') == 'int8':
    torch.set_num_threads(2)
    model = torch.ao.quantization.quantize_dynamic(model, {torch.nn.Linear}, dtype=torch.qint8)
pieces = [sp.id_to_piece(i) for i in range(sp.vocab_size())]
starts = torch.tensor([bool(re.match(r"^▁[^\W\d_]", p)) for p in pieces], device=DEV)
letter_cont = torch.tensor([bool(re.match(r"^[^\W\d_'’]+$|^[^\W\d_][^\W\d_'’]*$", p)) for p in pieces], device=DEV)
WORD = re.compile(r"[^\W\d_][^\W\d_'’]*")
CTX = Config(**s['config']).ctx

@torch.no_grad()
def logits(ids):
    return model(torch.tensor([ids[-CTX:]], device=DEV))[0, -1].float()

@torch.no_grad()
def next_words(ids, k=3, first=8, max_pieces=5):
    p = torch.softmax(logits(ids), -1)
    p_plain = torch.where(starts, p, torch.zeros_like(p))
    p_cap = torch.zeros_like(p)
    if CAPS:
        q = torch.softmax(logits(ids + [capsmod.CAP]), -1)
        p_cap = float(p[capsmod.CAP]) * torch.where(starts, q, torch.zeros_like(q))
    cand = {}
    for t in torch.topk(p_plain + p_cap, first).indices.tolist():
        lead = [capsmod.CAP] if float(p_cap[t]) > float(p_plain[t]) else []  # continue on the likelier path
        seq, prob = [t], float(p_plain[t] + p_cap[t])
        for _ in range(max_pieces):
            q = torch.softmax(logits(ids + lead + seq), -1); j = int(q.argmax())
            if not bool(letter_cont[j]): break  # the word ends here
            seq.append(j); prob *= float(q[j])
        w = ''.join(pieces[i] for i in seq).replace('▁', ' ').strip()
        cand[w] = cand.get(w, 0.0) + prob
    return [w for w, _ in sorted(cand.items(), key=lambda x: -x[1])[:k]]

t0 = time.time(); rows = 0
with open(out, 'w') as f:
    for si, line in enumerate(open(text, encoding='utf-8')):
        if si >= n_sent: break
        words = line.split()
        for i in range(1, len(words)):
            target = words[i].strip('.,!?;:"()\'¡¿«»')
            if not WORD.fullmatch(target): continue
            ctx = ' '.join(words[:i])
            ids = [1] + (capsmod.encode(sp, ctx) if CAPS else sp.encode(ctx))
            f.write(f"{si}\t{i}\t{target}\t{'|'.join(next_words(ids))}\n"); rows += 1
print(f'student {ckpt}: {rows} predictions in {time.time()-t0:.0f} s', file=sys.stderr)
