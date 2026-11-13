#!/usr/bin/env python3
"""Fetch cased English names of people, companies, brands and websites from Wikidata (CC0) for the
lexicon, each with a popularity signal (the number of Wikipedia language editions with an
article, "sitelinks"), plus given and family names with many articles (international names the
US Census lists miss). Places come from GeoNames (see build_lexicon.py).

    tools/data/fetch_wikidata_names.py OUT_DIR

Writes OUT_DIR/<kind>.tsv with lines "label<TAB>sitelinks". Uses the public query service, one
query per kind, with a sitelinks floor to keep each query small.
"""
import csv
import io
import os
import sys
import time
import urllib.parse
import urllib.request

ENDPOINT = "https://query.wikidata.org/sparql"
USER_AGENT = "DogBoardLexiconBuilder/0.1 (open-source keyboard; https://github.com/silly-puppyy/DogBoard)"

# kind -> (instance-of classes, minimum sitelinks)
KINDS = {
    "given_names": (["wd:Q202444", "wd:Q12308941", "wd:Q11879590", "wd:Q3409032"], 5),
    "family_names": (["wd:Q101352"], 8),
    "companies": (["wd:Q4830453", "wd:Q891723", "wd:Q6881511"], 30),
    "brands": (["wd:Q431289", "wd:Q167270"], 15),
    "websites": (["wd:Q35127", "wd:Q1153191"], 20),
    "apps": (["wd:Q3220391", "wd:Q620615", "wd:Q1668024"], 20),
}

QUERY = """
SELECT ?label (MAX(?sl) AS ?sitelinks) WHERE {{
  VALUES ?type {{ {types} }}
  ?item wdt:P31 ?type ;
        wikibase:sitelinks ?sl ;
        rdfs:label ?label .
  FILTER(?sl >= {min_sitelinks})
  FILTER(LANG(?label) = "en")
}}
GROUP BY ?label
"""


def run(query: str) -> list[tuple[str, int]]:
    url = ENDPOINT + "?" + urllib.parse.urlencode({"query": query})
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "text/csv"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        text = resp.read().decode("utf-8")
    rows = list(csv.reader(io.StringIO(text)))
    return [(r[0], int(float(r[1]))) for r in rows[1:] if len(r) >= 2 and r[1]]


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    for kind, (types, floor) in KINDS.items():
        path = os.path.join(out_dir, f"{kind}.tsv")
        if os.path.exists(path):
            print(f"{kind}: exists, skipped", file=sys.stderr)
            continue
        query = QUERY.format(types=" ".join(types), min_sitelinks=floor)
        for attempt in range(3):
            try:
                rows = run(query)
                break
            except Exception as e:  # timeouts and rate limits: wait and retry
                print(f"{kind}: attempt {attempt + 1} failed: {e}", file=sys.stderr)
                time.sleep(20)
        else:
            continue
        with open(path, "w", encoding="utf-8") as out:
            for label, sitelinks in sorted(rows, key=lambda r: -r[1]):
                out.write(f"{label}\t{sitelinks}\n")
        print(f"{kind}: {len(rows)} labels", file=sys.stderr)
        time.sleep(5)


if __name__ == "__main__":
    main()
