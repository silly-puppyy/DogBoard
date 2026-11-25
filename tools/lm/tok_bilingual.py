"""Tokenizer and token streams for the English + Spanish bilingual recipes (notes/research/bilingual-recipes.md, private
study). Text from make_bi_text.py (lm-text/en-es-en, lm-text/en-es-es); everything under lm-text/:
  tok     one SentencePiece unigram 16k (caps.py folding, as tok_prep_ru.py R0) on 1M English + 1M Spanish lines;
          user symbols <cap> (id 4), <en> (5), <es> (6)
  cap     both languages cut to the same token count (the smaller's total, by this tokenizer) -> en-es-encap/{train,val}.txt
          (English only, also the English baseline's text), en-es-bi/{train,val}.txt + .lang (one e / s per line) = the shuffled mix
  r1      en-es-bi-r1/{train,val}.bin: separate (recipe 1), BOS-separated lines, as every student
  r2      en-es-bi-r2/: recipe 1 with 10 % of the lines code-switched (60 % insertions, 40 % clause alternations, as bilingual_switch.py)
  r3      en-es-bi-r3/: every line = BOS, <en>|<es>, text with P (0.4) of its translatable content words replaced by their
          translation; train_rep.bin + train_rep.mask (0 = not predicted: the language token, the replaced words), val.bin = plain
          lines with their language token. Used with train.py AUG=1 AUGNAME=train_rep (a fully masked stream).
Usage: tok_bilingual.py tok|cap|r1|r2|r3   (THREADS=3 processes)"""
import os, re, sys, random, numpy as np, sentencepiece as spm
from multiprocessing import Pool
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import caps, bilingual_switch as bs

L = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text'
T = f'{L}/en-es-tok'
STAGE = sys.argv[1]; N = int(os.environ.get('THREADS', '3'))
P_REPLACE = float(os.environ.get('P_REPLACE', '0.4')); P_SWITCH = float(os.environ.get('P_SWITCH', '0.1'))
SYMS = [caps.CAP_SYMBOL, '<en>', '<es>']
LANGTOK = {'e': 5, 's': 6}


def lines(path):
    return [l.rstrip('\n') for l in open(path, encoding='utf-8')]


def tokenizer():
    os.makedirs(T, exist_ok=True)
    sample = f'{T}/sp_sample.txt'
    with open(sample, 'w', encoding='utf-8') as o:
        for lg in ('en', 'es'):
            with open(f'{L}/en-es-{lg}/train.txt', encoding='utf-8') as f:
                for i, line in enumerate(f):
                    if i >= 1_000_000: break
                    o.write(caps.fold(line.rstrip('\n'))[0] + '\n')
    spm.SentencePieceTrainer.train(input=sample, model_prefix=f'{T}/sp16k', vocab_size=16000, model_type='unigram',
        character_coverage=0.9995, byte_fallback=True, num_threads=N, input_sentence_size=3_000_000, shuffle_input_sentence=True,
        max_sentence_length=4096, pad_id=0, unk_id=3, bos_id=1, eos_id=2, split_digits=True, user_defined_symbols=SYMS)
    os.remove(sample)
    sp = spm.SentencePieceProcessor(model_file=f'{T}/sp16k.model')
    assert [sp.piece_to_id(s) for s in SYMS] == [4, 5, 6]


_sp = None
def sp():
    global _sp
    if _sp is None: _sp = spm.SentencePieceProcessor(model_file=f'{T}/sp16k.model')
    return _sp


def encode_words(words, flags=None, lang=None):
    """ids (BOS, language token if lang, CAP + pieces) and the target mask (0 = not predicted) for a line given as words."""
    s = sp(); starts = caps.word_starts(s)
    if flags is None: flags = [False] * len(words)
    keep = [i for i, w in enumerate(words) if w]  # caps.fold splits on whitespace: empty words (double spaces) vanish
    words, flags = [words[i] for i in keep], [flags[i] for i in keep]
    text, cap = caps.fold(' '.join(words))
    ids = [1] + ([LANGTOK[lang]] if lang else []); mask = [1] + ([0] if lang else [])
    k = -1
    for t in s.encode(text):
        if starts[t]:
            k += 1
            if k < len(cap) and cap[k]: ids.append(caps.CAP); mask.append(0 if k < len(flags) and flags[k] else 1)
        ids.append(t); mask.append(0 if 0 <= k < len(flags) and flags[k] else 1)
    return ids, mask


