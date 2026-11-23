#!/usr/bin/env python3
"""Builds DogBoard's English (US) lexicon from open data.

    tools/data/build_lexicon.py --data DATA_DIR [--max-level 80] [--max-words N] [--out PATH]

DATA_DIR (not in the repo) holds the downloaded sources:
    scowl/scowl-2020.12.07/final/    SCOWL word lists (http://wordlist.aspell.net/), size levels 10..95
    wordfreq/whl/wordfreq/data/large_en.msgpack.gz   wordfreq 3.1.1 frequencies (CC BY-SA 4.0)
    wikidata/*.tsv                   companies, brands, websites from tools/data/fetch_wikidata_names.py (CC0)
    census/1990/dist.{male,female}.first   US Census 1990 first names (public domain)
    census/surnames2010/Names_2010Census.csv  US Census 2010 surnames (public domain)
    geonames/cities15000.txt, countryInfo.txt, admin1CodesASCII.txt   GeoNames (CC BY 4.0)
    wikipedia/common_misspellings.txt  Wikipedia's machine-readable common misspellings (CC BY-SA)

Which words: SCOWL en_US lists up to --max-level (spelling, inflections, capitals), plus frequent
words SCOWL lacks (slang, brands) from wordfreq, plus single-word names, places and brands from
Wikidata, people's names from the US Census and places from GeoNames. Common misspellings and apostrophe-less contraction spellings ("dont") are left out so
autocorrect fixes them. Possessives ("dog's") are kept only when frequent; the rest is for the
engine to generate.

How often: wordfreq's frequency (Zipf scale, log10 per billion words) where it has the word;
otherwise a floor by SCOWL level or by Wikidata popularity, always below what wordfreq measures.
Counts in the output are 10^(zipf + 1), rounded.

Casing: the lowercase form when the word is an ordinary word (apple), else the proper form
(Paris, LinkedIn, NASA).

Output: DogBoard lexicon format v1 (see lexicon_from_florisboard.py), sorted by count.
"""
import argparse
import gzip
import bisect
import math
import os
import re
import sys
from collections import defaultdict

import msgpack

SCOWL_REGIONS = ("english", "american")
SCOWL_KINDS = ("words", "upper", "proper-names", "abbreviations", "contractions")
# Zipf floor for SCOWL words wordfreq does not have, by SCOWL size level (rarer level, lower floor)
LEVEL_FLOOR = [(35, 1.0), (50, 0.9), (60, 0.8), (70, 0.7), (80, 0.6), (95, 0.5)]
# wordfreq-only words (not in SCOWL or Wikidata) need at least this Zipf to be added
# short words only web text has (--short-word-filter): written in lowercase less than this share of
# the time, they are acronyms or names stored in the wrong case and take their usual written form
SHORT_MIN_LOWERCASE_SHARE = 0.10
# units the short-word filter never removes
UNITS = {"Hz", "Pa", "Wb", "Gy", "Sv", "Bq", "Np", "Da", "Gb", "Mb", "Kb"}
MIN_ZIPF_WEB_ONLY = 3.0
# possessives are kept only from this Zipf up
MIN_ZIPF_POSSESSIVE = 2.5
# a name or place takes wordfreq's frequency of its lowercase form, but at most this (the lowercase
# count also includes other uses and typos: "teh" as a surname)
NAME_ZIPF_CAP = 4.0
# hand-reviewed common words below the web-only cut (en.extra_words.txt), and their floor
EXTRA_WORDS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "en.extra_words.txt")
EXTRA_ZIPF_FLOOR = 1.5
# a lowercase word from this SCOWL level up loses to a capitalised form of the same letters
LOWERCASE_RARE_LEVEL = 35
# drop a plain spelling when its contraction is this much more frequent ("dont" vs "don't")
CONTRACTION_GAP = 0.8
# ...unless the plain spelling is a SCOWL word up to this level
CONTRACTION_KEEP_LEVEL = 20
# ...except these, rarely meant as typed (iOS leaves them out too): "cant" is can't, "hes" he's
ALWAYS_CONTRACTION = {"cant", "hes"}
# letters include Latin-1 accented ones (café, naïve); the keyboard types them on the plain key
WORD_RE = re.compile(r"^[A-Za-zÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ'’.-]*$")
# SCOWL's other-region lists (and their variants); plain variant_N lists are American variants
FOREIGN_REGIONS = ("british", "british_z", "canadian", "australian")
# American variant spellings (theatre, cancelled, judgement) need at least this Zipf
MIN_ZIPF_US_VARIANT = 2.0
# names from the web corpus (--web-names): brands, apps and sites newer than wordfreq's text (Canva, Unsplash).
# A Wikidata item's one-word name (--wikidata-wide) needs this Zipf in the corpus, a name the corpus alone
# shows needs more; either must be written capitalised this share of the time (a name, not a word)
MIN_ZIPF_WIKIDATA_NAME = 1.8
MIN_ZIPF_WEB_NAME = 2.5
MIN_NAME_CAPITAL_SHARE = 0.8
# all-capital names (call signs, codes) only when common: rare ones took typos' corrections ("kyou" -> KHOU,
# "N xt" -> NZXT; 2026-11-19 benchmark)
MIN_ZIPF_CAPS_NAME = 2.8
# corpus-only names: plain letters (no mojibake like "Iím") and at most one lower-to-upper step (SwiftKey,
# iFixit; not glued page text like "ArticlePubMedGoogle")
WEB_NAME_RE = re.compile(r"^[A-Za-z]+$")
# hyphenated words (--hyphenated, count_hyphenated.py): from this Zipf in the counted text, in their usual casing
# (iOS's en_US lexicon has ~1000: brother-in-law, check-in, T-shirt; SCOWL's lists have none)
MIN_ZIPF_HYPHENATED = 3.0
# an inflected form of a hyphenated word that is in (fine-tune -> fine-tuned, fine-tuning; follow-up -> follow-ups) from
# this lower Zipf: the forms are rarer than the base (fine-tuning 2.85 missed 3.0); one written closed too (re-used /
# reused) stays out, as re-open did (it "corrected" reopen at a general 2.5, d6e4dd4)
MIN_ZIPF_HYPHENATED_FORM = 2.0
HYPHENATED_RE = re.compile(r"^[A-Za-zÀ-ÖØ-öø-ÿ]+(-[A-Za-zÀ-ÖØ-öø-ÿ]+){1,3}$")


