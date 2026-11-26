#!/usr/bin/env python3
"""Bootstraps a NEW language's pack from its code, end to end, into packs-work/<lang> (never the app's assets; the
spec-driven tools/i18n/build_pack.py stays the tool for shipped packs and their hand-tuned files). Builds a DogBoard
language pack from a language code ("a script that just gets a
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

Options (all off by default, the default build is unchanged):
  --work DIR      build in DIR instead of packs-work/<lang>
  --from DIR      seed DIR from an older pack folder first: its held-out text, training text, lexicon, bigrams, rules and test
                  sets are copied and its clean-* corpora linked, so only what depends on the text is rebuilt (the older
                  pack's inputs are reproduced, not guessed; a hand-tuned lexicon stays as it is)
  --conv          step 2 appends a conversational slice to train.txt (variant "D" of notes/research/conversational-sources.md):
                  FineWeb-2 (ODC-By) documents ranked by first/second-person pronouns and question marks, fetched by
                  tools/data/conv_fw2_slice.py (needs LANGS[lang]["fw2"] and a pronoun list in conv_common.py), cleaned like the
                  Leipzig text (same letter filter and tokens), minus the hosts in LANGS[lang]["conv_drop_hosts"], minus every line
                  that is a held-out sentence (Leipzig held-out, Tatoeba ids ending in 0, local chat test sets); --conv-share
                  (default 0.4) x the old line count are added. The lexicon stays from the Leipzig corpora (casing needs written
                  text); the n-grams, the unigram counts and the letter model see the added text.
  --conv-fetch-only   download the slice, print its top hosts, stop (to look at the hosts before adding the text)
  --only STEP     run just that step (eval of an already built folder)
Environment: PACK_CPUS (cores of lmplz / the lexicon, default 3-6), PACK_GRADLE_CPUS (default 10-15), PACK_GUARD=1 (before each
heavy command wait until `free -g` available >= 8 GB and the CPU is below 88 C).
LANGS holds what can't be derived (corpus names, Tatoeba code, layout choice); everything else is computed."""
import argparse, glob, gzip, hashlib, json, shutil, math, os, re, subprocess, sys, tarfile, time, unicodedata, urllib.request, zlib
from collections import Counter