def count_chunk(chunk):
    s = sp(); n = 0
    for text, cap in (caps.fold(l) for l in chunk): n += len(s.encode(text)) + sum(cap) + 1
    return n


def chunks(xs, k=20000):
    return [xs[i:i + k] for i in range(0, len(xs), k)]


_tb = None
def tables():
    global _tb
    if _tb is None: _tb = bs.Tables()
    return _tb


def switch_chunk(arg):
    """Recipe 2: lines [(text, lang)] -> same lines, P_SWITCH of them code-switched. `pool` = other-language lines for alternations."""
    seed, items, pool = arg; rnd = random.Random(seed); tb = tables(); out = []; kinds = {'insert': 0, 'alternate': 0, 'none': 0}
    for text, lg in items:
        new = None
        if rnd.random() < P_SWITCH:
            lm = 'en' if lg == 'e' else 'es'; lo = 'es' if lm == 'en' else 'en'
            order = ['insert', 'alternate'] if rnd.random() < 0.6 else ['alternate', 'insert']
            for kind in order:
                new = bs.insert(tb, rnd, text, lm) if kind == 'insert' else bs.alternate(rnd, text, rnd.choice(pool[lo]))
                if new and new != text: kinds[kind] += 1; break
                new = None
            if new is None: kinds['none'] += 1
        out.append(new or text)
    return out, kinds


def encode_chunk(arg):
    """items [(text, lang)] -> (uint16 ids, uint8 mask, (replaced, eligible-free words, words))."""
    mode, seed, items = arg; rnd = random.Random(seed); tb = tables(); ids, mask = [], []; rep = tot = 0
    for text, lg in items:
        if mode == 'plain': i, m = encode_words(text.split(' '))
        elif mode == 'lang': i, m = encode_words(text.split(' '), None, lg)
        else:
            words, flags = bs.replace(tb, rnd, text, 'en' if lg == 'e' else 'es', P_REPLACE)
            i, m = encode_words(words, flags, lg); rep += sum(flags); tot += len(words)
        ids += i; mask += m
    return np.array(ids, dtype=np.uint16), np.array(mask, dtype=np.uint8), (rep, tot)