def edits1(w: str):
    """Words one deletion, transposition, substitution or insertion (a-z) away from w."""
    letters = "abcdefghijklmnopqrstuvwxyz"
    splits = [(w[:i], w[i:]) for i in range(len(w) + 1)]
    for a, b in splits:
        if b:
            yield a + b[1:]
            for c in letters:
                yield a + c + b[1:]
        if len(b) > 1:
            yield a + b[1] + b[0] + b[2:]
        for c in letters:
            yield a + c + b


def looks_like_typo(w: str, z: float, zipf: dict) -> bool:
    """True if a word one edit from w is at least 1000 times as frequent (3 on the Zipf scale)."""
    return any(e != w and zipf.get(e, 0) >= z + 3 for e in edits1(w))


def zipf_floor(level: int) -> float:
    for top, floor in LEVEL_FLOOR:
        if level <= top:
            return floor
    return LEVEL_FLOOR[-1][1]


# a casing below this share of a word's mid-sentence use is dropped (paris: 0 of 2786)
MIN_CASING_SHARE = 0.02


def load_casing(path: str) -> dict:
    """lowercase word -> {casing: mid-sentence count} from casing_counts.py."""
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            form, n = line.rstrip("\n").split("\t")
            out.setdefault(form.lower(), {})[form] = int(n)
    return out


def load_scowl(final_dir: str, max_level: int):
    """word -> (level, kind) for the en_US lists up to max_level (latin-1 files)."""
    words = {}
    for fn in os.listdir(final_dir):
        m = re.match(r"(.+?)-(.+)\.(\d+)$", fn)
        if not m:
            continue
        region, kind, level = m.group(1), m.group(2), int(m.group(3))
        if region not in SCOWL_REGIONS or kind not in SCOWL_KINDS or level > max_level:
            continue
        with open(os.path.join(final_dir, fn), encoding="latin-1") as f:
            for w in f.read().split("\n"):
                if w and (w not in words or level < words[w][0]):
                    words[w] = (level, kind)
    return words


def load_other_region_spellings(final_dir: str, us_words) -> set:
    """Lowercase spellings SCOWL lists only for other regions (colour, centre, organise)."""
    other = set()
    for fn in os.listdir(final_dir):
        m = re.match(r"(.+?)-(.+)\.(\d+)$", fn)
        if not m or m.group(1).split("_variant_")[0] not in FOREIGN_REGIONS:
            continue
        with open(os.path.join(final_dir, fn), encoding="latin-1") as f:
            other.update(w.lower() for w in f.read().split("\n") if w)
    return other - {w.lower() for w in us_words}


def load_commonwealth_spellings(final_dir: str) -> set:
    """Lowercase words in at least three of the other regions' SCOWL lists (centre, colour): the
    shared British-style spellings, not one region's slang (Australian "kylie")."""
    regions = defaultdict(set)
    for fn in os.listdir(final_dir):
        m = re.match(r"(.+?)-(.+)\.(\d+)$", fn)
        if not m or m.group(1).split("_variant_")[0] not in FOREIGN_REGIONS:
            continue
        with open(os.path.join(final_dir, fn), encoding="latin-1") as f:
            for w in f.read().split("\n"):
                if w and w == w.lower():
                    regions[w].add(m.group(1))
    return {w for w, r in regions.items() if len(r) >= 3}


