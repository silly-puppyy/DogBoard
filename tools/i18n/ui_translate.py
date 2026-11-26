#!/usr/bin/env python3
"""Translates DogBoard's UI strings (tools/i18n/ui_strings.py) into app/src/main/assets/i18n/<lang>.json with the
OPUS-MT models (Helsinki-NLP, CC BY 4.0), on the GPU. A translation that drops a name (DogBoard, iPhone, QWERTY...)
or comes back empty is left out, so the English shows (UiText falls back to it).

    HF_HOME=<data drive>/hf ui_translate.py ui-strings.txt de es fr pt ru"""
import json, os, re, sys
import torch
from transformers import MarianMTModel, MarianTokenizer

MODELS = {'de': 'Helsinki-NLP/opus-mt-en-de', 'es': 'Helsinki-NLP/opus-mt-en-es', 'fr': 'Helsinki-NLP/opus-mt-en-fr',
          'pt': 'Helsinki-NLP/opus-mt-tc-big-en-pt', 'ru': 'Helsinki-NLP/opus-mt-en-ru'}
# opus-mt-tc-big models pick their target by a token
PREFIX = {'pt': '>>por<< '}
NAMES = ['DogBoard', 'iPhone', 'Android', 'QWERTY', 'QWERTZ', 'AZERTY', 'Gboard', 'GitHub', 'F-Droid', 'Obtainium', 'TikTok',
         'YouTube', 'FlorisBoard', 'Apache', 'GIF', 'Wi-Fi', 'Unsplash', 'OPUS']
OUT = os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main', 'assets', 'i18n')

strings = [l.rstrip('\n') for l in open(sys.argv[1], encoding='utf-8') if l.strip()]
os.makedirs(OUT, exist_ok=True)
dev = 'cuda' if torch.cuda.is_available() else 'cpu'
for lang in sys.argv[2:]:
    tok = MarianTokenizer.from_pretrained(MODELS[lang])
    model = MarianMTModel.from_pretrained(MODELS[lang]).to(dev).eval()
    out = {}
    for i in range(0, len(strings), 32):
        batch = strings[i:i + 32]
        enc = tok([PREFIX.get(lang, '') + s for s in batch], return_tensors='pt', padding=True, truncation=True, max_length=256).to(dev)
        with torch.no_grad():
            gen = model.generate(**enc, num_beams=4, max_new_tokens=256)
        for s, t in zip(batch, tok.batch_decode(gen, skip_special_tokens=True)):
            t = t.strip()
            if not t or any(n in s and n not in t for n in NAMES): continue
            # quotes and arrows the strings use stay as written
            if s.count('“') != t.count('“') and '“' in s: t = t.replace('"', '“', 1).replace('"', '”', 1)
            if t != s: out[s] = t
    with open(os.path.join(OUT, f'{lang}.json'), 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=0, sort_keys=True)
    print(lang, len(out), 'of', len(strings), 'translated')