def main():
    if STAGE == 'tok': return tokenizer()
    pool = Pool(N)
    if STAGE == 'cap':
        tot = {}
        data = {lg: lines(f'{L}/en-es-{lg}/train.txt') for lg in ('en', 'es')}
        cum = {}
        for lg, ls in data.items():
            counts = pool.map(count_chunk, chunks(ls)); per = []
            # per-line counts: recount cumulatively by chunk (coarse cut: chunk-wise, then trimmed below)
            cum[lg] = np.cumsum(counts); tot[lg] = int(cum[lg][-1]); print(lg, f'{tot[lg]:,} tokens, {len(ls):,} lines', flush=True)
        target = min(tot.values())
        keep = {}
        for lg, ls in data.items():
            ci = int(np.searchsorted(cum[lg], target)); keep[lg] = ls[:min(len(ls), (ci + 1) * 20000)]
            # trim the last chunk line by line to the target
            if tot[lg] > target:
                over = int(cum[lg][ci]) - target; last = chunks(ls)[ci]
                done = int(cum[lg][ci - 1]) if ci else 0; n = 0
                for j, l in enumerate(last):
                    n += count_chunk([l])
                    if done + n >= target: keep[lg] = ls[:ci * 20000 + j + 1]; break
            print(lg, 'kept', f'{len(keep[lg]):,}', 'lines', flush=True)
        os.makedirs(f'{L}/en-es-encap', exist_ok=True); os.makedirs(f'{L}/en-es-bi', exist_ok=True)
        open(f'{L}/en-es-encap/train.txt', 'w', encoding='utf-8').write('\n'.join(keep['en']) + '\n')
        open(f'{L}/en-es-encap/val.txt', 'w', encoding='utf-8').write(open(f'{L}/en-es-en/val.txt', encoding='utf-8').read())
        rows = [(l, 'e') for l in keep['en']] + [(l, 's') for l in keep['es']]
        random.Random(5).shuffle(rows)
        with open(f'{L}/en-es-bi/train.txt', 'w', encoding='utf-8') as f, open(f'{L}/en-es-bi/train.lang', 'w') as g:
            f.write('\n'.join(r[0] for r in rows) + '\n'); g.write(''.join(r[1] + '\n' for r in rows))
        ven, ves = lines(f'{L}/en-es-en/val.txt')[:10000], lines(f'{L}/en-es-es/val.txt')[:10000]
        rows = [(l, 'e') for l in ven] + [(l, 's') for l in ves]; random.Random(6).shuffle(rows)
        with open(f'{L}/en-es-bi/val.txt', 'w', encoding='utf-8') as f, open(f'{L}/en-es-bi/val.lang', 'w') as g:
            f.write('\n'.join(r[0] for r in rows) + '\n'); g.write(''.join(r[1] + '\n' for r in rows))
        print('target', f'{target:,}', 'bi lines', len(keep['en']) + len(keep['es']))
        return
    # r1 / r2 / r3: the shuffled bilingual lines with their languages
    out = f'{L}/en-es-bi-{STAGE}'; os.makedirs(out, exist_ok=True)
    for split in ('val', 'train'):
        txt = lines(f'{L}/en-es-bi/{split}.txt'); lang = lines(f'{L}/en-es-bi/{split}.lang')
        items = list(zip(txt, lang))
        if STAGE == 'r2' and split == 'train':
            pl = {'en': [t for t, l in items if l == 'e'][:200000], 'es': [t for t, l in items if l == 's'][:200000]}
            res = pool.map(switch_chunk, [(i, c, pl) for i, c in enumerate(chunks(items))])
            items = [(t, l) for (o, _), c in zip(res, chunks(items)) for t, (_, l) in zip(o, c)]
            k = {}
            for _, kk in res:
                for a, b in kk.items(): k[a] = k.get(a, 0) + b
            print('switched', k, 'of', len(items), flush=True)
            open(f'{out}/train.txt', 'w', encoding='utf-8').write('\n'.join(t for t, _ in items) + '\n')
        modes = {'r1': ('plain', 'train'), 'r2': ('plain', 'train'), 'r3': ('lang', 'train_rep')}[STAGE]
        mode = modes[0] if (STAGE != 'r3' or split == 'val') else 'rep'
        name = 'val' if split == 'val' else (modes[1] if STAGE == 'r3' else 'train')
        res = pool.map(encode_chunk, [(mode, 1000 * (split == 'val') + i, c) for i, c in enumerate(chunks(items))])
        ids = np.concatenate([r[0] for r in res]); mask = np.concatenate([r[1] for r in res])
        ids.tofile(f'{out}/{name}.bin')
        if mode == 'rep': mask.tofile(f'{out}/{name}.mask')
        rep = sum(r[2][0] for r in res); tot = sum(r[2][1] for r in res)
        print(split, f'{len(ids):,} tokens', f'replaced {rep:,} of {tot:,} words ({100 * rep / max(tot, 1):.1f} %)' if tot else '', f'masked {100 * (1 - mask.mean()):.1f} %' if mode == 'rep' else '', flush=True)
    if STAGE == 'r3':
        # val: plain lines with their language token; the sentence-piece model is shared with the other recipes
        pass
    import shutil; shutil.copy(f'{T}/sp16k.model', f'{out}/sp16k.model'); shutil.copy(f'{T}/sp16k.vocab', f'{out}/sp16k.vocab')


if __name__ == '__main__': main()
