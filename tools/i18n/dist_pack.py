#!/usr/bin/env python3
"""Packs a built language pack (tools/i18n/auto_pack.py's folder) for download: dist/<tag>/ with the files the phone
reads and a manifest of their sizes and SHA-256 (PackInstaller checks both), and dist/index.json listing every pack.

    tools/i18n/dist_pack.py PACK_DIR [PACK_DIR ...] --out DIST [--tag TAG]

The text word lists (lexicon, bigrams TSVs) are left out when the pack has their snapshots: the phone reads only the
snapshots then (LanguagePack.hasWords; Spanish 45 -> 34 MB). Where the files are hosted is the uploader's business: a
pack's files are fetched by their path relative to its folder, so any host that serves the folder works.
"""
import argparse
import hashlib
import json
import os
import shutil

# read only when the snapshot is missing (LanguagePack.hasWords, EngineLoader)
TEXT_ONLY = {'lexicon': 'snapshot_lexicon', 'bigrams': 'snapshot_bigrams'}


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def dist(pack_dir, out, tag=None):
    man = json.load(open(os.path.join(pack_dir, 'manifest.json'), encoding='utf-8'))
    tag = tag or man['tag']
    dest = os.path.join(out, tag)
    os.makedirs(dest, exist_ok=True)
    files = {}
    for comp, f in man['files'].items():
        if comp in TEXT_ONLY and TEXT_ONLY[comp] in man['files']:
            continue
        src = os.path.join(pack_dir, f['path'])
        if not os.path.exists(src):
            if f.get('optional'): continue
            raise SystemExit(f'{tag}: {f["path"]} missing')
        shutil.copyfile(src, os.path.join(dest, os.path.basename(f['path'])))
        # flat: every file in the pack's own folder, so a host needs no subfolders
        files[comp] = {'path': os.path.basename(f['path']), 'size': os.path.getsize(src), 'sha256': sha256(src)}
    man = dict(man, tag=tag, files=files)
    with open(os.path.join(dest, 'manifest.json'), 'w', encoding='utf-8') as f:
        json.dump(man, f, ensure_ascii=False, indent=1)
    return {'tag': tag, 'name': man['name'], 'script': man.get('script', ''), 'version': man['version'],
            'size': sum(v['size'] for v in files.values()), 'manifest_sha256': sha256(os.path.join(dest, 'manifest.json'))}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('packs', nargs='+')
    ap.add_argument('--out', required=True)
    ap.add_argument('--tag', help='the tag to publish under (one pack only; default: its manifest tag)')
    args = ap.parse_args()
    if args.tag and len(args.packs) > 1: raise SystemExit('--tag with one pack only')
    index_path = os.path.join(args.out, 'index.json')
    index = json.load(open(index_path, encoding='utf-8')) if os.path.exists(index_path) else {'format': 1, 'packs': []}
    for p in args.packs:
        entry = dist(p, args.out, args.tag)
        index['packs'] = [e for e in index['packs'] if e['tag'] != entry['tag']] + [entry]
        print(f"{entry['tag']}: {entry['size'] / 1e6:.1f} MB, version {entry['version']}")
    index['packs'].sort(key=lambda e: e['tag'])
    with open(index_path, 'w', encoding='utf-8') as f:
        json.dump(index, f, ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