# a letter, or a mark belonging to one (Devanagari ि ा ्): Python's \w has no marks, so किताब split into क त ब
# (the Hindi pack's text, 2026-11-23)
L = '(?:[^\\W\\d_]|[' + ''.join(chr(c) for c in range(0x300, 0x10000) if unicodedata.category(chr(c))[0] == 'M') + '])'

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
GRADLE_CPU = os.environ.get('PACK_GRADLE_CPUS', '10-15')
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
               layout=('gboard', 'qwerty'), gboard_tag='vi-VN', script='Latn', compounds=False,
               # Telex (language-families 36): shapes as doubled letters or a w (ươ as "uow": dduowcj = được), the tone as
               # a key at the syllable's end; marks are typed, so a bare syllable stays bare (typed_marks)
               rules_extra={'typed_marks': True, 'spellings': [
                   {'letter': l, 'typed': t} for l, t in [('ươ', 'uow'), ('â', 'aa'), ('ă', 'aw'), ('ê', 'ee'), ('ô', 'oo'), ('ơ', 'ow'), ('ư', 'uw'), ('đ', 'dd')]
               ] + [{'mark': m, 'typed': t, 'at': 'end'} for m, t in [('\u0301', 's'), ('\u0300', 'f'), ('\u0309', 'r'), ('\u0303', 'x'), ('\u0323', 'j')]]}),
    # Korean (2-set): words along their jamo keys (rules path "hangul": the word list, snapshots and eval in jamo space)
    'ko': dict(name='Korean', leipzig=['kor_news_2022_1M', 'kor_news_2020_1M', 'kor_wikipedia_2021_1M'], tatoeba='kor',
               layout=('gboard', 'korean_two_bulsik'), gboard_tag='ko', script='Kore', compounds=False,
               rules_extra={'path': 'hangul', 'key_of': dict(zip('ㄲㄸㅃㅆㅉㅒㅖ', 'ㄱㄷㅂㅅㅈㅐㅔ'))}),
    # Hindi (Gboard's compact layout): words along the keys Gboard's Devanagari rules take them from (rules path)
    'hi': dict(name='Hindi', leipzig=['hin_news_2022_1M', 'hin_news_2020_1M', 'hin_wikipedia_2021_1M'], tatoeba='hin',
               layout=('gboard', 'hindi_compact'), gboard_tag='hi', script='Deva', compounds=False,
               rules_extra={'path': 'rules:devanagari', 'sentence_end': '.!?।'}),
    'pt': dict(name='Portuguese', leipzig=['por_news_2024_1M', 'por_news_2023_1M', 'por_wikipedia_2021_1M'], tatoeba='por',
               layout=('gboard', 'qwerty'), gboard_tag='pt-BR', script='Latn', compounds=False),
    # Romance with elisions (l'acqua, dell'anno: apostrophe words) and final accents (città, perché) by long-press
    'it': dict(name='Italian', leipzig=['ita_news_2024_1M', 'ita_news_2023_1M', 'ita_wikipedia_2021_1M'], tatoeba='ita',
               layout=('gboard', 'qwerty'), gboard_tag='it-IT', script='Latn', compounds=False),
    'tr': dict(name='Turkish', leipzig=['tur_news_2024_1M', 'tur_news_2022_1M', 'tur_wikipedia_2021_1M'], tatoeba='tur',
               layout=('gboard', 'qwerty'), gboard_tag='tr-TR', script='Latn', compounds=False),
    # 2026-11-24 night: five more European languages (layouts from Gboard; corpora checked to exist)
    'nl': dict(name='Dutch', leipzig=['nld_news_2024_1M', 'nld_news_2022_1M', 'nld_wikipedia_2021_1M'], tatoeba='nld',
               layout=('gboard', 'qwerty'), gboard_tag='nl-NL', script='Latn', compounds=True),
    'sv': dict(name='Swedish', leipzig=['swe_news_2022_1M', 'swe_wikipedia_2021_1M'], tatoeba='swe',
               layout=('gboard', 'nordic'), gboard_tag='sv-SE', script='Latn', compounds=True),
    'uk': dict(name='Ukrainian', leipzig=['ukr_news_2024_1M', 'ukr_news_2022_1M', 'ukr_wikipedia_2021_1M'], tatoeba='ukr',
               layout=('gboard', 'ukrainian'), gboard_tag='uk-UA', script='Cyrl', compounds=False),
    'ro': dict(name='Romanian', leipzig=['ron_news_2024_1M', 'ron_news_2022_1M', 'ron_wikipedia_2021_1M'], tatoeba='ron',
               layout=('gboard', 'qwerty'), gboard_tag='ro-RO', script='Latn', compounds=False),
    'hu': dict(name='Hungarian', leipzig=['hun_news_2024_1M', 'hun_news_2022_1M', 'hun_wikipedia_2021_1M'], tatoeba='hun',
               layout=('gboard', 'hungarian'), gboard_tag='hu-HU', script='Latn', compounds=True),
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
               layout=('gboard', 'persian'), gboard_tag='fa-IR', script='Arab', compounds=False, rtl=True,
               normalize={'ي': 'ی', 'ى': 'ی', 'ك': 'ک'}),
    # Slavic in Cyrillic; the phonetic layout (Russian x Bulgarian on a Bulgarian phonetic keyboard): no
    # ы э ё keys, which Russian needs (the multilingual pass)
    'bg': dict(name='Bulgarian', leipzig=['bul_news_2022_1M', 'bul_wikipedia_2021_1M'], tatoeba='bul',
               layout=('gboard', 'bulgarian_phonetic'), gboard_tag='bg-BG', script='Cyrl', compounds=False),
    'ar': dict(name='Arabic', leipzig=['ara_news_2022_1M', 'ara_wikipedia_2021_1M'], tatoeba='ara',
               layout=('gboard', 'arabic'), script='Arab', compounds=False, rtl=True),
    # 2026-11-25: 23 more languages (batch 8); layouts from Gboard's languages.json, corpora checked to exist
    'ca': dict(name='Catalan', leipzig=['cat_wikipedia_2021_1M', 'cat_newscrawl_2016_1M', 'cat_news_2022_300K'], tatoeba='cat',
               layout=('gboard', 'spanish'), gboard_tag='ca', script='Latn', compounds=False),
    'da': dict(name='Danish', leipzig=['dan_news_2020_1M', 'dan_newscrawl_2017_1M', 'dan_wikipedia_2021_1M'], tatoeba='dan',
               layout=('gboard', 'nordic'), gboard_tag='da-DK', script='Latn', compounds=True),
    'nb': dict(name='Norwegian Bokmål', leipzig=['nor_wikipedia_2021_1M', 'nor_wikipedia_2016_1M', 'nor_news_2023_300K'], tatoeba='nob',
               layout=('gboard', 'nordic'), gboard_tag='nb-NO', script='Latn', compounds=True, cldr='no'),
    'sk': dict(name='Slovak', leipzig=['slk_newscrawl_2016_1M', 'slk_wikipedia_2021_300K', 'slk_news_2020_100K'], tatoeba='slk',
               layout=('gboard', 'qwerty'), gboard_tag='sk-SK', script='Latn', compounds=False),
    'sl': dict(name='Slovenian', leipzig=['slv_news_2020_1M', 'slv_newscrawl_2016_1M', 'slv_wikipedia_2021_1M'], tatoeba='slv',
               layout=('gboard', 'qwerty'), gboard_tag='sl-SI', script='Latn', compounds=False),
    'hr': dict(name='Croatian', leipzig=['hrv_news_2020_1M', 'hrv_newscrawl_2016_1M', 'hrv_wikipedia_2021_1M'], tatoeba='hrv',
               layout=('gboard', 'croatian'), gboard_tag='hr-HR', script='Latn', compounds=False),
    'sr': dict(name='Serbian', leipzig=['srp_wikipedia_2021_1M', 'srp_wikipedia_2016_1M'], tatoeba='srp',
               layout=('gboard', 'serbian'), gboard_tag='sr-Cyrl-RS', script='Cyrl', compounds=False),
    'lt': dict(name='Lithuanian', leipzig=['lit_news_2020_1M', 'lit_newscrawl_2016_1M', 'lit_wikipedia_2021_300K'], tatoeba='lit',
               layout=('gboard', 'qwerty'), gboard_tag='lt-LT', script='Latn', compounds=False),
    'lv': dict(name='Latvian', leipzig=['lav_newscrawl_2016_1M', 'lav_news_2020_300K'], tatoeba='lvs',
               layout=('gboard', 'qwerty'), gboard_tag='lv-LV', script='Latn', compounds=False),
    'et': dict(name='Estonian', leipzig=['est_newscrawl_2017_1M', 'est_newscrawl_2016_1M', 'est_news_2022_300K'], tatoeba='est',
               layout=('gboard', 'estonian'), gboard_tag='et-EE', script='Latn', compounds=True),
    'bn': dict(name='Bengali', leipzig=['ben_newscrawl_2017_1M', 'ben_wikipedia_2021_1M', 'ben_news_2020_300K'], tatoeba='ben',
               layout=('gboard', 'bengali_india'), gboard_tag='bn-IN', script='Beng', compounds=False,
               rules_extra={'path': 'rules:bengali', 'sentence_end': '.!?।'}),
    'ta': dict(name='Tamil', leipzig=['tam_wikipedia_2021_1M', 'tam_news_2020_100K'], tatoeba='tam',
               layout=('gboard', 'tamil'), gboard_tag='ta-SG', script='Taml', compounds=False,
               rules_extra={'path': 'rules:tamil'}),
    'ur': dict(name='Urdu', leipzig=['urd_newscrawl_2016_1M', 'urd_wikipedia_2021_300K'], tatoeba='urd',
               layout=('gboard', 'urdu_in'), gboard_tag='ur-IN', script='Arab', compounds=False, rtl=True,
               letters_extra='ںئآؤ'),
    'sq': dict(name='Albanian', leipzig=['sqi_news_2020_1M', 'sqi_wikipedia_2021_300K'], tatoeba='sqi',
               layout=('gboard', 'albanian'), gboard_tag='sq-x-standard', script='Latn', compounds=False),
    'az': dict(name='Azerbaijani', leipzig=['aze_wikipedia_2021_1M', 'aze_wikipedia_2016_300K'], tatoeba='aze',
               layout=('gboard', 'azerbaijani'), gboard_tag='az-AZ', script='Latn', compounds=False),
    'kk': dict(name='Kazakh', leipzig=['kaz_newscrawl_2016_1M', 'kaz_wikipedia_2021_300K'], tatoeba='kaz',
               layout=('gboard', 'kazakh'), gboard_tag='kk-KZ', script='Cyrl', compounds=False),
    'mr': dict(name='Marathi', letters_extra='ँऍ़', leipzig=['mar_newscrawl_2016_1M', 'mar_news_2020_300K', 'mar_wikipedia_2021_300K'], tatoeba='mar',
               layout=('gboard', 'marathi_india'), gboard_tag='mr-IN', script='Deva', compounds=False,
               rules_extra={'path': 'rules:devanagari', 'sentence_end': '.!?।'}),
    'hy': dict(name='Armenian', leipzig=['hye_wikipedia_2021_1M', 'hye_wikipedia_2016_300K'], tatoeba='hye',
               layout=('gboard', 'armenian_armenia_phonetic'), gboard_tag='hy-AM', script='Armn', compounds=False),
    'bs': dict(name='Bosnian', leipzig=['bos_news_2020_300K', 'bos_wikipedia_2021_300K', 'bos_newscrawl_2017_100K'], tatoeba='bos',
               layout=('gboard', 'serbian_qwertz'), gboard_tag='bs-BA', script='Latn', compounds=False),
    'ms': dict(name='Malay', leipzig=['msa_newscrawl_2016_300K', 'msa_wikipedia_2021_300K', 'msa_wikipedia_2016_300K'], tatoeba='zsm',
               layout=('gboard', 'qwerty'), gboard_tag='ms-MY', script='Latn', compounds=False),
    'mk': dict(name='Macedonian', leipzig=['mkd_wikipedia_2021_300K', 'mkd_wikipedia_2016_300K', 'mkd_news_2020_100K'], tatoeba='mkd',
               layout=('gboard', 'macedonian'), gboard_tag='mk-MK', script='Cyrl', compounds=False),
    'gl': dict(name='Galician', leipzig=['glg_wikipedia_2021_300K', 'glg_wikipedia_2016_300K', 'glg_newscrawl_2016_100K'], tatoeba='glg',
               layout=('gboard', 'spanish'), gboard_tag='gl-ES', script='Latn', compounds=False),
    'af': dict(name='Afrikaans', letters_extra='áéèêëíîïóôöúûŉ', leipzig=['afr_wikipedia_2021_300K', 'afr_wikipedia_2016_300K'], tatoeba='afr',
               layout=('gboard', 'qwerty'), gboard_tag='af-ZA', script='Latn', compounds=False),
}
FW2_CONFIG = {'bg': 'bul_Cyrl', 'es': 'spa_Latn', 'ru': 'rus_Cyrl', 'de': 'deu_Latn', 'fr': 'fra_Latn', 'it': 'ita_Latn', 'pt': 'por_Latn',
              'pl': 'pol_Latn', 'cs': 'ces_Latn', 'nl': 'nld_Latn', 'tr': 'tur_Latn', 'sv': 'swe_Latn', 'ro': 'ron_Latn', 'uk': 'ukr_Cyrl',
              # 2026-11-25 second batch (config names listed from the dataset's data/ folder)
              'fi': 'fin_Latn', 'hu': 'hun_Latn', 'el': 'ell_Grek', 'id': 'ind_Latn', 'he': 'heb_Hebr', 'ar': 'arb_Arab', 'fa': 'fas_Arab',
              'hi': 'hin_Deva', 'vi': 'vie_Latn', 'ko': 'kor_Hang', 'ca': 'cat_Latn', 'da': 'dan_Latn', 'nb': 'nob_Latn', 'sk': 'slk_Latn',
              'hr': 'hrv_Latn', 'sr': 'srp_Cyrl', 'lt': 'lit_Latn', 'lv': 'lvs_Latn', 'et': 'ekk_Latn'}
