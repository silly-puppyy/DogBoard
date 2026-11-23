#!/usr/bin/env python3
"""Bootstraps a NEW language's pack from its code, end to end, into packs-work/<lang> (never the app's assets; the
spec-driven tools/i18n/build_pack.py stays the tool for shipped packs and their hand-tuned files). Builds a DogBoard
language pack from a language code (user 2026-11-22: "a script that just gets a
language and computes a language pack, e.g. Czech, Arabic or Georgian"). The steps the es / fr / fi / ru packs were
built with by hand (notes/design/i18n-pipeline.md "Tools to add a language"), in order, each skipped when its output
exists (re-run after a failure; delete a file to redo its step):

  1 corpora    Leipzig sentence corpora (CC BY), downloaded and unpacked (LANGS[lang]["leipzig"])
  2 text       held-out lines (ids ending in 0: test sets only) and the training text (leipzig_sentences.py)
  3 lexicon    wordfreq + corpus (build_lexicon_hunspell.py without Hunspell; corpus counts alone where wordfreq has no
               list, e.g. Georgian)
  4 ngrams     KenLM trigram -> arpa_to_dogboard.py --rank entropy --keep-twins (accent twins' entries kept) -> DGN3
  5 bigrams    Tatoeba sentences (CC BY 2.0 FR) -> bigrams_from_tatoeba.py; skipped when Tatoeba has too few
  6 charlm     letter model on the corpus words the lexicon lacks, alphabet from the lexicon (build_charlm.py)
  7 rules      rules.json: letter rows from the layout data (Gboard registry / iOS), distinct letters (letters with
               a key of their own that fold to another), compounds, sentence ends
  8 tests      typo / accent / control sets from held-out sentences on the layout (make_es_sets.py: typo simulator)
  9 snapshots  the app's snapshots (SnapshotExportTest) and manifest.json (sizes, SHA-256, sources)
 10 eval       RealTypoEvalTest on the sets -> report.md (fixed / wrong / broken)

Usage: auto_pack.py LANG [--through STEP] [--n 2000]     packs land in keyboard-data/packs-work/<lang>/
LANGS holds what can't be derived (corpus names, Tatoeba code, layout choice); everything else is computed."""
import argparse, glob, gzip, hashlib, json, shutil, math, os, re, subprocess, sys, tarfile, time, unicodedata, urllib.request, zlib
from collections import Counter

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
DATA = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data'
KENLM = f'{DATA}/kenlm/build/bin/lmplz'
WORDFREQ = f'{DATA}/wordfreq/whl/wordfreq/data'
WF_PY = '/tmp/claude-1000/-home-syn-Documents-Code-AI-keyboard/eb2fb8fd-7646-4213-845e-8d73c47f6e37/scratchpad/wfvenv/bin/python'
# the typo simulator and test-set maker (tools/i18n/sim: copied from the scratch benchmark folder 2026-11-23, so a pack
# builds from the repository alone)
PREDBENCH = f'{REPO}/tools/i18n/sim'
LAYOUTS = os.path.join(REPO, '..', 'notes', 'design', 'data', 'layouts')
TATOEBA = os.path.join(REPO, '..', 'data', 'tatoeba')
CPU = os.environ.get('PACK_CPUS', '3-6')
LEIPZIG_URL = 'https://downloads.wortschatz-leipzig.de/corpora/{}.tar.gz'
TATOEBA_URL = 'https://downloads.tatoeba.org/exports/per_language/{0}/{0}_sentences.tsv.bz2'

