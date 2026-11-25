#!/usr/bin/env python3
"""Streams a slice of FineWeb-2 (ODC-By 1.0) for one language and keeps (a) the most conversational documents and (b) a
random control sample, as KenLM training lines (the packs' tokens: lowercase words, one sentence per line).

    python3 -I conv_fw2_slice.py LANG CONFIG OUT_DIR [--groups N] [--cap LINES] [--workers W] [--percent P]

Reads row groups of data/<CONFIG>/train/000_00000.parquet over HTTP (1000 documents each, ~2 MB), nothing is stored but
the output. A document is "chatty" when first/second-person pronouns + 2 x question marks per 100 tokens are in the top
P percent of the sampled documents (threshold from the first 40 groups) and its sentences average <= 22 tokens. Writes
OUT_DIR/chatty.txt, control.txt, hosts.tsv (domains of the chatty documents), stats.json. Also chatty.hosts: the host of
every line of chatty.txt (same order), so a host found unwanted afterwards can be dropped without a new download.
--no-control skips the control sample (control.txt stays empty)."""
import argparse, collections, hashlib, json, multiprocessing as mp, os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from conv_common import PRON, WORD, lower, Chat, SPLIT

TOK = re.compile(r'\w+')


def doc_score(text, pron):
    toks = [t for t in TOK.findall(text.lower())]
    n = len(toks)
    if n < 25 or n > 3000: return None
    p = sum(t in pron for t in toks)
    q = text.count('?')
    ends = max(1, len(re.findall(r'[.!?…]+(?:\s|$)|\n', text)))
    return (100.0 * p / n + 200.0 * q / n), n / ends


def lines_of(text, lang):
    out = []
    for s in SPLIT.split(text):
        w = [lower(x, lang).replace('’', "'") for x in WORD.findall(s)]
        if len(w) >= 2: out.append(' '.join(w))
    return out


def worker(args):
    lang, cfg, groups, thr, out, cap, ctrl_mod = args
    from huggingface_hub import HfFileSystem
    import pyarrow.parquet as pq
    pf = pq.ParquetFile(HfFileSystem().open(f'datasets/HuggingFaceFW/fineweb-2/data/{cfg}/train/000_00000.parquet', 'rb'))
    pron = PRON[lang]
    nc = 0; nr = 0 if ctrl_mod else cap; hosts = collections.Counter(); chat = Chat(lang)
    with open(out + '.chatty', 'w', encoding='utf-8') as fc, open(out + '.control', 'w', encoding='utf-8') as fr, \
            open(out + '.chattyhost', 'w', encoding='utf-8') as fh:
        for g in groups:
            if nc >= cap and nr >= cap: break
            for i, r in enumerate(pf.read_row_group(g, columns=['text', 'url']).to_pylist()):
                text = r['text']
                sc = doc_score(text, pron)
                if sc is None: continue
                if nc < cap and sc[0] >= thr and sc[1] <= 22:
                    ls = lines_of(text, lang)
                    host = re.sub(r'^https?://(www\.)?', '', r['url']).split('/')[0].lower()
                    for l in ls: fc.write(l + '\n'); fh.write(host + '\n')
                    nc += len(ls)
                    hosts[host] += 1
                elif ctrl_mod and nr < cap and (g * 1000 + i) % ctrl_mod == 0:
                    ls = lines_of(text, lang)
                    for l in ls: fr.write(l + '\n')
                    nr += len(ls)
    return nc, nr, hosts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('lang'); ap.add_argument('config'); ap.add_argument('out')
    ap.add_argument('--groups', type=int, default=600); ap.add_argument('--cap', type=int, default=1_600_000)
    ap.add_argument('--workers', type=int, default=5); ap.add_argument('--percent', type=float, default=12)
    ap.add_argument('--ctrl-mod', type=int, default=6)
    ap.add_argument('--no-control', action='store_true')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    from huggingface_hub import HfFileSystem
    import pyarrow.parquet as pq
    pf = pq.ParquetFile(HfFileSystem().open(f'datasets/HuggingFaceFW/fineweb-2/data/{a.config}/train/000_00000.parquet', 'rb'))
    n = pf.metadata.num_row_groups
    # calibrate: threshold = (100 - percent) percentile of the document scores in 40 spread groups
    scores = []
    for g in [int(i * n / 40) for i in range(40)]:
        for r in pf.read_row_group(g, columns=['text']).to_pylist():
            sc = doc_score(r['text'], PRON[a.lang])
            if sc and sc[1] <= 22: scores.append(sc[0])
    scores.sort(); thr = scores[int(len(scores) * (1 - a.percent / 100))]
    print('threshold', thr, 'of', len(scores), 'docs', flush=True)
    order = [int(i * n / a.groups) for i in range(a.groups)]
    jobs = [(a.lang, a.config, order[w::a.workers], thr, f'{a.out}/part{w}', a.cap // a.workers, 0 if a.no_control else a.ctrl_mod) for w in range(a.workers)]
    with mp.Pool(a.workers) as pool: res = pool.map(worker, jobs)
    hosts = collections.Counter()
    for _, _, h in res: hosts.update(h)
    for kind in ('chatty', 'control'):
        seen = set(); kept = 0
        with open(f'{a.out}/{kind}.txt', 'w', encoding='utf-8') as out, open(f'{a.out}/{kind}.hosts', 'w', encoding='utf-8') as outh:
            for w in range(a.workers):
                hostf = open(f'{a.out}/part{w}.chattyhost', encoding='utf-8') if kind == 'chatty' else None
                for l in open(f'{a.out}/part{w}.{kind}', encoding='utf-8'):
                    host = hostf.readline() if hostf else None
                    h = hashlib.md5(l.encode()).digest()
                    if h in seen: continue
                    seen.add(h); out.write(l); kept += 1
                    if hostf: outh.write(host)
                if hostf: hostf.close(); os.remove(f'{a.out}/part{w}.chattyhost')
                os.remove(f'{a.out}/part{w}.{kind}')
        print(kind, kept, 'lines after exact dedup', flush=True)
    with open(f'{a.out}/hosts.tsv', 'w') as f:
        for h, c in hosts.most_common(60): f.write(f'{h}\t{c}\n')
    json.dump(dict(threshold=thr, groups=a.groups, percent=a.percent, res=[r[:2] for r in res]), open(f'{a.out}/stats.json', 'w'))


if __name__ == '__main__':
    main()
