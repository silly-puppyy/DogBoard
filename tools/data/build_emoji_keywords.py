#!/usr/bin/env python3
"""Builds the emoji suggestion index (tools/data/build_emoji_keywords.py).

    tools/data/build_emoji_keywords.py <cldr common/annotations/en.xml> <wordfreq large_en.msgpack.gz>
                                       [<cldr common/annotationsDerived/en.xml>]

Writes app/src/main/assets/dogboard/en.emoji.tsv: keyword<TAB>emoji emoji ..., one keyword or
phrase per line ("dog", "thumbs up"). As iOS looks words and phrases up in its emoji keyword
data, DogBoard uses Unicode CLDR's English emoji annotations: every keyword, and each emoji's
name. Emoji whose name is the keyword come first ("dog": 🐕 before 🐶 🐩), then the others by how
much people use them (wordfreq's frequency of the emoji: "love" → ❤ 😍), ties broken by the keyword
being a word of the name, shorter names, then CLDR's order.
Only emoji are kept (CLDR also annotates symbols such as "{" and arrows).
With the derived annotations it also writes en.emoji-tones.tsv: an emoji and its five skin-tone
forms (light to dark) as CLDR spells them, for emoji where one tone applies to the whole emoji.
Sources: Unicode CLDR (unicode-org/cldr, common/annotations/en.xml), Unicode License v3;
wordfreq 3.1.1 emoji frequencies (CC BY-SA 4.0), so the output is CC BY-SA 4.0 like the lexicon.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_lexicon import load_wordfreq  # noqa: E402
from collections import defaultdict

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "dogboard", "en.emoji.tsv")
MAX_PER_KEYWORD = 8


def is_emoji(s: str) -> bool:
    """Pictographic emoji (not plain symbols): main emoji blocks, or anything with the emoji variation selector."""
    for ch in s:
        cp = ord(ch)
        if 0x1F000 <= cp <= 0x1FAFF or 0x2600 <= cp <= 0x27BF or cp == 0xFE0F:
            return True
    return False


def main():
    import argparse
    global OUT
    ap = argparse.ArgumentParser()
    ap.add_argument("annotations", help="CLDR common/annotations/<lang>.xml")
    ap.add_argument("wordfreq", help="wordfreq large_<lang>.msgpack.gz")
    ap.add_argument("derived", nargs="?", default="", help="CLDR common/annotationsDerived/<lang>.xml (skin tones)")
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--tones-out", default="")
    a = ap.parse_args()
    OUT = a.out
    xml = open(a.annotations, encoding="utf-8").read()
    zipf = load_wordfreq(a.wordfreq)

    def popularity(cp: str) -> float:
        bare = cp.replace("\ufe0f", "")
        return max(zipf.get(cp, 0.0), zipf.get(bare, 0.0), zipf.get(bare + "\ufe0f", 0.0))
    names = {}
    keywords = defaultdict(list)
    for cp, tts, text in re.findall(r'<annotation cp="([^"]+)"( type="tts")?>([^<]+)</annotation>', xml):
        if not is_emoji(cp):
            continue
        if tts:
            names[cp] = text.strip().lower()
        else:
            keywords[cp] = [k.strip().lower() for k in text.split("|") if k.strip()]
    index = defaultdict(list)  # keyword -> [(exact name?, -popularity, keyword in name?, words in the name, order, emoji)]
    for order, cp in enumerate(names):
        name = names[cp]
        size = len(name.split())
        pop = -popularity(cp)
        index[name].append((0, pop, 0, size, order, cp))
        for k in keywords.get(cp, []):
            if k != name:
                # a keyword that is a word of the emoji's name ("heart" in "red heart") ranks above
                # one that is only a keyword ("heart" for 🥰)
                index[k].append((1, pop, 0 if k in name.split() else 1, size, order, cp))
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# Emoji suggestions: keyword<TAB>emoji...; built by tools/data/build_emoji_keywords.py\n")
        f.write("# from Unicode CLDR common/annotations/en.xml (Unicode License v3) ranked by wordfreq 3.1.1 (CC BY-SA 4.0)\n")
        for k in sorted(index):
            if not re.fullmatch(r"[a-z0-9' .&-]+", k):
                continue
            emojis = [cp for *_, cp in sorted(index[k])][:MAX_PER_KEYWORD]
            f.write(f"{k}\t{' '.join(emojis)}\n")
    print(f"{len(names)} emoji, {len(index)} keywords -> {OUT}")
    if a.derived:
        write_tones(open(a.derived, encoding="utf-8").read(), a.tones_out)


TONES = ["\U0001F3FB", "\U0001F3FC", "\U0001F3FD", "\U0001F3FE", "\U0001F3FF"]


def write_tones(xml: str, out_path: str = ""):
    """base emoji -> its form in each skin tone, from CLDR's derived annotations."""
    out = out_path or os.path.join(os.path.dirname(OUT), "en.emoji-tones.tsv")
    table = {}
    for cp in dict.fromkeys(re.findall(r'<annotation cp="([^"]+)"', xml)):
        tones = {c for c in cp if c in TONES}
        if len(tones) != 1:
            continue  # untoned, or several people in different tones
        tone = tones.pop()
        base = "".join(c for c in cp if c not in TONES).replace("\ufe0f", "")
        table.setdefault(base, {})[tone] = cp
    with open(out, "w", encoding="utf-8") as f:
        f.write("# Emoji skin tones: emoji<TAB>light<TAB>medium-light<TAB>medium<TAB>medium-dark<TAB>dark; tools/data/build_emoji_keywords.py\n")
        f.write("# from Unicode CLDR common/annotationsDerived/en.xml (Unicode License v3)\n")
        n = 0
        for base, forms in sorted(table.items()):
            if all(t in forms for t in TONES):
                f.write(base + "\t" + "\t".join(forms[t] for t in TONES) + "\n")
                n += 1
    print(f"{n} emoji with skin tones -> {out}")


if __name__ == "__main__":
    main()
