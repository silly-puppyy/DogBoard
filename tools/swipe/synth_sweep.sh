#!/bin/bash
# Synthetic-only swipe models on QWERTY, tested on real swipes (user 2026-11-18: "try getting simulated accuracy
# (against real data, unseen) higher"). Each variant: N synthetic swipes from a generator, a model trained on
# them alone, top-1 on swipe-5's 3,000 real QWERTY swipes (never used to fit anything). Baselines on the same
# test: real-trained 82.8, pursuit-v1 73.8, stitched (real pieces) 76.9.
#   synth_sweep.sh TAG "GEN [GEN2 ...]" [N] [EPOCHS]
#   EXTRA_PREP (env): more prepare_synth.py options (e.g. "--aspect 0.55 --aspect-max 0.8", "--uniform 0.2")
#   GEN: finger:PARAMS.json (physics.py), h1 / h2, lib:LIBRARY.pkl; two = equal shares mixed (train.py --extra)
D=/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data
PY=$D/venv-llm/bin/python
L=$D/swipe/futo/swipe-5/layouts
EN=/home/syn/Documents/Code/AI/keyboard/dogboard-batch8/app/src/main/assets/dogboard/en.lexicon.tsv
TAG=$1; GENS=($2); N=${3:-400000}; EPOCHS=${4:-16}
cd "$(dirname "$0")"
share=$((N / ${#GENS[@]}))
dirs=()
for i in "${!GENS[@]}"; do
  out=$D/swipe/synth-$TAG-$i
  [ -f "$out/done" ] || { taskset -c 1,3 nice $PY prepare_synth.py --lexicon $EN --layout $L/qwerty.json --out $out -n $share --units phys --aspect 0.674 --human "${GENS[$i]}" --workers 2 $EXTRA_PREP 2>&1 | grep -v Warn | tail -1 && touch "$out/done"; }
  dirs+=("$out")
done
extra=""; [ ${#dirs[@]} -gt 1 ] && extra="--extra ${dirs[1]}"   # train.py mixes one extra folder
[ ${#dirs[@]} -gt 2 ] && { echo "at most two generators"; exit 1; }
taskset -c 2 nice $PY train.py --data ${dirs[0]} $extra --out $D/swipe/runs/sweep-$TAG --epochs $EPOCHS --augment --alphabet $L/qwerty.json --lang en --layout qwerty --data-name "$2" 2>&1 | grep --line-buffered "^epoch $((EPOCHS - 1)):\|Error\|Traceback"
taskset -c 1 nice $PY eval_layout_model.py --model $D/swipe/runs/sweep-$TAG/best.pt --layout $L/qwerty.json --test $D/swipe/layouts-phys/qwerty-en.pt --lexicon $EN -n 3000 2>&1 | grep -v Warn | grep -v "wandb:\|shutdown" | sed "s/^/SWEEP $TAG /"
