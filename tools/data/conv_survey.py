#!/usr/bin/env python3
"""Chattiness of text sources per language (conversational-source study, 2026-11-25).

    python3 -I conv_survey.py DATA_DIR LANG [LANG...]      -> JSON lines on stdout

Sources per language: Leipzig news (the packs' text), a FineWeb-2 sample (8 row groups spread over the first file of
the config, streamed, nothing stored), the local chat sets (conv_extract_local.py), Tatoeba, extra Leipzig corpora
dropped into DATA_DIR/conv-sources/leipzig-web/<name>/ (web / mixed / community). Measures: conv_common.Chat."""
import glob, json, os, random, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from conv_common import Chat, sentences

ISO3 = {'bg': 'bul', 'es': 'spa', 'ru': 'rus', 'de': 'deu', 'fr': 'fra', 'it': 'ita', 'pt': 'por', 'pl': 'pol', 'cs': 'ces', 'nl': 'nld',
        'tr': 'tur', 'sv': 'swe', 'ro': 'ron', 'uk': 'ukr'}
FW2 = {'bg': 'bul_Cyrl', 'es': 'spa_Latn', 'ru': 'rus_Cyrl', 'de': 'deu_Latn', 'fr': 'fra_Latn', 'it': 'ita_Latn', 'pt': 'por_Latn',
       'pl': 'pol_Latn', 'cs': 'ces_Latn', 'nl': 'nld_Latn', 'tr': 'tur_Latn', 'sv': 'swe_Latn', 'ro': 'ron_Latn', 'uk': 'ukr_Cyrl'}
CAP = 30000


def leipzig(data, lang, pattern):
    out = []
    for d in sorted(glob.glob(f'{data}/leipzig/{ISO3[lang]}*_{pattern}*')) + sorted(glob.glob(f'{data}/conv-sources/leipzig-web/{ISO3[lang]}*_{pattern}*')):
        if os.path.isdir(d):
            for f in glob.glob(d + '/*-sentences.txt'):
                with open(f, encoding='utf-8', errors='replace') as fh:
                    lines = [l.rstrip('\n').split('\t')[-1] for l in fh]
                random.Random(1).shuffle(lines)
                out.append((os.path.basename(d), lines[:CAP]))
    return out


def fineweb(lang):
    from huggingface_hub import HfFileSystem
    import pyarrow.parquet as pq
    cfg = FW2[lang]
    fs = HfFileSystem()
    pf = pq.ParquetFile(fs.open(f'datasets/HuggingFaceFW/fineweb-2/data/{cfg}/train/000_00000.parquet', 'rb'))
    n = pf.metadata.num_row_groups
    out = []
    for g in [int(i * n / 8) for i in range(8)]:
        for r in pf.read_row_group(g, columns=['text']).to_pylist()[::4]:
            out.extend(sentences(r['text']))
    random.Random(1).shuffle(out)
    return out[:CAP]


def main():
    data, langs = sys.argv[1], sys.argv[2:]
    for lang in langs:
        rows = {}
        sets = {}
        for name, lines in leipzig(data, lang, 'news'): sets[f'leipzig {name}'] = lines
        for name, lines in leipzig(data, lang, 'web') + leipzig(data, lang, 'mixed') + leipzig(data, lang, 'community'): sets[f'leipzig {name}'] = lines
        try: sets['fineweb-2 sample'] = fineweb(lang)
        except Exception as e: print('fineweb failed', lang, e, file=sys.stderr)
        for src in ('wildchat', 'oasst2', 'aya-in'):
            f = f'{data}/conv-sources/local/{src}-{lang}.tsv'
            if os.path.exists(f):
                ls = [l.rstrip('\n').partition('\t')[2] for l in open(f, encoding='utf-8', newline='\n')]
                random.Random(1).shuffle(ls); sets[src + ' user turns'] = ls[:CAP]
        f = f'{data}/tatoeba/{ {"bg":"bul","es":"spa"}.get(lang, ISO3[lang])}_sentences.tsv'
        if os.path.exists(f):
            ls = [l.rstrip('\n').split('\t')[-1] for l in open(f, encoding='utf-8', newline='\n')]
            random.Random(1).shuffle(ls); sets['tatoeba'] = ls[:CAP]
        for name, lines in sets.items():
            c = Chat(lang)
            for s in lines: c.add(s)
            print(json.dumps(dict(lang=lang, source=name, **c.row()), ensure_ascii=False), flush=True)


main()