# British-to-US spelling patterns (centre/center, colour/color, sulphur/sulfur, sceptic/skeptic, ...)
US_SPELLINGS = (("re", "er"), ("our", "or"), ("sulph", "sulf"), ("scep", "skep"), ("grey", "gray"),
                ("tyre", "tire"), ("storey", "story"), ("ise", "ize"), ("isation", "ization"), ("lled", "led"),
                ("ae", "e"), ("oe", "e"))


def has_us_spelling(word: str, us_lower: set) -> bool:
    """True if a US spelling of the British-style word is a US word (centre -> center)."""
    for gb, us in US_SPELLINGS:
        i = word.find(gb)
        while i >= 0:
            if word[:i] + us + word[i + len(gb):] in us_lower:
                return True
            i = word.find(gb, i + 1)
    return False


EXTRA_ZIPF = {}  # extra words with a measured Zipf ("H&M\t3.19": web counts split at '&')


def load_extra_words():
    words = []
    with open(EXTRA_WORDS, encoding="utf-8") as f:
        for l in f:
            if l.strip() and not l.startswith("#"):
                w, _, z = l.strip().partition("\t")
                words.append(w)
                if z:
                    EXTRA_ZIPF[w] = float(z)
    return words


def load_wordfreq(path: str):
    """lowercase word -> Zipf frequency."""
    bins = msgpack.unpackb(gzip.open(path).read(), raw=False)
    zipf = {}
    for i, b in enumerate(bins[1:], start=1):  # bins[0] is a header; bin i = 10^(-i/100)
        for w in b:
            zipf.setdefault(w, 9 - i / 100)
    return zipf


def load_wikidata(dir_path: str):
    """single-word label -> max sitelinks, over all kinds."""
    names = {}
    if not os.path.isdir(dir_path):
        return names
    for fn in sorted(os.listdir(dir_path)):
        if not fn.endswith(".tsv"):
            continue
        with open(os.path.join(dir_path, fn), encoding="utf-8") as f:
            for line in f:
                label, _, n = line.rstrip("\n").partition("\t")
                if " " in label or not WORD_RE.match(label) or len(label) < 2:
                    continue
                names[label] = max(names.get(label, 0), int(n or 0))
    return names


def title_name(upper: str) -> str:
    """Census names are upper case: JAMES -> James, MCDONALD -> McDonald."""
    name = upper.capitalize()
    if name.startswith("Mc") and len(name) > 2:
        name = "Mc" + name[2:].capitalize()
    return name


def load_people(data_dir: str, max_surnames: int):
    """Name -> share of people (fraction), from the Census first-name and surname lists."""
    people = {}
    for fn in ("dist.male.first", "dist.female.first"):
        path = os.path.join(data_dir, "census/1990", fn)
        if os.path.exists(path):
            for line in open(path, encoding="latin-1"):
                parts = line.split()
                if len(parts) >= 2:
                    name = title_name(parts[0])
                    people[name] = max(people.get(name, 0.0), float(parts[1]) / 100)
    path = os.path.join(data_dir, "census/surnames2010/Names_2010Census.csv")
    if os.path.exists(path):
        for line in list(open(path, encoding="latin-1"))[1:max_surnames + 1]:
            parts = line.split(",")
            if parts[0].isalpha():
                name = title_name(parts[0])
                people[name] = max(people.get(name, 0.0), float(parts[3]) / 100000)
    return people


def load_places(data_dir: str):
    """Single-word place name -> population (cities over 15k, countries, first-level regions)."""
    places = {}
    geo = os.path.join(data_dir, "geonames")
    path = os.path.join(geo, "cities15000.txt")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            f = line.rstrip("\n").split("\t")
            name, pop = f[2], int(f[14] or 0)
            if WORD_RE.match(name) and " " not in name:
                places[name] = max(places.get(name, 0), pop)
    path = os.path.join(geo, "countryInfo.txt")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            if line.startswith("#"):
                continue
            f = line.split("\t")
            if len(f) > 7 and WORD_RE.match(f[4]):
                places[f[4]] = max(places.get(f[4], 0), int(f[7] or 0))
    path = os.path.join(geo, "admin1CodesASCII.txt")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            f = line.split("\t")
            if len(f) > 2 and WORD_RE.match(f[2]):
                places.setdefault(f[2], 1_000_000)  # states and provinces: treat as a large place
    return places


