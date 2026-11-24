#!/usr/bin/env python3
"""Packs a built language pack (tools/i18n/auto_pack.py's folder) for download: dist/<tag>/ with the files the phone
reads and a manifest of their sizes and SHA-256 (PackInstaller checks both), dist/<tag>.tar.xz (the same, one archive
for a release host), and dist/index.json listing every pack.

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
import tarfile

# read only when the snapshot is missing (LanguagePack.hasWords, EngineLoader)
TEXT_ONLY = {'lexicon': 'snapshot_lexicon', 'bigrams': 'snapshot_bigrams'}


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def dist(pack_dir, out, tag=None, base_url=''):
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
    entry = {'tag': tag, 'name': man['name'], 'script': man.get('script', ''), 'version': man['version'],
             'size': sum(v['size'] for v in files.values()), 'manifest_sha256': sha256(os.path.join(dest, 'manifest.json'))}
    # one archive per language for release hosts: xz takes the n-grams to a third (Spanish 56.4 ->
    # 22.0 MB); manifest first, so the phone reads the checksums before the files stream past
    archive = os.path.join(out, f'{tag}.tar.xz')
    # preset 6 (an 8 MiB dictionary): the phone's decoder allocates the whole dictionary, and preset 9's 64 MiB, three
    # updates at a time, ran the app out of heap (2026-11-24; the archives grow ~5 %)
    with tarfile.open(archive + '.tmp', 'w:xz', preset=6) as t:
        for name in ['manifest.json'] + sorted(v['path'] for v in files.values()):
            t.add(os.path.join(dest, name), arcname=name)
    os.replace(archive + '.tmp', archive)
    entry['archive'] = {'file': os.path.basename(archive), 'size': os.path.getsize(archive), 'sha256': sha256(archive)}
    # where the phone fetches it (a release asset: .../releases/download/<release tag>/<tag>.tar.xz); a pack can stay in
    # an older release while others move on, the index says where each one is
    if base_url: entry['archive']['url'] = base_url.rstrip('/') + '/' + entry['archive']['file']
    return entry


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('packs', nargs='+')
    ap.add_argument('--out', required=True)
    ap.add_argument('--tag', help='the tag to publish under (one pack only; default: its manifest tag)')
    ap.add_argument('--base-url', default='', help="the archives' download folder (a release's download URL)")
    args = ap.parse_args()
    if args.tag and len(args.packs) > 1: raise SystemExit('--tag with one pack only')
    index_path = os.path.join(args.out, 'index.json')
    index = json.load(open(index_path, encoding='utf-8')) if os.path.exists(index_path) else {'format': 1, 'packs': []}
    for p in args.packs:
        entry = dist(p, args.out, args.tag, args.base_url)
        index['packs'] = [e for e in index['packs'] if e['tag'] != entry['tag']] + [entry]
        print(f"{entry['tag']}: {entry['size'] / 1e6:.1f} MB, archive {entry['archive']['size'] / 1e6:.1f} MB, version {entry['version']}")
    index['packs'].sort(key=lambda e: e['tag'])
    with open(index_path, 'w', encoding='utf-8') as f:
        json.dump(index, f, ensure_ascii=False, indent=1)
    print('sign it: tools/i18n/sign_index.py sign', index_path)


if __name__ == '__main__':
    main()