# what can't be derived: Leipzig corpus names (probed 2026-11-22), Tatoeba's ISO 639-3 code, the layout (Gboard
# variant or iOS layout name), the script, and whether words compound (Finnish, German)
LANGS = {
    'fi': dict(name='Finnish', leipzig=['fin_news_2022_1M', 'fin_newscrawl_2016_1M', 'fin_wikipedia_2021_1M'], tatoeba='fin',
               layout=('gboard', 'nordic'), script='Latn', compounds=True),
    # a hand-built pack exists (packs-work/es): this one checks the pipeline against it (PACK_SUFFIX=-auto), as Finnish
    'es': dict(name='Spanish', leipzig=['spa_news_2024_1M', 'spa_web_2016_1M', 'spa_wikipedia_2021_1M'], tatoeba='spa',
               layout=('gboard', 'spanish'), gboard_tag='es-ES', script='Latn', compounds=False),
    # 2026-11-23 language families (user: "perfecting a few languages across families to see what issues arise"):
    # German (capitalised nouns, ß, umlauts by long-press on Gboard's QWERTZ, compounds), Turkish (i / İ, ı / I;
    # agglutination), Polish (ą ę ł ż ... by long-press), Greek (final ς, tonos), Hebrew (final letters, RTL)
    'de': dict(name='German', digraphs={'ä': 'ae', 'ö': 'oe', 'ü': 'ue'}, leipzig=['deu_news_2024_1M', 'deu_news_2023_1M', 'deu_wikipedia_2021_1M'], tatoeba='deu',
               layout=('gboard', 'qwertz'), gboard_tag='de-DE', script='Latn', compounds=True),
    # Romance with clitic pronouns (dá-me, fazê-lo) and ç / ã / õ by long-press; news mixes Brazil and Portugal
    # the hand-built French pack's typography rules carried over (fr/rules.json)
    'fr': dict(name='French', leipzig=['fra_news_2024_1M', 'fra_news_2023_1M', 'fra_wikipedia_2021_1M'], tatoeba='fra',
               layout=('gboard', 'azerty'), gboard_tag='fr-FR', script='Latn', compounds=False,
               rules_extra={'space_before_marks': '?!:;', 'quotes': '«»‘’’', 'double_space': '. '},
               # Hunspell (HUNSPELL=fr, Dicollecte MPL 2.0) on sets drawn from both lists: typos 31.6 / 8.0 vs 31.0 / 8.2,
               # accents 97.9 vs 97.5, twins 94.0 vs 92.8, but broken 0.4 vs 0.2: no clear win, left out (2026-11-23)
               elision='l,d,j,m,t,s,c,n,qu,jusqu,lorsqu,puisqu'),
    # Cyrillic; Gboard's ЙЦУКЕН has ъ on its own key (the hand-built pack's rows had it on long-press); ё folds to е
    'ru': dict(name='Russian', leipzig=['rus_news_2024_1M', 'rus_news_2023_1M', 'rus_wikipedia_2021_1M'], tatoeba='rus',
               layout=('gboard', 'russian'), gboard_tag='ru-RU', script='Cyrl', compounds=False),
    # Hunspell (HUNSPELL=ru, BSD-style) measured on sets drawn from both lists (the sampler takes only listed words, so
    # each list's own sets favour it): typos 40.7 / 7.2 vs 40.9 / 7.3, broken 0.2 vs 0.1, 73 vs 55 MB: not worth it
    # tones and vowel marks stack (ế, ặ); syllables are written apart, so nearly every bare syllable is several words
    # (ma má mà mả mã mạ): the accent-twin stress test. Gboard's default is plain QWERTY (Telex conversion not modelled)
    'vi': dict(name='Vietnamese', leipzig=['vie_news_2022_1M', 'vie_news_2020_1M', 'vie_wikipedia_2021_1M'], tatoeba='vie',
               layout=('gboard', 'qwerty'), gboard_tag='vi-VN', script='Latn', compounds=False),
    'pt': dict(name='Portuguese', leipzig=['por_news_2024_1M', 'por_news_2023_1M', 'por_wikipedia_2021_1M'], tatoeba='por',
               layout=('gboard', 'qwerty'), gboard_tag='pt-BR', script='Latn', compounds=False),
    # Romance with elisions (l'acqua, dell'anno: apostrophe words) and final accents (città, perché) by long-press
    'it': dict(name='Italian', leipzig=['ita_news_2024_1M', 'ita_news_2023_1M', 'ita_wikipedia_2021_1M'], tatoeba='ita',
               layout=('gboard', 'qwerty'), gboard_tag='it-IT', script='Latn', compounds=False),
    'tr': dict(name='Turkish', leipzig=['tur_news_2024_1M', 'tur_news_2022_1M', 'tur_wikipedia_2021_1M'], tatoeba='tur',
               layout=('gboard', 'qwerty'), gboard_tag='tr-TR', script='Latn', compounds=False),
    'pl': dict(name='Polish', leipzig=['pol_news_2024_1M', 'pol_news_2022_1M', 'pol_wikipedia_2021_1M'], tatoeba='pol',
               layout=('gboard', 'qwerty'), gboard_tag='pl-PL', script='Latn', compounds=False),
    'el': dict(name='Greek', leipzig=['ell_news_2024_1M', 'ell_news_2022_1M', 'ell_wikipedia_2021_1M'], tatoeba='ell',
               layout=('gboard', 'greek'), gboard_tag='el-GR', script='Grek', compounds=False),
    'he': dict(name='Hebrew', leipzig=['heb_news_2020_1M', 'heb_wikipedia_2021_1M'], tatoeba='heb',
               layout=('ios', 'Hebrew'), script='Hebr', compounds=False, rtl=True),
    # a sparse language: three 300K corpora, no wordfreq list, agglutinative (2026-11-23)
    'eu': dict(name='Basque', leipzig=['eus_wikipedia_2021_300K', 'eus_wikipedia_2016_300K', 'eus_newscrawl_2012_300K'],
               tatoeba='eus', layout=('gboard', 'spanish'), gboard_tag='eu-ES', script='Latn', compounds=False),
    'cs': dict(name='Czech', leipzig=['ces_news_2024_1M', 'ces_news_2022_1M', 'ces_wikipedia_2021_1M'], tatoeba='ces',
               layout=('gboard', 'qwertz'), script='Latn', compounds=False),
    'ka': dict(name='Georgian', leipzig=['kat-ge_web_2019_1M'], tatoeba='kat',
               # the standard Georgian layout (FlorisBoard's georgian_standard): all 33 letters on the base plane. Gboard's
               # default is the phonetic georgian_qwerty (its languages.json, checked 2026-11-23), as iOS's, with 7
               # letters (ჭ ღ თ შ ჟ ძ ჩ) on Shift, which the rows can't hold: an open question for the user
               layout=('gboard', 'georgian_georgia'), script='Geor', compounds=False),
    # Austronesian: Latin, few diacritics, affixes (meN-, ber-, -kan, -nya); Gboard lists it under the old code "in"
    'id': dict(name='Indonesian', leipzig=['ind_news_2024_1M', 'ind_news_2022_1M', 'ind_wikipedia_2021_1M'], tatoeba='ind',
               layout=('gboard', 'qwerty'), gboard_tag='in', script='Latn', compounds=False),
    # Iranian (Indo-European) in Arabic script, RTL; words carry a zero-width non-joiner (ZWNJ, U+200C: می‌خواهم)
    'fa': dict(name='Persian', leipzig=['fas_news_2022_1M', 'fas_newscrawl_2019_1M', 'pes_wikipedia_2021_1M'], tatoeba='pes',
               layout=('gboard', 'persian'), gboard_tag='fa-IR', script='Arab', compounds=False, rtl=True),
    'ar': dict(name='Arabic', leipzig=['ara_news_2022_1M', 'ara_wikipedia_2021_1M'], tatoeba='ara',
               layout=('gboard', 'arabic'), script='Arab', compounds=False, rtl=True),
}
CLDR_URL = 'https://raw.githubusercontent.com/unicode-org/cldr/main/common/main/{}.xml'


