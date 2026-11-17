"""Second-mix training text (private, on the SSD): broader registers for the student, one file per
source so the mix weights are chosen at tokenising time. Same rules as lm_text.py / pilot_mix.py:
build_ngrams' PII scrub (emails, URLs, handles, phone numbers), whitespace collapsed, exact duplicates
dropped, and every line equal (normalised) to a benchmark sentence dropped (all buckets, chat, human,
sms, sms-na). Each source stops at its word cap.
  reddit:  webis tldr-17 post bodies (CC BY 4.0), one paragraph per line, [deleted]/[removed] skipped
  soda:    AllenAI SODA dialogue turns (CC BY 4.0, LLM-written), one turn per line
  fineweb: FineWeb sample-10BT (ODC-By), one paragraph per line, 4..120-word paragraphs
Usage: lm_text_v2.py SOURCE  -> keyboard-data/lm-text/v2/SOURCE.txt"""
import os, re, sys, json, glob, zipfile, hashlib, ast
sys.path.insert(0, '/home/syn/Documents/Code/AI/keyboard/dogboard/tools/data')
import build_ngrams as bn

D = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data'
OUT = os.path.join(D, 'lm-text/v2'); os.makedirs(OUT, exist_ok=True)
CAPS = {'reddit': 300_000_000, 'soda': 60_000_000, 'fineweb': 400_000_000}
WS = re.compile(r'\s+')
NORM = lambda t: re.sub(r'[^a-z0-9 ]', '', WS.sub(' ', t.lower())).strip()
B = '/home/syn/.claude/jobs/eb2fb8fd/tmp/predbench/'
EVAL = glob.glob('/home/syn/Documents/Code/AI/keyboard/data/eval/buckets/*.txt') + [B + n + '.txt' for n in ('chat', 'human', 'sms', 'sms-na')]
HELD = {NORM(l) for f in EVAL for l in open(f, encoding='utf-8') if l.strip()}

def reddit():
    with zipfile.ZipFile(os.path.join(D, 'corpora/reddit_tldr17/data/corpus-webis-tldr-17.zip')) as z:
        with z.open('corpus-webis-tldr-17.json') as f:
            for line in f:
                body = json.loads(line).get('body') or ''
                if body.strip() in ('[deleted]', '[removed]'): continue
                yield from body.split('\n\n')

def soda():
    import pyarrow.parquet as pq
    for split in ('train', 'valid'):  # test split left alone
        t = pq.read_table(os.path.join(D, f'corpora/soda/{split}.parquet'), columns=['dialogue'])
        for d in t.column('dialogue').to_pylist():
            turns = d if isinstance(d, list) else ast.literal_eval(d)
            yield from turns

def fineweb():
    import pyarrow.parquet as pq
    for path in sorted(glob.glob(os.path.join(D, 'fineweb/sample-10BT/sample/10BT/*.parquet'))):
        pf = pq.ParquetFile(path)
        for batch in pf.iter_batches(columns=['text'], batch_size=2000):
            for doc in batch.column('text').to_pylist():
                for para in doc.split('\n'):
                    if 4 <= len(para.split()) <= 120: yield para

name = sys.argv[1]; cap = CAPS[name]
seen = set(); n = w = held = 0
with open(os.path.join(OUT, f'{name}.txt.tmp'), 'w', encoding='utf-8') as out:
    for text in {'reddit': reddit, 'soda': soda, 'fineweb': fineweb}[name]():
        t = WS.sub(' ', bn.clean(text)).strip()
        if len(t) < 2: continue
        if NORM(t) in HELD: held += 1; continue
        h = hashlib.md5(t.encode()).digest()[:8]
        if h in seen: continue
        seen.add(h); out.write(t + '\n'); n += 1; w += len(t.split())
        if n % 1_000_000 == 0: print(f'{name}: {n:,} lines {w:,} words', flush=True)
        if w >= cap: break
os.replace(os.path.join(OUT, f'{name}.txt.tmp'), os.path.join(OUT, f'{name}.txt'))
print(f'{name} done: {n:,} lines, {w:,} words, {held} benchmark lines dropped')