for _l, _c in FW2_CONFIG.items(): LANGS[_l]['fw2'] = _c
# hosts left out of the conversational slice after reading its top 30 (adult, dating, gambling, personal-data sites), per language
CONV_DROP_HOSTS = {
    # read in the top 30 hosts of each slice (2026-11-25), beyond what conv_common.unwanted_hosts finds: a brothel listing,
    # a teen sex-and-dating Q&A, a love-and-sex advice forum, warez boards, a people-and-phone directory
    'de': ('6profis.de', 'planet-liebe.de', 'freesoft-board.to', 'digital-eliteboard.com'),
    'tr': ('kizlarsoruyor.com',),
    'sv': ('eniro.se',),
    # second batch (2026-11-25): a company register, a teen sex-and-dating Q&A (hu); gibberish word-salad spam and a stranger-chat site
    # (ar); an erotic-story site below the automatic share (fa)
    'hu': ('bisnode.hu', 'kamaszpanasz.hu', 'intimzona.hu', 'feromonx.hu'),
    # erotic-story, escort and gambling-spam hosts at 8 to 10 % explicit words, just under the automatic 10 % (hi)
    'hi': ('kamuktahub.in', 'pinkgoa.in', 'hottestseductive.com', 'desikahani.net', 'freeclassifiedwebsitess.com', 'turku-piter.ru',
           'agentsofishq.com', 'bienvenuechezleschtis-lefilm.com'),
    'ar': ('ar.whatschat.org', 'theiu.org', 'sejrup-it.dk', 'macfixer.co.uk'),
    'fa': ('shahvani.com', 'fa.videochat.world'),
    # lottery-tip / online-casino hosts just under the automatic share (vi, nb), a machine-translated relationship-advice site (da)
    'vi': ('win2888asia.com', 'soicaumienphi.org', 'soicau3s.me'),
    'nb': ('spinpalace.com',),
    # word-salad spam (ko)
    'ko': ('whooskin.kr',),
    # machine-translated dating-advice spam (ca)
    # a web shop's boilerplate (31k lines, 2 % of the slice) and an erotic-story site (da)
    'da': ('gucca.dk', 'pov.international', 'da.whattalking.com'),
    # a machine-translated relationship-advice magazine (sr)
    'sr': ('sr.lifesizemagazine.com', 'gamblingpapa.com'),
    'lv': ('lv.lifesizemagazine.com',),
    'ca': ('ca.whattalking.com', 'ca.clarksbarandrestaurant.com', 'ca.enverpasadergisi.com'),
}
CLDR_URL = 'https://raw.githubusercontent.com/unicode-org/cldr/main/common/main/{}.xml'


