#!/bin/bash
# Keyboard comparison on real typos (CaseBenchUiTest): the same Aalto cases typed on DogBoard and on the emulator's
# Gboard with the same taps (key centres, 140 ms apart), scored fixed / left / wrong for typos and kept / broken for
# correctly typed words. Emulator only (the test forgets learned words and clears Gboard's data).
#
#   tools/test/casebench.sh <pairs.tsv> <n per kind> [keyboards...]     default keyboards: dogboard gboard
# The test APKs must be built and installed (tools/test/uitest.sh local builds them).
set -euo pipefail
cd "$(dirname "$0")/../.."
PAIRS=$1; N=${2:-200}; shift 2 || true
KEYBOARDS=("$@"); [[ ${#KEYBOARDS[@]} -eq 0 ]] && KEYBOARDS=(dogboard gboard)
ADB="/opt/android-sdk/platform-tools/adb -s ${SERIAL:-emulator-5554}"
OUT=$PWD/build/casebench; mkdir -p "$OUT"

python3 - "$PAIRS" "$N" "$OUT" <<'EOF'
import random, re, sys
pairs, n, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
typos, correct = [], []
for line in open(pairs, encoding='utf-8'):
    r = line.rstrip('\n').split('\t')
    if len(r) != 5 or not (r[3].isalpha() and r[3].isascii() and r[4].isalpha()): continue
    (correct if r[3].lower() == r[4].lower() else typos).append(r)
rnd = random.Random(7)
cases = rnd.sample(typos, n) + rnd.sample(correct, n)
with open(f'{out}/cases.tsv', 'w') as dev, open(f'{out}/key.tsv', 'w') as key:
    for i, r in enumerate(cases):
        ctx = ' '.join(re.sub('[^a-z ]', '', r[2].lower()).split()[-3:])
        dev.write(f'{i}\t{ctx}\t{r[3].lower()}\n')
        key.write(f'{i}\t{r[3].lower()}\t{r[4]}\t{"typo" if i < n else "correct"}\n')
EOF
$ADB push "$OUT/cases.tsv" /data/local/tmp/dogboard-cases.tsv >/dev/null

for k in "${KEYBOARDS[@]}"; do
  $ADB logcat -c
  $ADB shell am instrument -w -r -e keyboard "$k" -e casebench 1 \
    -e class io.github.sillypuppyy.dogboard.uitest.CaseBenchUiTest \
    io.github.sillypuppyy.dogboard.debug.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/$k-instrument.txt"
  $ADB pull /sdcard/Android/data/io.github.sillypuppyy.dogboard.debug/files/casebench/results.txt "$OUT/$k-results.txt" >/dev/null
  python3 - "$OUT/key.tsv" "$OUT/$k-results.txt" "$k" <<'EOF'
import collections, sys
key = {l.split('\t')[0]: l.rstrip('\n').split('\t')[1:] for l in open(sys.argv[1])}
got = {}
for l in open(sys.argv[2]):
    p = l.split(' ', 2)
    if len(p) >= 2: got[p[1]] = p[2].strip() if len(p) > 2 else ''
c = collections.Counter()
for i, (typed, want, kind) in key.items():
    if i not in got: c[kind, 'missing'] += 1; continue
    if got[i].startswith('HARNESS:'): c[kind, 'missing'] += 1; c['harness'] += 1; continue
    g = got[i].lower()
    if kind == 'typo': c[kind, 'fixed' if g == want.lower() else 'left' if g == typed else 'wrong'] += 1
    else: c[kind, 'kept' if g == want.lower() else 'broken'] += 1
nt = sum(v for k, v in c.items() if isinstance(k, tuple) and k[0] == 'typo'); nc = sum(v for k, v in c.items() if isinstance(k, tuple) and k[0] == 'correct')
pct = lambda k, o, n: f"{100 * c[k, o] / max(1, n):.1f}%"
print(f"{sys.argv[3]}: {nt} typos: fixed {pct('typo','fixed',nt)}, left {pct('typo','left',nt)}, wrong {pct('typo','wrong',nt)}"
      f" (missing {c['typo','missing']}, harness errors {c['harness']}) | {nc} correct: kept {pct('correct','kept',nc)}, broken {pct('correct','broken',nc)}")
EOF
done
$ADB shell ime set io.github.sillypuppyy.dogboard.debug/dev.patrickgold.florisboard.FlorisImeService >/dev/null
