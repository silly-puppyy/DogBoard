#!/usr/bin/env python3
"""One-word names of Wikidata items with an official website (companies, brands, products, apps, sites,
bands, games...), people and places left out, for one language, with each item's number of Wikipedia
articles ("sitelinks"). build_lexicon.py --web-names keeps those the language's web text writes capitalised.

    tools/data/fetch_wikidata_wide.py LANG OUT.tsv      (LANG: a Wikidata label language, "en", "de", ...)

Uses the QLever Wikidata endpoint (the public Wikidata query service times out on this query). Wikidata is CC0.
"""
import csv
import io
import re
import sys
import urllib.parse
import urllib.request

ENDPOINT = "https://qlever.cs.uni-freiburg.de/api/wikidata"
USER_AGENT = "DogBoardLexiconBuilder/0.2 (open-source keyboard; https://github.com/silly-puppyy/DogBoard)"
MIN_SITELINKS = 3
QUERY = """PREFIX wdt: <http://www.wikidata.org/prop/direct/> PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wikibase: <http://wikiba.se/ontology#> PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?item ?label ?sl WHERE {
  ?item wdt:P856 ?site ; wikibase:sitelinks ?sl .
  FILTER(?sl >= %d)
  MINUS { ?item wdt:P31 wd:Q5 }
  MINUS { ?item wdt:P625 ?coord }
  ?item rdfs:label ?label . FILTER(LANG(?label) = "%s")
}"""
# one word: letters, digits and the joiners names use (AT&T, Wi-Fi, Yahoo!, Moody's)
ONE_WORD = re.compile(r"^\w[\w&.'’+!-]*$")


def main():
    lang, out = sys.argv[1], sys.argv[2]
    url = ENDPOINT + "?" + urllib.parse.urlencode({"query": QUERY % (MIN_SITELINKS, lang)})
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "text/csv"})
    with urllib.request.urlopen(req, timeout=600) as resp:
        rows = list(csv.reader(io.StringIO(resp.read().decode("utf-8"))))[1:]
    names = {}
    for _item, label, sl in rows:
        if ONE_WORD.match(label):
            names[label] = max(names.get(label, 0), int(sl))
    with open(out, "w", encoding="utf-8") as f:
        f.write(f"#source Wikidata (CC0) via QLever; items with an official website, not people or places; {lang}\n")
        for label, sl in sorted(names.items(), key=lambda kv: -kv[1]):
            f.write(f"{label}\t{sl}\n")
    print(f"{len(rows)} items, {len(names)} one-word names -> {out}")


if __name__ == "__main__":
    main()
