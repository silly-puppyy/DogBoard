#!/usr/bin/env python3
"""Keeps a machine-translated sentence only if it survives the way back: translated back into English (OPUS-MT
de/es/fr -> en) it must match the original closely (difflib ratio >= MIN), and quoted examples must keep their
curly quotes. Rewrites MT_DIR/<lang>.json in place (the dropped ones show in English).   ui_backcheck.py MT_DIR"""
import difflib, json, os, re, sys
import torch
from transformers import MarianMTModel, MarianTokenizer
MIN = 0.72
BACK = {'de': 'Helsinki-NLP/opus-mt-de-en', 'es': 'Helsinki-NLP/opus-mt-es-en', 'fr': 'Helsinki-NLP/opus-mt-fr-en'}
dev = 'cuda' if torch.cuda.is_available() else 'cpu'
norm = lambda s: re.sub(r'[^\w ]', '', s.lower())
for lang, name in BACK.items():
    p = os.path.join(sys.argv[1], f'{lang}.json'); mt = json.load(open(p, encoding='utf-8'))
    items = [(en, t) for en, t in mt.items() if len(en.split()) > 4]
    tok = MarianTokenizer.from_pretrained(name); model = MarianMTModel.from_pretrained(name).to(dev).eval()
    keep = {en: t for en, t in mt.items() if len(en.split()) <= 4}
    dropped = 0
    for i in range(0, len(items), 32):
        batch = items[i:i + 32]
        enc = tok([t for _, t in batch], return_tensors='pt', padding=True, truncation=True, max_length=256).to(dev)
        with torch.no_grad(): gen = model.generate(**enc, num_beams=4, max_new_tokens=256)
        for (en, t), back in zip(batch, tok.batch_decode(gen, skip_special_tokens=True)):
            ok = difflib.SequenceMatcher(None, norm(en), norm(back)).ratio() >= MIN and ('“' not in en or ('“' in t or '«' in t or '„' in t))
            if ok: keep[en] = t
            else: dropped += 1
    json.dump(keep, open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=0, sort_keys=True)
    print(lang, len(items) - dropped, 'sentences kept,', dropped, 'dropped')
