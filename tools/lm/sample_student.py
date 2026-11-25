"""Text sampled from a student checkpoint, to look at what a model writes (the bilingual recipes, 2026-11-25).
Usage: sample_student.py CKPT "prompt" [n_tokens] [n_samples]; PREFIX_TOKEN=<en> puts a language token after BOS
(recipe 3). Temperature 0.8, top-k 40, seed 0: the same prompt gives the same text."""
import os, sys, torch
import sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from student import Config, Student
import caps as capsmod

P = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/pilot/'
ckpt, prompt = sys.argv[1], sys.argv[2]
n_tok = int(sys.argv[3]) if len(sys.argv) > 3 else 30
n_samples = int(sys.argv[4]) if len(sys.argv) > 4 else 2
DEV = os.environ.get('DEVICE', 'cuda')
s = torch.load(ckpt, map_location=DEV)
sp = spm.SentencePieceProcessor(model_file=os.path.join(s.get('tokdir', P), 'sp16k.model'))
CAPS = s.get('caps', False)
model = Student(Config(**s['config'])).to(DEV).eval(); model.load_state_dict(s['model'])
CTX = Config(**s['config']).ctx
pre = os.environ.get('PREFIX_TOKEN')
start = [1] + ([sp.piece_to_id(pre)] if pre else []) + (capsmod.encode(sp, prompt) if CAPS else sp.encode(prompt))
torch.manual_seed(0)


def decode(ids):
    # CAP ids (caps.py) capitalise the next piece's first letter
    out, cap = [], False
    for i in ids:
        if CAPS and i == capsmod.CAP: cap = True; continue
        p = sp.id_to_piece(i)
        if cap:
            lead = 1 if p.startswith('▁') else 0
            p = p[:lead] + p[lead:lead + 1].upper() + p[lead + 1:]; cap = False
        out.append(p)
    return ''.join(out).replace('▁', ' ').strip()


with torch.no_grad():
    for _ in range(n_samples):
        ids = list(start)
        for _ in range(n_tok):
            z = model(torch.tensor([ids[-CTX:]], device=DEV))[0, -1].float() / 0.8
            v, ix = torch.topk(z, 40)
            nxt = int(ix[torch.multinomial(torch.softmax(v, -1), 1)])
            if nxt in (1, 2): break  # BOS / EOS
            ids.append(nxt)
        print(decode(ids[len(start) - len(sp.encode(prompt)) if not CAPS else 1 + (1 if pre else 0):]))
