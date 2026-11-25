#!/usr/bin/env python3
"""Splits the local chat datasets into per-language sentence files (conv-sources/local/<source>-<lang>.tsv, one
"turn id TAB sentence" per line) and prints turns / sentences per language, for the conversational-source study.

    python3 -I conv_extract_local.py DATA_DIR OUT_DIR

WildChat-1M (ODC-By; user turns only), OpenAssistant oasst2 (Apache-2.0; user "prompter" turns of non-synthetic
messages), Aya dataset (Apache-2.0; inputs = prompts written by contributors, targets = their answers)."""
import glob, gzip, json, os, sys, collections
import pyarrow.parquet as pq
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from conv_common import sentences

WC = {'Afrikaans': 'af', 'Arabic': 'ar', 'Azerbaijani': 'az', 'Bulgarian': 'bg', 'Bengali': 'bn', 'Bosnian': 'bs', 'Catalan': 'ca',
      'Czech': 'cs', 'Danish': 'da', 'German': 'de', 'Greek': 'el', 'English': 'en', 'Spanish': 'es', 'Estonian': 'et',
      'Basque': 'eu', 'Persian': 'fa', 'Finnish': 'fi', 'French': 'fr', 'Galician': 'gl', 'Hebrew': 'he', 'Hindi': 'hi',
      'Croatian': 'hr', 'Hungarian': 'hu', 'Armenian': 'hy', 'Indonesian': 'id', 'Italian': 'it', 'Georgian': 'ka',
      'Kazakh': 'kk', 'Korean': 'ko', 'Lithuanian': 'lt', 'Latvian': 'lv', 'Macedonian': 'mk', 'Marathi': 'mr', 'Malay': 'ms',
      'Norwegian': 'nb', 'Dutch': 'nl', 'Polish': 'pl', 'Portuguese': 'pt', 'Romanian': 'ro', 'Russian': 'ru', 'Slovak': 'sk',
      'Slovenian': 'sl', 'Albanian': 'sq', 'Serbian': 'sr', 'Swedish': 'sv', 'Tamil': 'ta', 'Turkish': 'tr', 'Ukrainian': 'uk',
      'Urdu': 'ur', 'Vietnamese': 'vi'}
AYA = {'afr': 'af', 'arb': 'ar', 'ben': 'bn', 'cat': 'ca', 'ces': 'cs', 'dan': 'da', 'deu': 'de', 'ell': 'el', 'eng': 'en', 'spa': 'es',
       'est': 'et', 'eus': 'eu', 'pes': 'fa', 'fin': 'fi', 'fra': 'fr', 'heb': 'he', 'hin': 'hi', 'hrv': 'hr', 'hun': 'hu',
       'ind': 'id', 'ita': 'it', 'kor': 'ko', 'lit': 'lt', 'mar': 'mr', 'zsm': 'ms', 'nld': 'nl', 'pol': 'pl', 'por': 'pt',
       'rus': 'ru', 'srp': 'sr', 'swe': 'sv', 'tam': 'ta', 'tur': 'tr', 'ukr': 'uk', 'urd': 'ur', 'vie': 'vi', 'als': 'sq'}


def main():
    data, out = sys.argv[1:3]
    os.makedirs(out, exist_ok=True)
    files = {}; turns = collections.Counter(); sents = collections.Counter()

    def put(src, lang, tid, text):
        key = (src, lang)
        if key not in files: files[key] = open(f'{out}/{src}-{lang}.tsv', 'w', encoding='utf-8')
        turns[key] += 1
        for s in sentences(text):
            files[key].write(f'{tid}\t{s.replace(chr(9), " ")}\n'); sents[key] += 1

    for f in sorted(glob.glob(f'{data}/hf/wildchat-1m/*.parquet')):
        for r in pq.read_table(f, columns=['conversation_hash', 'conversation', 'language']).to_pylist():
            lang = WC.get(r['language'])
            if not lang: continue
            for i, u in enumerate(r['conversation']):
                if u['role'] == 'user': put('wildchat', lang, f"{r['conversation_hash'][:8]}.{i}", u['content'])
    for line in gzip.open(f'{data}/hf/oasst2/ready.messages.jsonl.gz', 'rt'):
        m = json.loads(line)
        if m['role'] == 'prompter' and not m.get('synthetic') and not m.get('deleted'):
            put('oasst2', m['lang'].split('-')[0], m['message_id'][:8], m['text'])
    for r in pq.read_table(f'{data}/hf/aya/train.parquet', columns=['inputs', 'targets', 'language_code', 'user_id']).to_pylist():
        lang = AYA.get(r['language_code'])
        if lang:
            put('aya-in', lang, r['user_id'][:6], r['inputs']); put('aya-out', lang, r['user_id'][:6], r['targets'])
    for f in files.values(): f.close()
    for (src, lang), n in sorted(turns.items(), key=lambda x: (x[0][1], x[0][0])): print(src, lang, n, sents[(src, lang)], sep='\t')


main()
