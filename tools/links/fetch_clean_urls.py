#!/usr/bin/env python3
"""Fetches Brave's "Copy clean link" list into the app (tools/links/fetch_clean_urls.py).

    tools/links/fetch_clean_urls.py [--commit <brave/adblock-lists commit or branch>]

Source: github.com/brave/adblock-lists, brave-lists/clean-urls.json, MPL-2.0. The file is copied
unchanged (MPL keeps it under its own licence) to app/src/main/assets/dogboard/clean-urls.json, its
licence to assets/dogboard/licenses/Brave-clean-urls-MPL-2.0.txt. DogBoard's own rules (Wikipedia,
Amazon's /ref= path, Instagram posts) live in LinkCleaner.kt, not in this file.
"""
import argparse, json, os, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "dogboard")

ap = argparse.ArgumentParser()
ap.add_argument("--commit", default="master")
a = ap.parse_args()
base = f"https://raw.githubusercontent.com/brave/adblock-lists/{a.commit}/"
data = urllib.request.urlopen(base + "brave-lists/clean-urls.json", timeout=30).read()
rules = json.loads(data)
assert isinstance(rules, list) and all({"include", "params"} <= set(r) for r in rules), "unexpected format"
licence = urllib.request.urlopen(base + "LICENSE", timeout=30).read()
assert licence.startswith(b"Mozilla Public License Version 2.0"), "licence changed: check it before shipping"
open(os.path.join(ASSETS, "clean-urls.json"), "wb").write(data)
open(os.path.join(ASSETS, "licenses", "Brave-clean-urls-MPL-2.0.txt"), "wb").write(licence)
print(f"{len(rules)} rules, {sum(len(r['include']) for r in rules)} site patterns, {sum(len(r['params']) for r in rules)} tags")