def letters_of(lang):
    """The language's letters (CLDR's main exemplar characters, Unicode licence) in both cases, plus basic Latin for
    names and brands: a corpus sentence with any other letter is mis-encoded or another language ("tøeba" for
    "třeba" in Leipzig's Czech news, 2026-11-22)."""
    path = f'{DATA}/cldr/main/{lang}.xml'
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True); urllib.request.urlretrieve(CLDR_URL.format(lang), path)
    m = re.search(r'<exemplarCharacters>\[([^\]]*)\]', open(path, encoding='utf-8').read())
    chars = {c for c in re.sub(r'\{[^}]*\}', '', m.group(1)) if c.isalpha() or unicodedata.category(c) == 'Mn'}
    return chars | {c.upper() for c in chars} | set('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ')


STEPS = ['corpora', 'text', 'lexicon', 'ngrams', 'bigrams', 'charlm', 'rules', 'tests', 'snapshots', 'eval']


def log(*a): print(time.strftime('%H:%M:%S'), *a, flush=True)


def sh(cmd, **kw):
    log('$', cmd if isinstance(cmd, str) else ' '.join(cmd))
    subprocess.run(cmd, shell=isinstance(cmd, str), check=True, **kw)


def fold(c):
    return unicodedata.normalize('NFD', c)[0]


