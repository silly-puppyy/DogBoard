#!/usr/bin/env python3
"""Scores lang_quality.py's greedy continuations (tools/lm/lang_judge.py): Qwen3-4B (multilingual, instruction-tuned,
thinking off) rates each continuation 1-5 for coherence (does it follow on from the beginning and make sense) and for
grammar (correct in that language), the same instructions for every language. Writes OUT/<lang>.judged.jsonl and adds
the means to OUT/<lang>.scores.json.

    lang_judge.py OUT [langs]
"""
import glob, json, os, re, sys
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

JUDGE = "Qwen/Qwen3-4B"
NAMES = {"en": "English", "es": "Spanish", "fr": "French", "de": "German", "it": "Italian", "pt": "Portuguese",
         "ru": "Russian", "fi": "Finnish"}
ASK = ("You rate text written by a small {lang} language model. It was given the beginning of a sentence and wrote the rest.\n"
       "Beginning: {prompt}\nContinuation: {cont}\n\n"
       "Rate the continuation from 1 (bad) to 5 (good) on two things:\n"
       "coherence: it follows on from the beginning and makes sense (repeating itself or drifting off topic is bad);\n"
       "grammar: it is correct {lang} (agreement, word forms, word order), whatever it says.\n"
       'Answer with JSON only: {{"coherence": n, "grammar": n}}')


def main():
    out = sys.argv[1]
    langs = sys.argv[2].split(",") if len(sys.argv) > 2 else [os.path.basename(p).split(".")[0] for p in sorted(glob.glob(f"{out}/*.gen.jsonl"))]
    tok = AutoTokenizer.from_pretrained(JUDGE)
    model = AutoModelForCausalLM.from_pretrained(JUDGE, torch_dtype=torch.bfloat16, device_map="cuda", low_cpu_mem_usage=True).eval()
    for lang in langs:
        rows = [json.loads(l) for l in open(f"{out}/{lang}.gen.jsonl", encoding="utf-8")]
        coh, gra = [], []
        with open(f"{out}/{lang}.judged.jsonl", "w", encoding="utf-8") as f:
            for r in rows:
                msg = [{"role": "user", "content": ASK.format(lang=NAMES[lang], prompt=r["prompt"], cont=r["cont"] or "(nothing)")}]
                ids = tok.apply_chat_template(msg, add_generation_prompt=True, enable_thinking=False, return_tensors="pt").to("cuda")
                with torch.no_grad():
                    o = model.generate(ids, max_new_tokens=30, do_sample=False)
                text = tok.decode(o[0, ids.shape[1]:], skip_special_tokens=True)
                m = re.search(r'"coherence"\s*:\s*(\d).*?"grammar"\s*:\s*(\d)', text, re.S)
                if m:
                    r["coherence"], r["grammar"] = int(m.group(1)), int(m.group(2))
                    coh.append(r["coherence"]); gra.append(r["grammar"])
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        p = f"{out}/{lang}.scores.json"
        s = json.load(open(p)) if os.path.exists(p) else {"lang": lang}
        s.update(coherence=round(sum(coh) / max(1, len(coh)), 2), grammar=round(sum(gra) / max(1, len(gra)), 2), judged=len(coh))
        json.dump(s, open(p, "w"))
        print(s, flush=True)


if __name__ == "__main__":
    main()
