#!/usr/bin/env python3
"""Translates DogBoard's longer UI sentences with a local instruction model (Qwen3-4B, Apache 2.0) told what it is
translating (a keyboard app's settings) and given a glossary, into MT_DIR/<lang>.json (read by ui_merge.py, which adds
the hand-translated labels). OPUS-MT, the first try, had no context: "key" became a lock's key (Schlüssel, llave, clé)
and "theme" a topic (Thema) in 18-25 sentences per language (German screenshot sweep, 2026-11-26).
A translation is kept only if it keeps the sentence's quoted examples and uses none of the glossary's wrong words;
ui_backcheck.py then checks it by translating back.

    HF_HOME=<data drive>/hf ui_translate_llm.py ui-strings.txt MT_DIR de es fr"""
import json, os, re, sys
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

MODEL = 'Qwen/Qwen3-4B'
NAMES = {'de': 'German', 'es': 'Spanish (Spain)', 'fr': 'French'}
STYLE = {'de': 'formal "Sie"', 'es': 'formal "usted"', 'fr': 'formal "vous"'}
GLOSSARY = {
    'de': 'key (on the keyboard) = Taste; keyboard = Tastatur; theme = Design; return key = Eingabetaste; space bar = Leertaste; '
          'shift = Umschalttaste; suggestion bar = Vorschlagsleiste; clipboard = Zwischenablage; clip = Clip; autocorrect = Autokorrektur; '
          'swipe typing = Wischen zum Schreiben; long-press = lange drücken; pack = Paket; tap = tippen',
    'es': 'key (on the keyboard) = tecla; keyboard = teclado; theme = tema; return key = tecla Intro; space bar = barra espaciadora; '
          'shift = Mayús; suggestion bar = barra de sugerencias; clipboard = portapapeles; clip = recorte; autocorrect = autocorrección; '
          'swipe typing = deslizar para escribir; long-press = mantener pulsado; pack = paquete; tap = tocar',
    'fr': 'key (on the keyboard) = touche; keyboard = clavier; theme = thème; return key = touche Entrée; space bar = barre d\'espace; '
          'shift = Maj; suggestion bar = barre de suggestions; clipboard = presse-papiers; clip = élément; autocorrect = correction automatique; '
          'swipe typing = écriture glissée; long-press = appui long; pack = pack; tap = toucher',
}
WRONG = {'de': r'Schlüssel|\bThem(a|en)\b|Keyboard', 'es': r'\bllaves?\b|\bclaves?\b', 'fr': r'\bclés?\b|\bclefs?\b'}
KEEP = ['DogBoard', 'iPhone', 'Android', 'QWERTY', 'Gboard', 'GitHub', 'F-Droid', 'Unsplash', 'GIF']

strings, out_dir = sys.argv[1], sys.argv[2]
todo = [l.rstrip('\n') for l in open(strings, encoding='utf-8') if len(l.split()) > 4]
tok = AutoTokenizer.from_pretrained(MODEL); tok.padding_side = 'left'
model = AutoModelForCausalLM.from_pretrained(MODEL, torch_dtype=torch.bfloat16).to('cuda').eval()
for lang in sys.argv[3:]:
    system = (f'You translate the settings screens of DogBoard, an Android keyboard app, from English into {NAMES[lang]}. '
              f'Use the {STYLE[lang]} register, short natural UI wording, and this glossary: {GLOSSARY[lang]}. '
              f'Keep product names ({", ".join(KEEP)}) and anything in quotes that is an example of typing (like “le maison”) exactly as written; '
              'translate quote marks to the language\'s usual ones. Reply with the translation only.')
    out = {}
    for i in range(0, len(todo), 8):
        batch = todo[i:i + 8]
        prompts = [tok.apply_chat_template([{'role': 'system', 'content': system}, {'role': 'user', 'content': s}],
                                           tokenize=False, add_generation_prompt=True, enable_thinking=False) for s in batch]
        enc = tok(prompts, return_tensors='pt', padding=True).to('cuda')
        with torch.no_grad():
            gen = model.generate(**enc, max_new_tokens=200, do_sample=False)
        for s, g in zip(batch, tok.batch_decode(gen[:, enc['input_ids'].shape[1]:], skip_special_tokens=True)):
            t = g.strip().split('\n')[0].strip()
            quoted = re.findall(r'“([^”]+)”', s)
            if not t or re.search(WRONG[lang], t) or any(q not in t for q in quoted) or any(n in s and n not in t for n in KEEP): continue
            out[s] = t
    json.dump(out, open(os.path.join(out_dir, f'{lang}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0, sort_keys=True)
    print(lang, len(out), 'of', len(todo), 'kept')