def lower(word, lang):
    """Lowercase as the keyboard does (tools/data/leipzig_sentences.py lower: "İ" is "i", Turkish "I" is "ı")."""
    if lang in ('tr', 'az'):
        word = word.replace('I', 'ı')
    return word.replace('İ', 'i').lower()


def letters_of(lang):
    """The language's letters (CLDR's main exemplar characters, Unicode licence) in both cases, plus basic Latin for
    names and brands: a corpus sentence with any other letter is mis-encoded or another language ("tøeba" for
    "třeba" in Leipzig's Czech news, 2026-11-22)."""
    orig, lang = lang, LANGS.get(lang, {}).get('cldr', lang)  # nb's exemplar set is in no.xml
    path = f'{DATA}/cldr/main/{lang}.xml'
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True); urllib.request.urlretrieve(CLDR_URL.format(lang), path)
    m = re.search(r'<exemplarCharacters>\[([^\]]*)\]', open(path, encoding='utf-8').read())
    body = re.sub(r'\{[^}]*\}', '', m.group(1))
    # ranges ("가-힣": Korean lists its syllables so): every letter between the ends
    body = re.sub(r'(\S)-(\S)', lambda r: ''.join(chr(c) for c in range(ord(r.group(1)), ord(r.group(2)) + 1)), body)
    chars = {c for c in body if c.isalpha() or unicodedata.category(c) == 'Mn'}
    # letters CLDR's main set leaves out (Urdu's ں ئ آ ؤ sit in its auxiliary set: 95 % of the news was dropped without them)
    chars |= set(LANGS.get(orig, {}).get('letters_extra', ''))
    return chars | {c.upper() for c in chars} | set('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ')


STEPS = ['corpora', 'text', 'lexicon', 'ngrams', 'bigrams', 'charlm', 'rules', 'tests', 'snapshots', 'eval']


def log(*a): print(time.strftime('%H:%M:%S'), *a, flush=True)


def guard():
    """PACK_GUARD=1: wait until memory and temperature allow a heavy step (shared machine)."""
    if os.environ.get('PACK_GUARD') != '1': return
    while True:
        try:
            avail = int(subprocess.run(['free', '-g'], capture_output=True, text=True).stdout.splitlines()[1].split()[6])
            t = re.search(r'Tctl:\s*\+?([\d.]+)', subprocess.run(['sensors'], capture_output=True, text=True).stdout)
            temp = float(t.group(1)) if t else 0.0
        except Exception: avail, temp = 99, 0.0
        if avail >= 8 and temp < 88: return
        log(f'waiting: {avail} GB available, CPU {temp} C'); time.sleep(60)


def sh(cmd, **kw):
    guard()
    log('$', cmd if isinstance(cmd, str) else ' '.join(cmd))
    subprocess.run(cmd, shell=isinstance(cmd, str), check=True, **kw)


def fold(c):
    return unicodedata.normalize('NFD', c)[0]


