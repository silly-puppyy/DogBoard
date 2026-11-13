#!/usr/bin/env bash
# Downloads the text corpora for the n-gram model into DATA_DIR (not the repo).
#
#   tools/data/fetch_corpora.sh DATA_DIR
#
# WildChat-1M (allenai, ODC-BY 1.0): only the human turns are used later.
# Synthetic-Persona-Chat (google, CC BY 4.0).
# Common Voice English sentences (Mozilla, CC0).
# Ubuntu IRC logs 2004-2025 (common-pile/ubuntu_irc, public domain).
# Existing files are kept, so the script can be re-run after an interruption.
set -euo pipefail
D=${1:?usage: fetch_corpora.sh DATA_DIR}
HF=https://huggingface.co/datasets

mkdir -p "$D/wildchat" "$D/persona_chat" "$D/common_voice"
for i in $(seq -f %05g 0 13); do
  f="$D/wildchat/train-$i-of-00014.parquet"
  [ -s "$f" ] || curl -sSL --retry 3 -o "$f" "$HF/allenai/WildChat-1M/resolve/main/data/train-$i-of-00014.parquet"
done
for f in Synthetic-Persona-Chat_train.csv Synthetic-Persona-Chat_valid.csv Synthetic-Persona-Chat_test.csv New-Persona-New-Conversations.csv; do
  [ -s "$D/persona_chat/$f" ] || curl -sSL --retry 3 -o "$D/persona_chat/$f" "$HF/google/Synthetic-Persona-Chat/resolve/main/data/$f"
done
curl -sS "https://api.github.com/repos/common-voice/common-voice/contents/server/data/en" |
  python3 -I -c "import json,sys; [print(f['download_url']) for f in json.load(sys.stdin) if f['name'].endswith('.txt')]" |
  while read -r url; do
    f="$D/common_voice/$(basename "$url")"
    [ -s "$f" ] || curl -sSL --retry 3 -o "$f" "$url"
  done
mkdir -p "$D/ubuntu_irc"
for i in $(seq -f %05g 0 7); do
  f="$D/ubuntu_irc/${i}_ubuntu.jsonl.gz"
  [ -s "$f" ] || curl -sSL --retry 3 -o "$f" "$HF/common-pile/ubuntu_irc/resolve/main/v0/documents/${i}_ubuntu.jsonl.gz"
done
echo "done: $(du -sh "$D" | cut -f1)"
