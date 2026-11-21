#!/usr/bin/env python3
"""Expands a Hunspell dictionary into every word form it accepts (one per line, stem first).

    tools/data/expand_hunspell.py INDEX.dic INDEX.aff > forms.txt

Why not unmunch: it reads one byte per flag, so dictionaries with "FLAG UTF-8" (Spanish, and others from
LibreOffice) lose every entry whose flags are not ASCII ("administración/S|" came out as nothing).

Supported: FLAG (single character, UTF-8, long, num), PFX/SFX with conditions and cross products,
continuation flags on suffixes (two-fold suffixes, Spanish enclitics: "da" -> "dame" -> "dámelo"),
NEEDAFFIX / FORBIDDENWORD / ONLYINCOMPOUND (such stems are not words by themselves).
Compounding rules are not expanded (they make words on the fly, not a list).
"""
import re
import sys


def parse_flags(s, mode):
    if mode == "long":
        return [s[i:i + 2] for i in range(0, len(s), 2)]
    if mode == "num":
        return [x for x in s.split(",") if x]
    return list(s)  # single character, ASCII or UTF-8


class Affix:
    __slots__ = ("flag", "cross", "strip", "add", "cont", "cond", "pfx")

    def __init__(self, flag, cross, strip, add, cont, cond, pfx):
        self.flag, self.cross, self.strip, self.add, self.cont, self.pfx = flag, cross, strip, add, cont, pfx
        self.cond = re.compile(("^" + cond) if pfx else (cond + "$")) if cond != "." else None

    def apply(self, word):
        if self.cond and not self.cond.search(word):
            return None
        if self.pfx:
            if self.strip and not word.startswith(self.strip):
                return None
            return self.add + word[len(self.strip):]
        if self.strip and not word.endswith(self.strip):
            return None
        return word[:len(word) - len(self.strip)] + self.add


def read_aff(path):
    mode, special, affixes = "char", {}, {}
    with open(path, encoding="utf-8", errors="replace") as f:
        lines = [l.rstrip("\n") for l in f]
    for l in lines:
        p = l.split()
        if not p:
            continue
        if p[0] == "FLAG" and len(p) > 1:
            mode = {"long": "long", "num": "num"}.get(p[1], "char")
        elif p[0] in ("NEEDAFFIX", "FORBIDDENWORD", "ONLYINCOMPOUND") and len(p) > 1:
            special[p[0]] = p[1]
    header = {}
    for l in lines:
        p = l.split()
        if len(p) < 4 or p[0] not in ("PFX", "SFX"):
            continue
        if p[1] not in header and len(p) == 4 and p[2] in ("Y", "N"):
            header[p[1]] = p[2] == "Y"
            continue
        if len(p) < 5:
            continue
        add, _, cont = p[3].partition("/")
        aff = Affix(p[1], header.get(p[1], False), "" if p[2] == "0" else p[2], "" if add == "0" else add,
                    parse_flags(cont, mode) if cont else [], p[4], p[0] == "PFX")
        affixes.setdefault(p[1], []).append(aff)
    return mode, special, affixes


def expand(dic, aff, out):
    mode, special, affixes = read_aff(aff)
    not_alone = {special.get(k) for k in ("NEEDAFFIX", "FORBIDDENWORD", "ONLYINCOMPOUND")} - {None}
    seen = set()

    def emit(w):
        if w and w not in seen:
            seen.add(w)
            out.write(w + "\n")

    with open(dic, encoding="utf-8", errors="replace") as f:
        next(f)  # the entry count
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            entry = line.split("\t")[0].split(" ")[0]
            stem, _, fl = entry.partition("/")
            stem = stem.replace("\\/", "/")
            flags = parse_flags(fl, mode) if fl else []
            if "FORBIDDENWORD" in special and special["FORBIDDENWORD"] in flags:
                continue
            if not (set(flags) & not_alone):
                emit(stem)
            pfx = [a for fl_ in flags for a in affixes.get(fl_, []) if a.pfx]
            sfx = [a for fl_ in flags for a in affixes.get(fl_, []) if not a.pfx]
            suffixed = []
            for s in sfx:
                w = s.apply(stem)
                if w is None:
                    continue
                if not (set(s.cont) & not_alone):
                    emit(w)
                suffixed.append((w, s))
                # two-fold: the suffix's own continuation flags add a second suffix
                for c in s.cont:
                    for s2 in affixes.get(c, []):
                        if s2.pfx:
                            continue
                        w2 = s2.apply(w)
                        if w2 is not None:
                            emit(w2)
            for p in pfx:
                w = p.apply(stem)
                if w is not None:
                    emit(w)
                if p.cross:
                    for w1, s in suffixed:
                        if s.cross:
                            w2 = p.apply(w1)
                            if w2 is not None:
                                emit(w2)
    return len(seen)


if __name__ == "__main__":
    n = expand(sys.argv[1], sys.argv[2], sys.stdout)
    print(f"{n} forms", file=sys.stderr)