class Pack:
    def __init__(self, lang, n):
        self.lang, self.cfg, self.n = lang, LANGS[lang], n
        self.dir = f'{DATA}/packs-work/{lang}' + os.environ.get('PACK_SUFFIX', '')
        os.makedirs(self.dir, exist_ok=True)
        self.p = lambda name: os.path.join(self.dir, name)
        # CORPUS_FRACTION=0.25: a learning-curve point (is there enough text?): seeded from the full build in
        # packs-work/<lang>: the same held-out text and test sets, a fixed share of its training text and corpora
        # (by a hash of each line, so 25 % is inside 50 %)
        frac = float(os.environ.get('CORPUS_FRACTION', '1'))
        if frac < 1:
            full = f'{DATA}/packs-work/{lang}'
            keep = lambda line: zlib.crc32(line.encode('utf-8')) % 10000 < frac * 10000
            for name in os.listdir(full):
                src, dst = os.path.join(full, name), self.p(name)
                if os.path.exists(dst): continue
                if name == f'{lang}.heldout.txt' or (name.startswith(f'{lang}-') and name.endswith('.tsv')):
                    shutil.copyfile(src, dst)
                elif name == 'train.txt' or name.startswith('clean-'):
                    with open(src, encoding='utf-8') as f, open(dst + '.tmp', 'w', encoding='utf-8') as o:
                        o.writelines(l for l in f if keep(l))
                    os.rename(dst + '.tmp', dst)

    # 1
    def corpora(self):
        files = []
        for name in self.cfg['leipzig']:
            d = f'{DATA}/leipzig/{name}'
            hit = glob.glob(f'{d}/*-sentences.txt')
            if not hit:
                tgz = f'{DATA}/leipzig/{name}.tar.gz'
                if not os.path.exists(tgz):
                    log('download', name); urllib.request.urlretrieve(LEIPZIG_URL.format(name), tgz)
                # into the corpus's own folder: older archives (fra_mixed_2009, eus_newscrawl_2012) hold their files
                # without one, and the corpus was silently skipped (2026-11-23)
                with tarfile.open(tgz) as t:
                    inner = any(m.name.startswith(name + '/') for m in t.getmembers())
                    t.extractall(f'{DATA}/leipzig' if inner else d, filter='data')
                hit = glob.glob(f'{d}/*-sentences.txt')
            if not hit:
                log('WARNING: no sentences file in', name)
            files += hit
        assert files, 'no corpus'
        # sentences with letters foreign to the language dropped (mis-encoding, other languages), into the pack dir
        ok = letters_of(self.lang); cleaned = []
        for f in files:
            out = self.p('clean-' + os.path.basename(f))
            if not os.path.exists(out):
                kept = dropped = 0
                with open(f, encoding='utf-8', errors='replace') as src, open(out + '.tmp', 'w', encoding='utf-8') as dst:
                    for line in src:
                        text = line.rstrip('\n').split('\t')[-1]
                        if all(c in ok for c in text if c.isalpha() or unicodedata.category(c) == 'Mn'): dst.write(line); kept += 1
                        else: dropped += 1
                os.rename(out + '.tmp', out)
                log(f'{os.path.basename(f)}: kept {kept:,}, dropped {dropped:,} ({100 * dropped / max(1, kept + dropped):.1f} %) with foreign letters')
            cleaned.append(out)
        self.corpus_files = cleaned
        return cleaned

    # 2
    def text(self):
        if os.path.exists(self.p('train.txt')): return
        with open(self.p('train.txt'), 'w', encoding='utf-8') as out:
            subprocess.run([sys.executable, '-I', f'{REPO}/tools/data/leipzig_sentences.py', '--heldout', self.p(f'{self.lang}.heldout.txt')]
                           + self.corpus_files, stdout=out, check=True)
        log('training text', sum(1 for _ in open(self.p('train.txt'), encoding='utf-8')), 'lines')

    # 3
    def lexicon(self):
        out = self.p(f'{self.lang}.lexicon.tsv')
        if os.path.exists(out): return
        wf = f'{WORDFREQ}/large_{self.lang}.msgpack.gz'
        if not os.path.exists(wf): wf = f'{WORDFREQ}/small_{self.lang}.msgpack.gz'
        args = ['--wordfreq', wf] if os.path.exists(wf) else ['--min-corpus', '3']
        # every corpus form used 3 times joins (MIN_CORPUS_FORM overrides, 0 = off): measured on each language's original
        # sets (2026-11-23): Turkish 63k -> 322k words, unknown text 11.2 -> 3.1 %, wrong 8.8 -> 8.5; Greek 46k -> 283k,
        # unknown 24 -> 1.5 %, wrong 17.1 -> 13.2; German wrong 6.7 -> 6.3; correct words broken unchanged in all three
        args += ['--min-corpus-form', os.environ.get('MIN_CORPUS_FORM', '3')]
        src = (f'wordfreq 3.1.1 (CC BY-SA 4.0) attested in ' if os.path.exists(wf) else 'counts of ') + \
              f'Leipzig {self.cfg["name"]} corpora (CC BY 4.0)'
        # a Hunspell dictionary (wooorm/dictionaries layout: <dir>/index.dic + .aff) adds every inflection it accepts: the
        # hand-built French list (519k words) beat a corpus-only one (201k: more wrong corrections, rare forms missing)
        # HUNSPELL=<dir> tries one without it being in the config. Not a general win: German and Polish measured worse with
        # theirs (wrong 6.1 -> 8.6 / 6.7 -> 7.0: rare forms catch typos, 2026-11-23); French better: per language only
        hun = self.cfg.get('hunspell') or ((os.environ['HUNSPELL'], 'licence not checked') if os.environ.get('HUNSPELL') else None)
        if hun:
            hdir, licence = hun
            args += ['--hunspell', f'{DATA}/hunspell/{hdir}/index']
            src = f'Hunspell {hdir} ({licence}), ' + src
        # elided forms as words (French l', d', qu'...): from the training text's counts
        if self.cfg.get('elision'):
            uni = self.p(f'{self.lang}.unigrams.tsv')
            if not os.path.exists(uni):
                c = Counter(w for line in open(self.p('train.txt'), encoding='utf-8') for w in line.split())
                with open(uni, 'w', encoding='utf-8') as f:
                    for w, n in c.most_common(): f.write(f'{w}\t{n}\n')
            args += ['--elision', self.cfg['elision'], '--unigrams', uni]
        cmd = ['taskset', '-c', CPU, 'nice', WF_PY if os.path.exists(WF_PY) else sys.executable, '-I',
               f'{REPO}/tools/data/build_lexicon_hunspell.py', '--lang', self.lang] + args
        for f in self.corpus_files: cmd += ['--corpus', f]
        sh(cmd + ['--out', out, '--review', self.p(f'{self.lang}.review.tsv'), '--source', src])

    # 4
    def ngrams(self):
        out = self.p(f'{self.lang}.ngrams.bin')
        if os.path.exists(out): return
        arpa = self.p(f'{self.lang}.arpa')
        if not os.path.exists(arpa):
            sh(f'taskset -c {CPU} nice {KENLM} -o 3 -S 3G -T {self.dir} --prune 0 0 1 < {self.p("train.txt")} > {arpa} 2> {self.p("lmplz.log")}')
        dgn2 = self.p(f'{self.lang}.ngrams.dgn2.bin')
        sh(['taskset', '-c', CPU, 'nice', sys.executable, '-I', f'{REPO}/tools/data/arpa_to_dogboard.py', arpa, dgn2,
            '--lexicon', self.p(f'{self.lang}.lexicon.tsv'), '--rank', 'entropy', '--keep-twins', '500000'])
        # the shipped file in the version-3 layout: the same model, 28 % smaller (tools/data/dgn3.py)
        sh(['taskset', '-c', CPU, 'nice', sys.executable, f'{REPO}/tools/data/dgn3.py', 'convert', dgn2, out])

    # 5
    def bigrams(self):
        out = self.p(f'{self.lang}.bigrams.tsv')
        if os.path.exists(out): return
        code = self.cfg['tatoeba']; os.makedirs(TATOEBA, exist_ok=True)
        tsv = f'{TATOEBA}/{code}_sentences.tsv'
        if not os.path.exists(tsv):
            try:
                urllib.request.urlretrieve(TATOEBA_URL.format(code), tsv + '.bz2'); sh(['bunzip2', '-f', tsv + '.bz2'])
            except Exception as e:
                log('no Tatoeba for', code, e)
        if os.path.exists(tsv) and sum(1 for _ in open(tsv, encoding='utf-8')) >= 2000:
            sh([sys.executable, '-I', f'{REPO}/tools/data/bigrams_from_tatoeba.py', tsv, '--lexicon',
                self.p(f'{self.lang}.lexicon.tsv'), '--out', out, '--lang', self.lang])
        else:
            # too little Tatoeba: word pairs from the corpus training text instead (same format)
            lex = {l.split('\t')[0].lower() for l in open(self.p(f'{self.lang}.lexicon.tsv'), encoding='utf-8') if not l.startswith('#')}
            pairs = Counter(); W = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*")
            for i, line in enumerate(open(self.p('train.txt'), encoding='utf-8')):
                if i >= 400000: break
                ws = ['<s>'] + [w.lower() for w in W.findall(line)]
                for a, b in zip(ws, ws[1:]):
                    if (a == '<s>' or a in lex) and b in lex: pairs[(a, b)] += 1
            with open(out, 'w', encoding='utf-8') as o:
                o.write('#dogboard-bigrams 1\n')
                for (a, b), c in pairs.most_common(200000):
                    if c >= 2: o.write(f'{a}\t{b}\t{c}\n')
            log('bigrams from the corpus (Tatoeba too small)')

    # 6
    def charlm(self):
        out = self.p(f'{self.lang}.charlm.bin')
        if os.path.exists(out): return
        lexf = self.p(f'{self.lang}.lexicon.tsv')
        words = [l.split('\t')[0].lower() for l in open(lexf, encoding='utf-8') if not l.startswith('#')][:50000]
        alphabet = ''.join(sorted({fold(c) for w in words for c in w if c.isalpha()}, key=lambda c: -sum(w.count(c) for w in words[:5000])))
        alphabet = ''.join(c for c in alphabet if c.isalpha())[:60]
        uni = self.p(f'{self.lang}.unigrams.tsv')
        if not os.path.exists(uni):
            cnt = Counter(); W = re.compile(r"[^\W\d_]+")
            for line in open(self.p('train.txt'), encoding='utf-8'): cnt.update(w.lower() for w in W.findall(line))
            with open(uni, 'w', encoding='utf-8') as o:
                for w, c in cnt.most_common(): o.write(f'{w}\t{c}\n')
        latin_az = set(alphabet) <= set('abcdefghijklmnopqrstuvwxyz')
        sh([sys.executable, '-I', f'{REPO}/tools/data/build_charlm.py', uni, lexf, self.p(f'{self.lang}.charlm.tsv'),
            '--min-count', '5', '--binary', out] + ([] if latin_az else ['--alphabet', alphabet]))

    # 7
    def rules(self):
        out = self.p('rules.json')
        if os.path.exists(out): return
        src, name = self.cfg['layout']
        rows = None
        if src == 'gboard':
            langs = json.load(open(f'{LAYOUTS}/gboard/languages.json')); lays = json.load(open(f'{LAYOUTS}/gboard/layouts.json'))
            v = next(v for v in langs[self.cfg.get('gboard_tag', self.lang)]['variants'] if v['variant'] == name)
            lid = v['layout_id'] or next((x['layout_id'] for l in langs.values() for x in l['variants']
                                          if x['variant'] == name and x.get('layout_id')), '')
            # the extracted data has no id for some variants (fr-FR azerty, 2026-11-23): the same-named variant of another
            # language (fr-CA's azerty, p4q: the same rows)
            rows = lays[lid]['rows']
        else:
            rows = json.load(open(f'{LAYOUTS}/ios/layouts.json'))[name]['rows']
        letters = [''.join(k for k in r if isinstance(k, str) and len(k) == 1 and k.isalpha()) for r in rows]
        letters = [r for r in letters if len(r) >= 5][:3]
        # a key that types two letters (Arabic ﻻ = ل + ا, a presentation form) can't be one decoder key: typed as two
        letters = [''.join(c for c in r if not ('\ufb50' <= c <= '\ufeff')) for r in letters]
        # a letter with its own key that folds to another letter of the layout (å ä ö in Finnish) stays distinct;
        # letters typed by long-press (Czech č ř ž on qwertz) fold, as accents do
        on_keys = set(''.join(letters))
        distinct = ''.join(sorted(c for c in on_keys if fold(c) != c and fold(c) in on_keys))
        w = max(len(r) for r in letters)
        rules = {'sentence_end': '.!?' + ('؟' if self.cfg.get('rtl') else ''), 'compounds': self.cfg.get('compounds', False),
                 'letter_rows': {'rows': letters, 'offsets': [round((w - len(r)) / 2 * 2) / 2 for r in letters]},
                 'distinct_letters': distinct}
        # letters also typed as two (German "fuer" for "für", TypingEngine.digraphCandidates)
        if self.cfg.get('digraphs'): rules['digraphs'] = self.cfg['digraphs']
        # typography no corpus shows (French: a space before ? ! : ;, « » quotes), as the hand-built packs had it
        rules.update(self.cfg.get('rules_extra', {}))
        json.dump(rules, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        log('rows', letters, 'distinct', distinct or '-')

    # 8
    def tests(self):
        if glob.glob(self.p(f'{self.lang}-typos.tsv')): return
        rows = json.load(open(self.p('rules.json')))['letter_rows']['rows']
        env = dict(os.environ, SIM_ROWS=','.join(rows), SIM_PREFIX=self.lang, SIM_LETTERS=''.join(c for c in letters_of(self.lang) if c.islower() or not c.isupper()))
        sh([sys.executable, f'{PREDBENCH}/make_es_sets.py', self.p(f'{self.lang}.heldout.txt'), self.p(f'{self.lang}.lexicon.tsv'),
            f'{PREDBENCH}/typo_model7.json', self.dir, str(self.n)], env=env, cwd=PREDBENCH)

    # 9
    def snapshots(self):
        if not os.path.exists(self.p(f'{self.lang}-lexicon.dgz')):
            env = dict(os.environ, DOGBOARD_TEST_HEAP='3g', DOGBOARD_EXPORT_PACK_DIR=self.dir, DOGBOARD_EXPORT_PACK_LANG=self.lang,
                       ANDROID_USER_HOME=os.path.expanduser('~/.config/.android'))
            sh(['taskset', '-c', '10-15', 'nice', './gradlew', '--no-daemon', '--max-workers=5', ':app:testDebugUnitTest',
                '--tests', 'io.github.sillypuppyy.dogboard.engine.SnapshotExportTest', '--rerun', '-q'], env=env, cwd=REPO)
        files = {'lexicon': f'{self.lang}.lexicon.tsv', 'bigrams': f'{self.lang}.bigrams.tsv', 'ngrams': f'{self.lang}.ngrams.bin',
                 'charlm': f'{self.lang}.charlm.bin', 'rules': 'rules.json', 'snapshot_lexicon': f'{self.lang}-lexicon.dgz',
                 'snapshot_bigrams': f'{self.lang}-bigrams.dgz', 'snapshot_ngramindex': f'{self.lang}-ngramindex.dgz'}
        man = {'format': 1, 'tag': self.lang, 'name': self.cfg['name'], 'script': self.cfg['script'], 'version': time.strftime('%Y.%m.%d'),
               'files': {}, 'sources': [
                   {'name': f'Leipzig Corpora Collection, {self.cfg["name"]}', 'licence': 'CC BY 4.0', 'for': ['lexicon', 'ngrams', 'charlm']},
                   {'name': 'wordfreq 3.1.1', 'licence': 'CC BY-SA 4.0', 'for': ['lexicon']},
                   {'name': 'Tatoeba', 'licence': 'CC BY 2.0 FR', 'for': ['bigrams']}]}
        for k, f in files.items():
            path = self.p(f)
            if os.path.exists(path):
                man['files'][k] = {'path': f, 'size': os.path.getsize(path), 'sha256': hashlib.sha256(open(path, 'rb').read()).hexdigest()}
        json.dump(man, open(self.p('manifest.json'), 'w'), ensure_ascii=False, indent=1)
        log('manifest', list(man['files']))

    # 10
    def eval(self):
        rows = json.load(open(self.p('rules.json')))
        report = [f'# {self.cfg["name"]} pack ({self.lang}), built by tools/i18n/auto_pack.py {time.strftime("%Y-%m-%d %H:%M")}', '',
                  f'Letter rows: {" / ".join(rows["letter_rows"]["rows"])}; distinct letters: {rows["distinct_letters"] or "none"}', '',
                  '| set | cases | fixed | left | wrong | broken |', '|---|---|---|---|---|---|']
        broken_high = None
        for s in ('typos', 'accents-clear', 'accents-twin', 'controls'):
            f = self.p(f'{self.lang}-{s}.tsv')
            if not os.path.exists(f) or os.path.getsize(f) == 0: continue
            env = dict(os.environ, DOGBOARD_TEST_HEAP='3g', DOGBOARD_PACK_DIR=self.dir, DOGBOARD_PACK_LANG=self.lang,
                       DOGBOARD_ROWS=','.join(rows['letter_rows']['rows']), DOGBOARD_SPACE_KEY='1', DOGBOARD_APP_PATH='1',
                       DOGBOARD_CHARLM='1', DOGBOARD_MISSPELLINGS='1', DOGBOARD_REAL_TYPOS=f, DOGBOARD_REAL_TYPOS_N=str(self.n),
                       DOGBOARD_NGRAMS=self.p(f'{self.lang}.ngrams.bin'), DOGBOARD_EVAL_THREADS='5',
                       DOGBOARD_COMPOUNDS='1' if self.cfg.get('compounds') else '0',
                       ANDROID_USER_HOME=os.path.expanduser('~/.config/.android'))
            # only the eval (other probe tests switch on with the same variables: NgramFileProbeTest is English-only)
            r = subprocess.run(['taskset', '-c', '10-15', 'nice', './gradlew', '--no-daemon', '--max-workers=5', ':app:testDebugUnitTest',
                                '--tests', 'io.github.sillypuppyy.dogboard.engine.RealTypoEvalTest', '--rerun', '-i'],
                               env=env, cwd=REPO, capture_output=True, text=True)
            m = re.search(r'(\d+) typos: fixed ([\d.]+|NaN)%, left ([\d.]+|NaN)%, wrong word ([\d.]+|NaN)% \| (\d+) correct words: '
                          r'kept (?:[\d.]+|NaN)%, broken ([\d.]+|NaN)%', r.stdout)
            if m:
                report.append(f'| {s} | {m.group(1) if m.group(1) != "0" else m.group(5)} | {m.group(2)} | {m.group(3)} | {m.group(4)} | {m.group(6)} |')
                log(s, m.group(0))
                if s == 'controls' and m.group(6) != 'NaN' and float(m.group(6)) > 0.5: broken_high = float(m.group(6))
            else:
                report.append(f'| {s} | eval failed | | | | |'); log(s, 'eval failed', r.stdout[-500:])
        if broken_high:
            # Basque at a quarter of its text broke 1.2 % of correct words (full text 0.2 %, like the other packs): too little
            # text; a stricter correction threshold barely helped (1.0 %, two points of fixed typos lost), 2026-11-23
            report += ['', f'NOT READY TO SHIP: {broken_high} % of correct words broken (at most 0.5 %): read the list (DOGBOARD_EVAL_ERRORS); '
                       'unknown real words want more text, restored accents (Greek tonos, Arabic hamza) are a policy question']
        sizes = {k: v['size'] for k, v in json.load(open(self.p('manifest.json')))['files'].items()}
        report += ['', 'Download size without the text lists: %.1f MB' % (sum(v for k, v in sizes.items() if k not in ('lexicon', 'bigrams')) / 1e6)]
        open(self.p('report.md'), 'w').write('\n'.join(report) + '\n')
        print('\n'.join(report))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('lang', choices=sorted(LANGS))
    ap.add_argument('--through', default='eval', choices=STEPS)
    ap.add_argument('--n', type=int, default=2000)
    a = ap.parse_args()
    pack = Pack(a.lang, a.n)
    for step in STEPS:
        if step != 'corpora' and not hasattr(pack, 'corpus_files'): pack.corpora()
        log('==', step); getattr(pack, step)()
        if step == a.through: break


if __name__ == '__main__':
    main()