def load_us_variants(final_dir: str, max_level: int) -> dict:
    """word -> level from SCOWL's American variant lists (variant_1..3)."""
    out = {}
    for fn in os.listdir(final_dir):
        m = re.match(r"variant_\d-(words|upper|contractions)\.(\d+)$", fn)
        if not m or int(m.group(2)) > max_level:
            continue
        with open(os.path.join(final_dir, fn), encoding="latin-1") as f:
            for w in f.read().split("\n"):
                if w and (w not in out or int(m.group(2)) < out[w]):
                    out[w] = int(m.group(2))
    return out


def load_misspellings(path: str):
    bad = set()
    if not os.path.exists(path):
        return bad
    with open(path, encoding="utf-8") as f:
        for line in f:
            if "->" in line:
                bad.add(line.split("->")[0].strip().lower())
    return bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--max-level", type=int, default=80)
    ap.add_argument("--max-words", type=int, default=0, help="keep only the N most frequent (0 = all)")
    ap.add_argument("--max-surnames", type=int, default=20000, help="most common Census surnames to include")
    ap.add_argument("--out", default="app/src/main/assets/dogboard/en.lexicon.tsv")
    ap.add_argument("--casing", default="", help="casing_counts.py output; keeps SCOWL's casings of a word with a measured split")
    ap.add_argument("--min-zipf-web-only", type=float, default=MIN_ZIPF_WEB_ONLY, help="words only wordfreq has (slang, brands, tech) kept from this Zipf up")
    ap.add_argument("--min-zipf-possessive", type=float, default=MIN_ZIPF_POSSESSIVE, help="possessives ('s, s') kept from this Zipf up")
    ap.add_argument("--short-word-filter", action="store_true", help="drop short fragments and abbreviations (see main)")
    ap.add_argument("--short-word-report", default="", help="write the short words the filter dropped here")
    ap.add_argument("--web-names", action="store_true",
                    help="add names written capitalised in --seen-counts (cased counts): Wikidata's (--wikidata-wide) and the corpus's own")
    ap.add_argument("--web-names-report", default="", help="write the names --web-names added here (name, Zipf, source)")
    ap.add_argument("--hyphenated", default="", help="count_hyphenated.py output: hyphenated words with their counts")
    ap.add_argument("--min-zipf-hyphenated", type=float, default=MIN_ZIPF_HYPHENATED)
    ap.add_argument("--hyphenated-words", type=float, default=0, help="how many words the --hyphenated text had (its Zipf scale)")
    ap.add_argument("--wikidata-wide", default="", help="one-word Wikidata names, label<TAB>sitelinks (tools/data/fetch_wikidata_wide.py)")
    ap.add_argument("--seen-counts", default="", help="count_unigrams.py output (FineWeb): plain lowercase SCOWL words it never saw are dropped")
    args = ap.parse_args()

    final_dir = os.path.join(args.data, "scowl/scowl-2020.12.07/final")
    scowl = load_scowl(final_dir, args.max_level)
    other_regions = load_other_region_spellings(final_dir, load_scowl(final_dir, 95))
    us_lower = {w for w in scowl if w.islower()}
    commonwealth = {w for w in load_commonwealth_spellings(final_dir) - us_lower if has_us_spelling(w, us_lower)}
    zipf = load_wordfreq(os.path.join(args.data, "wordfreq/whl/wordfreq/data/large_en.msgpack.gz"))
    wikidata = load_wikidata(os.path.join(args.data, "wikidata"))
    misspelled = load_misspellings(os.path.join(args.data, "wikipedia/common_misspellings.txt"))
    people = load_people(args.data, args.max_surnames)
    places = load_places(args.data)

    # candidates: lowercase key -> {surface form: zipf}
    forms = defaultdict(dict)
    sources = defaultdict(int)

    def add(form: str, z: float, source: str, level: int = 99):
        key = form.lower()
        # the misspelling list never removes common SCOWL words (thru) or names (Holliday)
        if key in misspelled and level > 50 and source not in ("census", "wikidata", "geonames"):
            return
        # other-region spellings are kept out of the web-only words, not out of names
        if source in ("wordfreq", "extra") and key in other_regions:
            return
        if forms[key].get(form, -1) < z:
            forms[key][form] = z
        sources[source] += 1

    # words never seen in billions of words of text only give autocorrect wrong targets
    # ("abamperes", "abnub"); only plain lowercase words: names and forms our tokenizer splits stay
    seen = None
    case_counts = {}  # case-sensitive counts of the British-style spellings and their capitals
    web_forms = {}  # --web-names: lowercase -> {cased form: count} of words written capitalised
    web_total = 0
    if args.seen_counts:
        seen = set()
        with open(args.seen_counts, encoding="utf-8") as f:
            for line in f:
                if not line.startswith("#"):
                    w, n = line.rstrip("\n").split("\t", 1)
                    low = w.lower()
                    seen.add(low)
                    if low in commonwealth:
                        case_counts[w] = int(n)
                    if args.web_names:
                        web_total += int(n)
                        if w != low and int(n) >= 20:
                            web_forms[low] = {}
        # a second pass counts every form of the words seen capitalised (17M lines: one pass for all would not fit)
        if args.web_names:
            with open(args.seen_counts, encoding="utf-8") as f:
                for line in f:
                    if not line.startswith("#"):
                        w, n = line.rstrip("\n").split("\t", 1)
                        d = web_forms.get(w.lower())
                        if d is not None:
                            d[w] = int(n)

    # a name spelt like a British-style word ("Centre": a French region, and SCOWL's proper names)
    # takes its own floor when text has the lowercase word at least as often: the web count is then
    # mostly the word's and would make a typed "centre" come out as "Centre" (Welch keeps its count:
    # "welch" 319 vs "Welch" 19205 in FineWeb)
    def british_word(form):
        key = form.lower()
        return form != key and key in commonwealth and case_counts.get(key, 0) >= case_counts.get(form, 0)
    unseen_dropped = 0
    for w, (level, kind) in scowl.items():
        if not WORD_RE.match(w):
            continue
        if seen is not None and w.isalpha() and w.islower() and w not in seen:
            unseen_dropped += 1
            continue
        add(w, zipf_floor(level) if british_word(w) else zipf.get(w.lower(), zipf_floor(level)), "scowl", level)
    for label, sitelinks in wikidata.items():
        floor = min(1.2, 0.5 + 0.3 * math.log10(max(sitelinks, 1)))
        add(label, zipf.get(label.lower(), floor), "wikidata")
    # names and places: wordfreq where it has the word (capped, since a lowercase count also holds
    # other uses and typos), else a floor from how common the name or how big the place is
    def name_zipf(name, floor):
        if british_word(name):
            return floor
        return min(zipf.get(name.lower(), floor), max(floor, NAME_ZIPF_CAP))
    for name, share in people.items():
        floor = min(1.5, max(0.5, 2.5 + math.log10(share)))  # 1% of people -> 0.5 + ... capped
        add(name, name_zipf(name, floor), "census")
    for name, pop in places.items():
        floor = min(1.5, max(0.5, 0.25 * math.log10(max(pop, 1))))
        add(name, name_zipf(name, floor), "geonames")
    for w, z in zipf.items():
        if z < args.min_zipf_web_only or w in forms or w in other_regions or not WORD_RE.match(w) or "." in w:
            continue
        # below the old 3.0 cut, web text is full of slips ("thw", "hte", "tooo"): a word one edit away
        # from one at least 1000x as common is taken for a typo of it
        if z < 3.0 and looks_like_typo(w, z, zipf):
            continue
        add(w, z, "wordfreq")
    # names the web corpus writes capitalised (brands, apps, sites): the corpus gives frequency and casing
    if args.web_names and web_total:
        def web_name(key):
            """(usual cased form, Zipf) of a word the corpus mostly writes capitalised, else None."""
            f = web_forms.get(key)
            if not f:
                return None
            tot = sum(f.values())
            best = max(f, key=f.get)
            if best == key or 1 - f.get(key, 0) / tot < MIN_NAME_CAPITAL_SHARE:
                return None
            # short names would take short typos' corrections ("Gea", "Nou", "GV"): 4 letters, or 3 in capitals
            if len(best) < (3 if best.isupper() else 4):
                return None
            z = math.log10(tot / web_total * 1e9)
            if best.isupper() and z < MIN_ZIPF_CAPS_NAME:
                return None
            return best, math.log10(tot / web_total * 1e9)
        added = {"wikidata-wide": 0, "web-name": 0}
        report = open(args.web_names_report, "w", encoding="utf-8") if args.web_names_report else None
        if args.wikidata_wide:
            with open(args.wikidata_wide, encoding="utf-8") as f:
                for line in f:
                    if line.startswith("#"):
                        continue
                    key = line.split("\t", 1)[0].lower()
                    if key in forms or key in misspelled or key in other_regions:
                        continue
                    r = web_name(key)
                    if r and r[1] >= MIN_ZIPF_WIKIDATA_NAME and WORD_RE.match(r[0]):
                        add(r[0], min(r[1], NAME_ZIPF_CAP), "wikidata-wide"); added["wikidata-wide"] += 1
                        if report: report.write(f"{r[0]}\t{r[1]:.2f}\twikidata-wide\n")
        sorted_keys = sorted(forms)
        for key in web_forms:
            if key in forms or key in misspelled or key in other_regions:
                continue
            r = web_name(key)
            if not r or r[1] < MIN_ZIPF_WEB_NAME or not WEB_NAME_RE.match(r[0]):
                continue
            # capitals inside a word only after a lowercase start (iHerb): camel-case brands need Wikidata, as page
            # text glues words the same way ("ArticleGoogle", "ReplyDelete")
            if any(c.isupper() for c in r[0][1:]) and not r[0].isupper() and not r[0][0].islower():
                continue
            # the start of a more common word is an abbreviation or a broken token ("Microbiol", "Hawai", "Pract")
            i = bisect.bisect_right(sorted_keys, key)
            if i < len(sorted_keys) and sorted_keys[i].startswith(key) and any(
                    max(forms[k].values()) >= r[1] - 0.5 for k in sorted_keys[i:i + 50] if k.startswith(key)):
                continue
            # a slip of a more common word ("Ghandi", "Pharoah", "Regsitration") is not a name to learn
            if any(e != key and zipf.get(e, 0) >= r[1] + 1.0 for e in edits1(key)):
                continue
            add(r[0], min(r[1], NAME_ZIPF_CAP), "web-name"); added["web-name"] += 1
            if report: report.write(f"{r[0]}\t{r[1]:.2f}\tweb-name\n")
        print(f"web names: {added}")
        if report: report.close()
    # hyphenated words, in the casing the text uses most (well-known, T-shirt, Coca-Cola)
    if args.hyphenated and args.hyphenated_words:
        by_key = defaultdict(dict)
        with open(args.hyphenated, encoding="utf-8") as f:
            for line in f:
                if line.startswith("#"):
                    continue
                w, n = line.rstrip("\n").split("\t")
                if HYPHENATED_RE.match(w):
                    by_key[w.lower()][w] = int(n)
        n_hyph = 0
        zipf_of = {k: math.log10(sum(fs.values()) / args.hyphenated_words * 1e9) for k, fs in by_key.items()}
        bases = {k for k, z in zipf_of.items() if z >= args.min_zipf_hyphenated}

        def inflects(key):
            """key is an -s / -es / -ed / -d / -ing form of a hyphenated word that is in."""
            head, _, last = key.rpartition("-")
            stems = {last[:-1], last[:-2], last[:-2] + "e", last[:-3], last[:-3] + "e", last[:-3] + "y", last[:-2] + "y"}
            return any(f"{head}-{st}" in bases for st in stems if st) and last.endswith(("s", "ed", "d", "ing"))
        for key, fs in by_key.items():
            if key in forms or key in misspelled or key.replace("-", "") in misspelled:
                continue
            tot = sum(fs.values())
            z = zipf_of[key]
            if z < args.min_zipf_hyphenated and not (z >= MIN_ZIPF_HYPHENATED_FORM and inflects(key) and key.replace("-", "") not in forms):
                continue
            best = max(fs, key=fs.get)
            # all capitals is a heading's casing, not the word's (WELL-KNOWN): take the next form
            if best.isupper() and len(fs) > 1:
                best = max((f for f in fs if not f.isupper()), key=fs.get)
            add(best, min(z, NAME_ZIPF_CAP), "hyphenated"); n_hyph += 1
        print(f"hyphenated: {n_hyph}")
    # American variant spellings fill gaps only: a name with the same letters wins (Bonnie, not bonnie)
    if seen is not None:
        print(f"dropped {unseen_dropped} SCOWL words never seen in --seen-counts")
    for w, level in load_us_variants(final_dir, 95).items():
        z = zipf.get(w.lower())
        if z is not None and z >= MIN_ZIPF_US_VARIANT and WORD_RE.match(w) and w.lower() not in forms:
            add(w, z, "us-variant", level)
    extra = load_extra_words()
    for w in extra:
        if w not in forms[w.lower()]:  # (forms is keyed by the lowercase word: "hm" beside "HM")
            add(w, EXTRA_ZIPF.get(w) or zipf.get(w.lower(), EXTRA_ZIPF_FLOOR), "extra")

    # casings: when SCOWL lists a word in more than one casing (google/Google, may/May), keep each
    # casing that is at least MIN_CASING_SHARE of mid-sentence use in edited text, splitting the
    # word's frequency by that use (casing_counts.py); iOS keeps such variants as separate entries
    casing = load_casing(args.casing) if args.casing else {}
    lexicon = {}
    with_variants = set()
    for key, cands in forms.items():
        if key in extra:  # a lowercase spelling reviewed by hand: chosen below
            continue
        variants = {f: n for f, n in casing.get(key, {}).items() if f in cands}
        if len(variants) > 1:
            total = sum(variants.values()) + 0.5 * len(variants)
            shares = {f: (n + 0.5) / total for f, n in variants.items()}
            kept = {f: sh for f, sh in shares.items() if sh >= MIN_CASING_SHARE}
            if len(kept) > 1:
                z = max(cands.values())
                norm = sum(kept.values())
                for f, sh in kept.items():
                    lexicon[f] = z + math.log10(sh / norm)
                with_variants.add(key)
            elif kept:
                # one casing carries (almost) all use: that one ("Smith", not "smith")
                lexicon[next(iter(kept))] = max(cands.values())
                with_variants.add(key)
    # otherwise one surface form per key: an ordinary lowercase word wins unless it is uncommon
    # (SCOWL level >= LOWERCASE_RARE_LEVEL) and a capitalised form exists (paris -> Paris)
    for key, cands in forms.items():
        if key in with_variants:
            continue
        proper = [f for f in cands if f != key]
        lowercase_rare = key in cands and scowl.get(key, (0, ""))[0] >= LOWERCASE_RARE_LEVEL
        # a lowercase spelling only web text has ("whatsapp") loses to a brand's inner capitals
        # ("WhatsApp", "YouTube") or to a casing reviewed by hand in the extra words ("Gmail"): no
        # dictionary lists the lowercase one
        brand = [f for f in proper if any(c.isupper() for c in f[1:]) or f in extra]
        if key in extra:  # reviewed by hand in its lowercase spelling (bc, rn, hm: not BC, RN, HM)
            surface = key
        elif key in cands and key not in scowl and brand:
            surface = max(brand, key=lambda f: (cands[f], f))
        elif key in cands and not (proper and lowercase_rare):
            surface = key
        else:
            surface = max(proper or cands, key=lambda f: (cands[f], f))
        lexicon[surface] = max(cands.values())
    # single letters are lowercase words ("g", "x"): a capital form would make autocorrect capitalise
    # a typed letter; "I" is the exception
    for c in "abcdefghjklmnopqrstuvwxyz":
        if c.upper() in lexicon:
            z = lexicon.pop(c.upper())
            lexicon[c] = math.log10(10 ** lexicon.get(c, -9) + 10 ** z)
    lexicon["I"] = lexicon.pop("i", zipf.get("i", 7.0))

    # short words ("Th is certainly unexpected... cut down the up to 4-5 letter words,
    # and even harsher on 1-3 letter words"): a short entry is a cheap target for a slip and shows up
    # as a completion ("ofth" -> "of Th"), so it must be a real word, a name or a common word, not a
    # fragment or an abbreviation the web counts happen to hold
    if args.short_word_filter:
        names = {n.lower() for n in people} | {n.lower() for n in places} | {n.lower() for n in wikidata}
        # how often each short word is written in lowercase among all its casings (FineWeb): acronyms
        # and names are mostly capitalised (doj 1.5 %, nyt 3 %), slang mostly not (plz 70 %, pov 18 %)
        casings = defaultdict(lambda: [0, 0])  # lowercase key -> [lowercase count, all count]
        written = {}  # lowercase key -> (count, its most common written form)
        if args.seen_counts:
            with open(args.seen_counts, encoding="utf-8") as f:
                for line in f:
                    if line.startswith("#"):
                        continue
                    w, n = line.rstrip("\n").split("\t", 1)
                    if len(w) <= 5 and w.isalpha():
                        c = casings[w.lower()]
                        c[1] += int(n)
                        if w.islower():
                            c[0] += int(n)
                        if int(n) > written.get(w.lower(), (0, ""))[0]:
                            written[w.lower()] = (int(n), w)
        lowercase_share = lambda w: (casings[w][0] / casings[w][1]) if w in casings and casings[w][1] else 1.0
        dropped = []
        recased = []
        for w in list(lexicon):
            letters = sum(c.isalpha() for c in w)
            if letters > 5 or "'" in w or w == "I" or w in extra:
                continue
            level, kind = scowl.get(w, (99, ""))
            z = lexicon[w]
            harsh = letters <= 3
            # a capitalised or mixed-case form SCOWL has only in its capitalised list, from a rarer level,
            # that isn't a person's name, a place or a brand: abbreviations and capitalised common words
            # (Th, Ca, Md, Fr, Au, New, Law, Old)
            # (4-5 letters only when the word is also stored in lowercase, a pure duplicate: real proper
            # nouns without one stay, Kurds, Shia)
            twin = w.lower() != w and w.lower() in lexicon
            # Without a lowercase twin the word would disappear, so only what is surely not a word goes:
            # two-letter abbreviations (Th, Md, Pb), rare three-letter ones; never units (Hz) or
            # mixed case (MHz)
            title = w[:1].isupper() and w[1:].islower()
            gone_ok = title and w not in UNITS and (letters == 2 or (letters == 3 and z < 3.0))
            capital_only = (not w.islower() and kind == "upper" and level >= (50 if harsh else 70)
                            and w.lower() not in names and (twin or (harsh and gone_ok)))
            # a lowercase word only web text has (no dictionary lists it), unless it is common: slang
            # stays (lol, idk, omg), fragments and lowercase acronyms go (doj, mvs, crp)
            web_only = w.islower() and w not in scowl
            # mostly written capitalised: an acronym, brand or name stored in the wrong case; it takes
            # its usual written form (xbox -> Xbox, nhs -> NHS) unless that form is already stored
            if web_only and lowercase_share(w) < SHORT_MIN_LOWERCASE_SHARE and w in written:
                form = written[w][1]
                if form != w and form not in lexicon:
                    lexicon[form] = lexicon.pop(w)
                    recased.append((w, form))
                    continue
            # its usual written form is already stored: this lowercase copy is a duplicate. Nothing is cut
            # for being rare ("don't cut important words"): chat words like mhm, cya
            # and omw are rare in web text and often capitalised at a sentence start
            web_only_rare = web_only and lowercase_share(w) < SHORT_MIN_LOWERCASE_SHARE
            if capital_only or web_only_rare:
                dropped.append((w, round(z, 2), "capital-only" if capital_only else "web-only"))
                del lexicon[w]
        if args.short_word_report:
            with open(args.short_word_report, "w", encoding="utf-8") as f:
                for w, z, why in sorted(dropped, key=lambda d: -d[1]):
                    f.write(f"{w}\t{z}\t{why}\n")
            with open(args.short_word_report + ".recased", "w", encoding="utf-8") as f:
                for a, b in recased:
                    f.write(f"{a}\t{b}\n")
        print(f"short-word filter: dropped {len(dropped)}, recased {len(recased)}", file=sys.stderr)

    # possessives: only frequent ones; the engine generates the rest
    for w in [w for w in lexicon if w.lower().endswith(("'s", "s'"))]:
        if lexicon[w] < args.min_zipf_possessive and w.lower() not in ("it's", "he's", "she's", "that's", "what's",
                                                                    "there's", "here's", "who's", "let's"):
            del lexicon[w]
    # apostrophe-less contraction spellings ("dont", "im") so autocorrect restores the apostrophe
    by_plain = {w.replace("'", "").lower(): w for w in lexicon if "'" in w}
    for w in list(lexicon):
        c = by_plain.get(w.lower())
        # all-caps words stay (ID vs I'd, IM vs I'm), and so do core dictionary words (wed vs we'd,
        # wont vs won't): context tells them apart ("they wed in June", "we'd better go"), and iOS keeps
        # them too
        core = w.islower() and w not in ALWAYS_CONTRACTION and scowl.get(w, (99, ""))[0] <= CONTRACTION_KEEP_LEVEL
        if c is not None and c != w and not w.isupper() and not core and lexicon[c] - lexicon[w] >= CONTRACTION_GAP:
            del lexicon[w]

    # possessives ride on top of the word cap (iOS stores them beside its words): the cap is for
    # the base words, and a possessive stays if its base word does
    is_poss = lambda w: w.lower().endswith(("'s", "s'"))
    ranked = sorted(((w, z) for w, z in lexicon.items() if not is_poss(w)), key=lambda kv: (-kv[1], kv[0]))
    if args.max_words and len(ranked) > args.max_words:
        # a cut inside a tie (thousands of rare words at the same floor) drops the whole tie
        # instead of keeping an arbitrary part of it
        edge = ranked[args.max_words][1]
        ranked = [kv for kv in ranked[: args.max_words] if kv[1] > edge]
    kept = {w.lower() for w, _ in ranked}
    # ...or if it is a common word on its own (names that are possessives: "Sainsbury's")
    poss = [(w, z) for w, z in lexicon.items()
            if is_poss(w) and ((w[:-2] if w.endswith("'s") else w[:-1]).lower() in kept or z >= MIN_ZIPF_POSSESSIVE)]
    ranked = sorted(ranked + poss, key=lambda kv: (-kv[1], kv[0]))
    with open(args.out, "w", encoding="utf-8") as out:
        out.write("#dogboard-lexicon 1\n#lang en_US\n")
        out.write(f"#source SCOWL 2020.12.07 (level <= {args.max_level}), wordfreq 3.1.1 (CC BY-SA 4.0), "
                  f"Wikidata (CC0), US Census names (public domain), GeoNames (CC BY 4.0), "
                  + ("FineWeb counts (ODC-By 1.0) for names, " if args.web_names else "") +
                  f"Wikipedia common misspellings (excluded); tools/data/build_lexicon.py\n")
        for w, z in ranked:
            out.write(f"{w}\t{round(10 ** (z + 1))}\n")
    print(f"{len(ranked)} words -> {args.out} (candidates by source: {dict(sources)})", file=sys.stderr)


if __name__ == "__main__":
    main()
