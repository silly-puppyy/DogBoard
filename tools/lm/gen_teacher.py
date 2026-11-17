"""Sequence-level distillation data (private study, GPU): the teacher (a Qwen3 base model) continues
the opening words of pilot training lines, sampled at temperature 0.8 / top-p 0.95, one short
continuation per prompt (a message's worth: up to MAX_NEW tokens, stopped at a newline). The student
then trains on teacher text mixed with real text (tokenisers differ, so no logit matching here).
Resumable: appends to the output and skips the prompts already done (line index in the first column).
Usage: gen_teacher.py MODEL_ID n_prompts out.tsv [batch]"""
import os, sys, random, time, torch
from transformers import AutoModelForCausalLM, AutoTokenizer

P = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/pilot/train.txt'
model_id, n_prompts, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
BATCH = int(sys.argv[4]) if len(sys.argv) > 4 else 96
MAX_NEW = 40
tok = AutoTokenizer.from_pretrained(model_id); tok.padding_side = 'left'
if tok.pad_token is None: tok.pad_token = tok.eos_token
model = AutoModelForCausalLM.from_pretrained(model_id, dtype=torch.bfloat16).cuda().eval()
nl = [i for t, i in tok.get_vocab().items() if '\n' in tok.convert_tokens_to_string([t])]

# prompts: the first 1-6 words of random lines with at least 4 words (fixed seed: resumable)
rnd = random.Random(11); lines = [l.split()[:7] for l in open(P, encoding='utf-8')]  # only the openings: RAM
picks = [i for i in rnd.sample(range(len(lines)), min(len(lines), n_prompts * 2)) if len(lines[i]) >= 4][:n_prompts]
done = set()
if os.path.exists(out):
    done = {int(l.split('\t', 1)[0]) for l in open(out, encoding='utf-8') if '\t' in l}
todo = [i for i in picks if i not in done]
print(f'{len(done)} done, {len(todo)} to go', flush=True)
t0 = time.time(); n_tok = 0
with open(out, 'a', encoding='utf-8') as f, torch.no_grad():
    for b in range(0, len(todo), BATCH):
        idx = todo[b:b + BATCH]
        prompts = [' '.join(lines[i][:rnd.randint(1, min(6, len(lines[i]) - 1))]) for i in idx]
        enc = tok(prompts, return_tensors='pt', padding=True).to('cuda')
        gen = model.generate(**enc, do_sample=True, temperature=0.8, top_p=0.95, max_new_tokens=MAX_NEW,
                             eos_token_id=nl + [tok.eos_token_id], pad_token_id=tok.pad_token_id)
        new = gen[:, enc.input_ids.shape[1]:]; n_tok += int((new != tok.pad_token_id).sum())
        for i, p, g in zip(idx, prompts, tok.batch_decode(new, skip_special_tokens=True)):
            text = (p + g).split('\n')[0].strip().replace('\t', ' ')
            f.write(f'{i}\t{text}\n')
        f.flush()
        if (b // BATCH) % 20 == 0: print(f'{b + len(idx)} prompts, {n_tok / (time.time() - t0):.0f} tok/s', flush=True)
