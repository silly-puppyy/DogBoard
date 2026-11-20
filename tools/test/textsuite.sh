#!/bin/bash
# DogBoard long text suite (tier 3: run before releases and after big engine changes; ~50 min on 3 cores).
# Every run decides as the keyboard does (RealTypoEvalTest with DOGBOARD_APP_PATH=1: the engine's filters, letter
# model, misspellings) and prints per-kind lines (short words, capitalised, apostrophes, splits, other):
#   1 centre taps                         spelling typos as typed (the reference numbers)
#   2 centre taps + neural scores          as the phone runs it (weight 0.5; scores from the GPU model)
#   3 touch noise 1.5 x 2.0 mm             sloppy taps
#   4 steady hand 1.0 mm + noise 1.0 mm    taps that miss the same way within a sentence (iOS touch history)
#   5 apostrophe set (1000)                contractions and possessives typed without the apostrophe
#   + word joins / splits (SpaceJoinEvalTest) with each run
# Summary: build/textsuite/<date>.txt (one line per run and kind). Data paths below are this machine's.
#
#   tools/test/textsuite.sh [N]     N typos and N correct words per run (default 6000)
set -euo pipefail
cd "$(dirname "$0")/../.."
N=${1:-6000}
D=/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data
PRED=/home/syn/.claude/jobs/eb2fb8fd/tmp/predbench
OUT=$PWD/build/textsuite; mkdir -p "$OUT" # absolute: the tests run from app/
SUM=$OUT/$(date +%Y-%m-%d-%H%M).txt
echo "# textsuite $(git rev-parse --short HEAD) N=$N $(date -Iseconds)" > "$SUM"

run() { # label, extra env...
  local label=$1; shift
  env "$@" DOGBOARD_APP_PATH=1 DOGBOARD_CHARLM=1 DOGBOARD_MISSPELLINGS=1 DOGBOARD_CONTEXT_END_OMISSION=1 \
    DOGBOARD_REAL_TYPOS=$PRED/aalto-words-clean.tsv DOGBOARD_SPLITS=$PRED/aalto-split-cases.tsv \
    DOGBOARD_NGRAMS=$PWD/app/src/main/assets/dogboard/en.ngrams.bin DOGBOARD_REAL_TYPOS_N=$N DOGBOARD_EVAL_THREADS=2 \
    DOGBOARD_EVAL_ERRORS=$OUT/$label-errors.tsv ANDROID_USER_HOME=$HOME/.config/.android \
    taskset -c 0-2 nice ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest --rerun -i 2>&1 \
    | grep -E "REALTYPO|SPACEJOIN ratio|REALWORD" | sed -E "s/^.*(REALTYPO|SPACEJOIN|REALWORD)/\1/" \
    | sed "s/^/$label /" | tee -a "$SUM" || true
  # (the whole unit suite runs: Gradle's --tests filter doesn't match Kotest names; the evals only run with their data set)
  pkill -f "[K]otlinCompileDaemon" || true
}

run centre DOGBOARD_DUMP_CANDIDATES=$OUT/cands.tsv
# neural scores for run 1's candidates (GPU, one job at a time)
systemd-run --user --scope -q -p CPUQuota=200% -p MemoryMax=8G -p MemorySwapMax=0 \
  "$D/venv-llm/bin/python" tools/lm/neural_scores.py "$D/lm/ckpt/v3-modern12x384.pt" "$OUT/cands.tsv" "$OUT/cands-neural.tsv" >/dev/null 2>&1 \
  && run neural DOGBOARD_NEURAL_SCORES=$OUT/cands-neural.tsv DOGBOARD_NEURAL_WEIGHT=0.5 \
  || echo "neural: skipped (scoring failed)" | tee -a "$SUM"
run noise DOGBOARD_REAL_TYPOS_SIGMA=1.5x2.0
run hand DOGBOARD_REAL_TYPOS_HAND=1.0 DOGBOARD_REAL_TYPOS_SIGMA=1.0x1.0
run apos DOGBOARD_APOSTROPHE_SET=1000
echo "summary: $SUM"
