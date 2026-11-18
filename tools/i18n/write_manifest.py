#!/usr/bin/env python3
"""Writes a language pack's manifest (tools/i18n/write_manifest.py).

    tools/i18n/write_manifest.py PACK_SPEC.json [--assets app/src/main/assets] [--out PATH]

PACK_SPEC lists the pack's tag, name, script, version, sources and its components (component -> asset path,
optional flag). The manifest written next to it in assets/dogboard/packs/<tag>/manifest.json adds each file's
size and SHA-256, so the app can refuse a stale or truncated file (LanguagePack.path checks the size) and a
build can be traced to its exact files. Optional files that are missing from this checkout (the neural model
is built privately) are written without size and hash. See notes/design/i18n-pipeline.md.
"""
import argparse
import hashlib
import json
import os
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("spec")
    ap.add_argument("--assets", default="app/src/main/assets")
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    spec = json.load(open(a.spec, encoding="utf-8"))
    files = {}
    for comp, f in spec["files"].items():
        p = os.path.join(a.assets, f["path"])
        entry = {"path": f["path"]}
        for k in ("optional", "curated"):
            if f.get(k):
                entry[k] = True
        if os.path.exists(p):
            h = hashlib.sha256()
            with open(p, "rb") as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b""):
                    h.update(chunk)
            entry["size"] = os.path.getsize(p)
            entry["sha256"] = h.hexdigest()
        elif not f.get("optional"):
            sys.exit(f"missing required file for {comp}: {p}")
        files[comp] = entry
    manifest = {"format": 1, **{k: spec[k] for k in ("tag", "name", "script", "version")}, "files": files,
                "sources": spec.get("sources", [])}
    out = a.out or os.path.join(a.assets, "dogboard", "packs", spec["tag"], "manifest.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    print(f"{out}: {len(files)} components, {sum(1 for f in files.values() if 'size' in f)} present")


if __name__ == "__main__":
    main()
