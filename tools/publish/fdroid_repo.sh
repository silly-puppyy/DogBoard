#!/usr/bin/env bash
# DogBoard's own F-Droid repository (https://silly-puppyy.github.io/DogBoard-fdroid/repo): a release's APK added,
# the index rebuilt and signed, the site pushed to github.com/silly-puppyy/DogBoard-fdroid (GitHub Pages).
#
#     tools/publish/fdroid_repo.sh DogBoard-1.2.5.apk [CHANGELOG_TXT]
#
# Needs: fdroidserver (FDROID, a venv), the work folder (FDROID_WORK: config.yml with the key passwords, metadata/,
# repo/, site/), the repository key (~/.config/dogboard/fdroid-repo-keystore.p12, named in config.yml). The key and
# config.yml never go into a repository; back both up (a lost key means users add the repository again).
#
# The newest KEEP APKs stay (older ones are removed); the site's history is one commit, replaced each time, so the
# repository doesn't grow by an APK a release.
set -euo pipefail
trap 'echo "fdroid_repo.sh: failed at line $LINENO" >&2' ERR
APK=${1:?APK path}
CHANGELOG=${2:-}
DATA=/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data
FDROID=${FDROID:-$DATA/venv-fdroid/bin/fdroid}
WORK=${FDROID_WORK:-$DATA/fdroid-repo}
KEEP=${KEEP:-2}
APP=io.github.sillypuppyy.dogboard
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}

cp "$APK" "$WORK/repo/"
if [ -n "$CHANGELOG" ]; then
    # the newest build-tools (several are installed: a glob gave aapt2 two paths); sed reads all of aapt2's output (a
    # `head -1` ended the pipe early: aapt2's SIGPIPE failed the pipeline and set -e quit without a word)
    aapt2=$(printf '%s\n' "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)aapt2
    code=$("$aapt2" dump badging "$APK" | sed -n "1s/.*versionCode='\([0-9]*\)'.*/\1/p")
    mkdir -p "$WORK/metadata/$APP/en-US/changelogs"
    cp "$CHANGELOG" "$WORK/metadata/$APP/en-US/changelogs/$code.txt"
fi
# only the newest KEEP DogBoard APKs (by name's version: DogBoard-X.Y.Z.apk)
mapfile -t old < <(cd "$WORK/repo" && printf '%s\n' DogBoard-*.apk | sort -V | head -n -"$KEEP")
for f in "${old[@]}"; do rm -- "$WORK/repo/$f"; done
(cd "$WORK" && "$FDROID" update)
rsync -a --delete --exclude .git --exclude README.md --exclude .nojekyll "$WORK/repo/" "$WORK/site/repo/"
cd "$WORK/site"
git checkout -q --orphan fresh
git add -A
git -c user.name=silly-puppyy -c user.email=347531506+silly-puppyy@users.noreply.github.com commit -q -m "$(basename "$APK" .apk)"
git branch -D main -q
git branch -m main
git push -q --force origin main
echo "pushed: https://silly-puppyy.github.io/DogBoard-fdroid/repo"