# the letter model too: it judges whether a typed unknown word looks like a word, and one retrained on a conversational slice kept
# untoned Vietnamese ("gioi") as typed (tone restoration 29.9 -> 24.9 %; the older pack's letter model: 29.2 %, 2026-11-26)
SEED_COPY = ('{l}.heldout.txt', 'train.txt', '{l}.lexicon.tsv', '{l}.bigrams.tsv', 'rules.json', '{l}-typos.tsv', '{l}-accents-clear.tsv',
             '{l}-accents-twin.tsv', '{l}-controls.tsv', '{l}.charlm.bin', '{l}.charlm.tsv')


class Pack:
    def __init__(self, lang, n, work=None, seed=None, conv=False, conv_share=0.4):
        self.lang, self.cfg, self.n = lang, LANGS[lang], n
        self.conv, self.conv_share = conv, conv_share
        self.dir = work or f'{DATA}/packs-work/{lang}' + os.environ.get('PACK_SUFFIX', '')
        os.makedirs(self.dir, exist_ok=True)
        self.p = lambda name: os.path.join(self.dir, name)
        if seed: self.seed(seed)
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

    def seed(self, src):
        """Copies an older pack's text-independent inputs into this folder (missing files only) and links its clean-* corpora."""
        for pat in SEED_COPY:
            name = pat.format(l=self.lang)
            if os.path.exists(os.path.join(src, name)) and not os.path.exists(self.p(name)):
                shutil.copyfile(os.path.join(src, name), self.p(name))
        for name in os.listdir(src):
            if name.startswith('clean-') and not os.path.lexists(self.p(name)): os.symlink(os.path.join(src, name), self.p(name))

    def text_ok(self, text):
        """The corpus letter filter: no letter foreign to the language, no mostly-Latin text in a non-Latin script."""
        # computed once (setdefault(.., letters_of(..)) re-read and re-parsed the CLDR file for every line: Korean's 11,172-syllable range took 35+ minutes)
        if '_letters' not in self.__dict__: self._letters = letters_of(self.lang)
        ok = self._letters
        latin = self.cfg['script'] != 'Latn' and 2 * sum('a' <= c.lower() <= 'z' for c in text) > sum(c.isalpha() for c in text)
        # a letter written precomposed is checked by its parts: CLDR lists Bengali য় as য + ় and Armenian
        # և is ե + ւ (73 % of Bengali news and 46 % of Armenian Wikipedia were dropped, 2026-11-25)
        return not latin and all(c in ok or all(d in ok for d in unicodedata.normalize('NFKD', c))
                                 for c in text if c.isalpha() or unicodedata.category(c) == 'Mn')

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
        cleaned = []
        # letters written with another language's code point (Persian news in Arabic ي / ك for ی / ک: 20 % of one corpus
        # was dropped as foreign, 2026-11-23) replaced first
        norm = str.maketrans(self.cfg.get('normalize', {}))
        for f in files:
            out = self.p('clean-' + os.path.basename(f))
            if not os.path.exists(out):
                kept = dropped = 0
                with open(f, encoding='utf-8', errors='replace') as src, open(out + '.tmp', 'w', encoding='utf-8') as dst:
                    for line in src:
                        line = line.translate(norm)
                        text = line.rstrip('\n').split('\t')[-1]
                        # (text_ok: a Cyrillic pack drops the sentences written mostly in Latin: Serbian Wikipedia carries a few, 0.13 %)
                        if self.text_ok(text): dst.write(line); kept += 1
                        else: dropped += 1
                os.rename(out + '.tmp', out)
                log(f'{os.path.basename(f)}: kept {kept:,}, dropped {dropped:,} ({100 * dropped / max(1, kept + dropped):.1f} %) with foreign letters')
            cleaned.append(out)
        self.corpus_files = cleaned
        return cleaned

    # 2
    def text(self):
        if not os.path.exists(self.p('train.txt')):
            with open(self.p('train.txt'), 'w', encoding='utf-8') as out:
                subprocess.run([sys.executable, '-I', f'{REPO}/tools/data/leipzig_sentences.py', '--heldout', self.p(f'{self.lang}.heldout.txt'), '--lang', self.lang]
                               + self.corpus_files, stdout=out, check=True)
            log('training text', sum(1 for _ in open(self.p('train.txt'), encoding='utf-8')), 'lines')
        if self.conv: self.conv_text()

    def conv_fetch(self):
        """The FineWeb-2 chatty slice (conv_fw2_slice.py: 700 spread row groups, ~1.4 GB streamed, nothing stored but the output)."""
        d = self.p('conv')
        if not os.path.exists(f'{d}/chatty.txt'):
            guard()
            sh(['taskset', '-c', CPU, 'nice', sys.executable, '-I', f'{REPO}/tools/data/conv_fw2_slice.py', self.lang, self.cfg['fw2'], d,
                '--groups', os.environ.get('CONV_GROUPS', '700'), '--no-control'])
        return d

    def conv_text(self):
        """Appends the cleaned conversational slice to train.txt (once: conv-lines.txt records it)."""
        mark = self.p('conv-lines.txt')
        if os.path.exists(mark): return
        sys.path.insert(0, f'{REPO}/tools/data')
        from leipzig_sentences import WORD, SENTENCE_END
        d = self.conv_fetch(); lang = self.lang
        toks = lambda part: ' '.join(lower(w, lang).replace('’', "'") for w in WORD.findall(part))
        # sentences never to train on: the pack's held-out text, Tatoeba ids ending in 0 and the local chat test sets (WildChat,
        # oasst2, Aya prompts), as pack tokens
        test = set()
        def add(text):
            for part in SENTENCE_END.split(text):
                t = toks(part)
                if t: test.add(t)
        for l in open(self.p(f'{lang}.heldout.txt'), encoding='utf-8', newline='\n'): add(l.rstrip('\n'))
        tat = f'{TATOEBA}/{self.cfg["tatoeba"]}_sentences.tsv'
        if os.path.exists(tat):
            for l in open(tat, encoding='utf-8', newline='\n'):
                t = l.rstrip('\n').split('\t')
                if len(t) >= 3 and t[0].endswith('0'): add(t[-1])
        for src in ('wildchat', 'oasst2', 'aya-in'):
            f = f'{DATA}/conv-sources/local/{src}-{lang}.tsv'
            if os.path.exists(f):
                for l in open(f, encoding='utf-8', newline='\n'): add(l.rstrip('\n').partition('\t')[2])
        drop = tuple(CONV_DROP_HOSTS.get(lang, ())) + tuple(self.cfg.get('conv_drop_hosts', ()))
        drop = tuple(h.lower() for h in drop)
        # adult / gambling / dating hosts found by name or by their lines (conv_common.unwanted_hosts), listed in conv/dropped-hosts.tsv
        from conv_common import unwanted_hosts
        lines = [l.rstrip('\n') for l in open(f'{d}/chatty.txt', encoding='utf-8', newline='\n')]
        hostl = [l.rstrip('\n') for l in open(f'{d}/chatty.hosts', encoding='utf-8', newline='\n')]
        auto, counts = unwanted_hosts(lines, hostl)
        with open(f'{d}/dropped-hosts.tsv', 'w', encoding='utf-8') as o:
            for h, why in sorted(auto.items(), key=lambda x: -counts[x[0]]): o.write(f'{h}\t{counts[h]}\t{why}\n')
            for h in drop: o.write(f'{h}\t{counts.get(h, 0)}\tlisted\n')
        log(f'hosts dropped: {len(auto)} by name or content ({sum(counts[h] for h in auto):,} lines), {len(drop)} listed')
        dropset = set(auto) | set(drop)
        # Romanian's s and t with a comma below (ș ț) were long written with a cedilla (ş ţ), a third of the slice's: the
        # pack's text has only the comma forms, so the letter filter dropped those lines; written as the comma forms
        fix = str.maketrans({'ş': 'ș', 'ţ': 'ț', 'Ş': 'Ș', 'Ţ': 'Ț'}) if lang == 'ro' else None
        # informal text often leaves a language's accents out ("si" for "și"): 27 % of the Romanian slice's lines of 8+ words
        # had none, 8 % of its news, and the accent twins it restored fell from 86.4 to 76.2 %. Where the pack's own text
        # nearly always has them (under 15 % of long lines without), slice lines of 8+ words without any are left out
        marks = {c for c in letters_of(lang) if c.islower() and not ('a' <= c <= 'z')} if self.cfg['script'] == 'Latn' else set()
        def plain(line): return len(line.split()) >= 8 and not any(c in marks for c in line.lower())
        if marks:
            long_lines = n_plain_base = 0
            for l in open(self.p('train.txt'), encoding='utf-8', newline='\n'):
                if len(l.split()) < 8: continue
                long_lines += 1; n_plain_base += plain(l)
                if long_lines >= 200000: break
            if not long_lines or n_plain_base / long_lines >= 0.15: marks = set()
            else: log(f'accents: {100 * n_plain_base / long_lines:.1f} % of the text\'s long lines have none: slice lines without any left out')
        cand = []; n_host = n_letters = n_test = n_plain = 0; gone = {}
        def host_gone(host):
            if host not in gone: gone[host] = any(p in dropset for p in (host.split('.', i)[-1] for i in range(host.count('.') + 1)))
            return gone[host]
        if True:
            for line, host in zip(lines, hostl):
                if host_gone(host): n_host += 1; continue
                if fix: line = line.translate(fix)
                if not self.text_ok(line): n_letters += 1; continue
                if marks and plain(line): n_plain += 1; continue
                if line in test: n_test += 1; continue
                cand.append(line)
        import random
        random.Random(7).shuffle(cand)
        base = sum(1 for _ in open(self.p('train.txt'), encoding='utf-8', newline='\n'))
        k = min(len(cand), int(self.conv_share * base))
        log(f'conversational slice: {len(cand):,} lines usable ({n_host:,} dropped by host, {n_letters:,} foreign letters, {n_plain:,} without accents, {n_test:,} test sentences), adding {k:,} to {base:,}')
        tmp = self.p('train.txt.conv')
        shutil.copyfile(self.p('train.txt'), tmp)
        with open(tmp, 'a', encoding='utf-8', newline='\n') as o: o.writelines(l + '\n' for l in cand[:k])
        os.replace(tmp, self.p('train.txt'))
        open(mark, 'w').write(f'{base}\t{k}\t{len(cand)}\n')

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
            lex = {lower(l.split('\t')[0], self.lang) for l in open(self.p(f'{self.lang}.lexicon.tsv'), encoding='utf-8') if not l.startswith('#')}
            pairs = Counter(); W = re.compile(L + r"+(?:['’]" + L + r"+)*")
            for i, line in enumerate(open(self.p('train.txt'), encoding='utf-8')):
                if i >= 400000: break
                ws = ['<s>'] + [lower(w, self.lang) for w in W.findall(line)]
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
        words = [lower(l.split('\t')[0], self.lang) for l in open(lexf, encoding='utf-8') if not l.startswith('#')][:50000]
        alphabet = ''.join(sorted({fold(c) for w in words for c in w if c.isalpha()}, key=lambda c: -sum(w.count(c) for w in words[:5000])))
        alphabet = ''.join(c for c in alphabet if c.isalpha())[:60]
        uni = self.p(f'{self.lang}.unigrams.tsv')
        # the letter model learns from the written text only, not a conversational slice (--conv): it judges whether a typed
        # unknown word looks like a word, and chat's untoned and sloppy spellings made "gioi" look like one (Vietnamese tone
        # restoration 29.9 -> 24.9 %; with the written text's letter model 29.2 %, 2026-11-26)
        written = None
        if os.path.exists(self.p('conv-lines.txt')):
            written = int(open(self.p('conv-lines.txt')).read().split('\t')[0])
            uni = self.p(f'{self.lang}.unigrams-written.tsv')
        if not os.path.exists(uni):
            cnt = Counter(); W = re.compile(L + r"+")
            for i, line in enumerate(open(self.p('train.txt'), encoding='utf-8')):
                if written is not None and i >= written: break
                cnt.update(lower(w, self.lang) for w in W.findall(line))
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
        rows, longpress = None, {}
        if src == 'gboard':
            langs = json.load(open(f'{LAYOUTS}/gboard/languages.json')); lays = json.load(open(f'{LAYOUTS}/gboard/layouts.json'))
            v = next(v for v in langs[self.cfg.get('gboard_tag', self.lang)]['variants'] if v['variant'] == name)
            lid = v['layout_id'] or next((x['layout_id'] for l in langs.values() for x in l['variants']
                                          if x['variant'] == name and x.get('layout_id')), '')
            # the extracted data has no id for some variants (fr-FR azerty, 2026-11-23): the same-named variant of another
            # language (fr-CA's azerty, p4q: the same rows)
            rows = lays[lid]['rows']
            longpress = lays[lid].get('longpress', {})
        else:
            rows = json.load(open(f'{LAYOUTS}/ios/layouts.json'))[name]['rows']
        # a sign with its own key (Hindi ् ं) is typed like a letter
        letters = [''.join(k for k in r if isinstance(k, str) and len(k) == 1 and (k.isalpha() or unicodedata.category(k) in ('Mn', 'Mc')))
                   for r in rows]
        letters = [r for r in letters if len(r) >= 5][:3]
        # a key that types two letters (Arabic ﻻ = ل + ا, a presentation form) can't be one decoder key: typed as two
        letters = [''.join(c for c in r if not ('\ufb50' <= c <= '\ufeff')) for r in letters]
        # a letter with its own key that folds to another letter of the layout (å ä ö in Finnish) stays distinct;
        # letters typed by long-press (Czech č ř ž on qwertz) fold, as accents do
        on_keys = set(''.join(letters))
        # æ œ ß with a key of their own (Danish, Norwegian æ ø) are letters too: unmarked, the trie spelled æ as "ae", so
        # typing "stærke" on the æ key was read as the typo "starke" (Danish broke 1.15 % of correct words, 2026-11-25)
        distinct = ''.join(sorted(c for c in on_keys if (fold(c) != c and fold(c) in on_keys) or c in 'æœß'))
        w = max(len(r) for r in letters)
        rules = {'sentence_end': '.!?' + ('؟' if self.cfg.get('rtl') else ''), 'compounds': self.cfg.get('compounds', False),
                 'letter_rows': {'rows': letters, 'offsets': [round((w - len(r)) / 2 * 2) / 2 for r in letters]},
                 'distinct_letters': distinct}
        # letters with no key of their own, typed by long-press on another (Hindi compact भ on ब): folded to that key
        # (Lexicon.keyOf); letters an accent fold already reaches (é on e) left to it
        key_of = {}
        for k, alts in longpress.items():
            if len(k) != 1 or k not in on_keys: continue
            for c in alts.split(','):
                if len(c) == 1 and c.isalpha() and c not in on_keys and fold(c) == c and c not in key_of: key_of[c] = k
        if key_of: rules['key_of'] = key_of
        # letters also typed as two (German "fuer" for "für", TypingEngine.digraphCandidates)
        if self.cfg.get('digraphs'): rules['digraphs'] = self.cfg['digraphs']
        # typography no corpus shows (French: a space before ? ! : ;, « » quotes), as the hand-built packs had it
        rules.update(self.cfg.get('rules_extra', {}))
        json.dump(rules, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        log('rows', letters, 'distinct', distinct or '-')

    # 8
    def tests(self):
        if glob.glob(self.p(f'{self.lang}-typos.tsv')): return
        if json.load(open(self.p('rules.json'))).get('path'):
            # typed in key space (Korean jamo, Indic vowel keys): the sets as a typist's keys, made apart from the pack
            sub = self.p('typed')
            sh([sys.executable, '-I', f'{REPO}/tools/i18n/sim/telex_sets.py', self.dir, sub, str(self.n)])
            for kind in ('typos', 'controls'): shutil.copy(f'{sub}/{self.lang}-{kind}.tsv', self.p(f'{self.lang}-{kind}.tsv'))
            return
        rows = json.load(open(self.p('rules.json')))['letter_rows']['rows']
        key_of = json.load(open(self.p('rules.json'))).get('key_of', {})
        env = dict(os.environ, SIM_ROWS=','.join(rows), SIM_SHIFTED=''.join(a + b for a, b in key_of.items()), SIM_PREFIX=self.lang, SIM_LETTERS=''.join(c for c in letters_of(self.lang) if c.islower() or not c.isupper()))
        sh([sys.executable, f'{PREDBENCH}/make_es_sets.py', self.p(f'{self.lang}.heldout.txt'), self.p(f'{self.lang}.lexicon.tsv'),
            f'{PREDBENCH}/typo_model7.json', self.dir, str(self.n)], env=env, cwd=PREDBENCH)

    # 9
    def snapshots(self):
        if not os.path.exists(self.p(f'{self.lang}-lexicon.dgz')):
            env = dict(os.environ, DOGBOARD_TEST_HEAP='3g', DOGBOARD_EXPORT_PACK_DIR=self.dir, DOGBOARD_EXPORT_PACK_LANG=self.lang,
                       ANDROID_USER_HOME=os.path.expanduser('~/.config/.android'))
            sh(['taskset', '-c', GRADLE_CPU, 'nice', './gradlew', '--no-daemon', '--max-workers=5', ':app:testDebugUnitTest',
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
                       DOGBOARD_EVAL_ERRORS=self.p(f'{self.lang}-{s}.errors.tsv'),
                       ANDROID_USER_HOME=os.path.expanduser('~/.config/.android'))
            # only the eval (other probe tests switch on with the same variables: NgramFileProbeTest is English-only)
            guard()
            r = subprocess.run(['taskset', '-c', GRADLE_CPU, 'nice', './gradlew', '--no-daemon', '--max-workers=5', ':app:testDebugUnitTest',
                                '--tests', 'io.github.sillypuppyy.dogboard.engine.RealTypoEvalTest', '--rerun', '-i'],
                               env=env, cwd=REPO, capture_output=True, text=True)
            m = re.search(r'(\d+) typos: fixed ([\d.]+|NaN)%, left ([\d.]+|NaN)%, wrong word ([\d.]+|NaN)% \| (\d+) correct words: '
                          r'kept (?:[\d.]+|NaN)%, broken ([\d.]+|NaN)%', r.stdout)
            if m:
                report.append(f'| {s} | {m.group(1) if m.group(1) != "0" else m.group(5)} | {m.group(2)} | {m.group(3)} | {m.group(4)} | {m.group(6)} |')
                log(s, m.group(0))
                if s == 'controls' and m.group(6) != 'NaN':
                    # a correct word given its accents or hamza back is standard spelling restored, not broken
                    # (language-families.md, 2026-11-23): only the other changes count against the gate
                    errs = [l.rstrip('\n').split('\t') for l in open(self.p(f'{self.lang}-controls.errors.tsv'), encoding='utf-8')]
                    real = [e for e in errs if e[0] == 'broken' and fold_marks(e[3]) != fold_marks(e[2])]
                    pct = 100.0 * len(real) / int(m.group(5))
                    report[-1] += f' (restored accents / hamza: {len(errs) - len(real)}; other changes {pct:.2f} %)'
                    if pct > 0.5: broken_high = round(pct, 2)
            else:
                report.append(f'| {s} | eval failed | | | | |'); log(s, 'eval failed', r.stdout[-500:])
        if broken_high:
            # Basque at a quarter of its text broke 1.2 % of correct words (full text 0.2 %, like the other packs): too little
            # text; a stricter correction threshold barely helped (1.0 %, two points of fixed typos lost), 2026-11-23
            report += ['', f'NOT READY TO SHIP: {broken_high} % of correct words changed into other words (at most 0.5 %; restored '
                       f'accents and hamza not counted): read {self.lang}-controls.errors.tsv; unknown real words want more text']
        sizes = {k: v.get('size') or (os.path.getsize(self.p(v['path'])) if os.path.exists(self.p(v['path'])) else 0) for k, v in json.load(open(self.p('manifest.json')))['files'].items()}
        report += ['', 'Download size without the text lists: %.1f MB' % (sum(v for k, v in sizes.items() if k not in ('lexicon', 'bigrams')) / 1e6)]
        open(self.p('report.md'), 'w').write('\n'.join(report) + '\n')
        print('\n'.join(report))


def fold_marks(w):
    """A word without its accents and hamza: what someone typing without them would type."""
    w = w.translate(str.maketrans({'أ': 'ا', 'إ': 'ا', 'آ': 'ا', 'ؤ': 'و', 'ئ': 'ي'}))
    return ''.join(c for c in unicodedata.normalize('NFD', w) if not unicodedata.combining(c))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('lang', choices=sorted(LANGS))
    ap.add_argument('--through', default='eval', choices=STEPS)
    ap.add_argument('--n', type=int, default=2000)
    ap.add_argument('--work'); ap.add_argument('--from', dest='seed'); ap.add_argument('--only', choices=STEPS)
    ap.add_argument('--conv', action='store_true'); ap.add_argument('--conv-share', type=float, default=0.4)
    ap.add_argument('--conv-fetch-only', action='store_true')
    a = ap.parse_args()
    pack = Pack(a.lang, a.n, a.work, a.seed, a.conv or a.conv_fetch_only, a.conv_share)
    if a.conv_fetch_only:
        d = pack.conv_fetch()
        print(open(f'{d}/hosts.tsv', encoding='utf-8').read()[:3000]); return
    if a.only:
        if a.only != 'eval': pack.corpora()
        log('==', a.only); getattr(pack, a.only)(); return
    for step in STEPS:
        if step != 'corpora' and not hasattr(pack, 'corpus_files'): pack.corpora()
        log('==', step); getattr(pack, step)()
        if step == a.through: break


if __name__ == '__main__':
    main()
