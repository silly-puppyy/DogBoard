"""Next-word benchmark for Hugging Face causal LMs (private study): for every word position, the
top-3 next whole words given the words before it, the same way as apple_nwp.py: the most likely
first tokens that start a new word, each completed greedily to the end of the word, probabilities
merged per word. Output TSV: sentence, position, target, top1|top2|top3 (score.py compares).
Usage: hf_nwp.py MODEL_ID text.txt n_sentences out.tsv

hf_nwp_lang.py (2026-11-22): the same with words of any alphabet (accented Latin, Cyrillic: the English version
matched A-Z only), for the per-language teacher bake-off (make_lang_predbench.py sets)."""
import sys, re, time, torch
from transformers import AutoModelForCausalLM, AutoTokenizer

model_id, text, n_sent, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
tok = AutoTokenizer.from_pretrained(model_id)
import os
if os.environ.get('QUANT4') == '1':  # the ceiling model (Qwen3-8B) only fits the 16 GB GPU in 4-bit
    from transformers import BitsAndBytesConfig
    model = AutoModelForCausalLM.from_pretrained(model_id, device_map='cuda', quantization_config=BitsAndBytesConfig(
        load_in_4bit=True, bnb_4bit_quant_type='nf4', bnb_4bit_compute_dtype=torch.bfloat16)).eval()
else:
    model = AutoModelForCausalLM.from_pretrained(model_id, dtype=torch.bfloat16).cuda().eval()
WORD = re.compile(r"[^\W\d_][^\W\d_'’]*")
vocab = tok.convert_ids_to_tokens(list(range(len(tok))))
# a token starts a new word if it decodes with a leading space and a letter after it
starts = torch.tensor([bool(re.match(r"^ [^\W\d_]", tok.convert_tokens_to_string([t]))) for t in vocab], device='cuda')
letter_cont = torch.tensor([bool(re.match(r"^[^\W\d_'’]+$", tok.convert_tokens_to_string([t]))) for t in vocab], device='cuda')
# the output layer can be padded past the tokenizer (Qwen3: 151,936 vs 151,669): padding never starts a word
pad = model.get_output_embeddings().weight.shape[0] - len(vocab)
if pad > 0:
    starts = torch.cat([starts, torch.zeros(pad, dtype=torch.bool, device='cuda')])
    letter_cont = torch.cat([letter_cont, torch.zeros(pad, dtype=torch.bool, device='cuda')])

@torch.no_grad()
def next_words(ids, k=3, first=8, max_pieces=5):
    logits = model(torch.tensor([ids], device='cuda')).logits[0, -1].float()
    p = torch.softmax(logits, -1)
    p_start = torch.where(starts, p, torch.zeros_like(p))
    cand = {}
    for t in torch.topk(p_start, first).indices.tolist():
        seq, prob = [t], float(p[t])
        for _ in range(max_pieces):
            l2 = model(torch.tensor([ids + seq], device='cuda')).logits[0, -1].float()
            q = torch.softmax(l2, -1)
            j = int(q.argmax())
            if not bool(letter_cont[j]): break  # the word ends here
            seq.append(j); prob *= float(q[j])
        w = tok.decode(seq).strip()
        cand[w] = cand.get(w, 0.0) + prob
    return [w for w, _ in sorted(cand.items(), key=lambda x: -x[1])[:k]]

t0 = time.time(); rows = 0
with open(out, 'w') as f:
    for si, line in enumerate(open(text, encoding='utf-8')):
        if si >= n_sent: break
        words = line.split()
        for i in range(1, len(words)):
            target = words[i].strip('.,!?;:"()\'')
            if not WORD.fullmatch(target): continue
            ids = tok(' '.join(words[:i]), add_special_tokens=False).input_ids
            if tok.bos_token_id is not None: ids = [tok.bos_token_id] + ids
            f.write(f"{si}\t{i}\t{target}\t{'|'.join(next_words(ids))}\n"); rows += 1
print(f'{model_id}: {rows} predictions in {time.time()-t0:.0f} s', file=sys.stderr)
