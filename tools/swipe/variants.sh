#!/bin/bash
# Human-model variants on QWERTY: train on synthetic only, score on the selection set (FUTO dev, real)
# and on swipe-5's real QWERTY test.   variants.sh NAME=GENERATOR ...  (env: N, EPOCHS, ASPECT, ASPECT_MAX)
D=/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data
PY=$D/venv-llm/bin/python
L=$D/swipe/futo/swipe-5/layouts
EN=$(cd "$(dirname "$0")/../.." && pwd)/app/src/main/assets/dogboard/en.lexicon.tsv
cd "$(dirname "$0")"
for spec in "$@"; do
  name=${spec%%=*}; gen=${spec#*=}
  out=$D/swipe/synth-var-$name
  taskset -c 1,3 nice $PY prepare_synth.py --lexicon $EN --layout $L/qwerty.json --out $out -n ${N:-400000} --units phys --aspect ${ASPECT:-0.40} --aspect-max ${ASPECT_MAX:-0.80} --human $gen --workers 2 2>&1 | tail -1 > /dev/null
  taskset -c 2 nice $PY train.py --data $out --out $D/swipe/runs/var-$name --epochs ${EPOCHS:-16} --augment --alphabet $L/qwerty.json --layout qwerty --name var-$name --data-name "$gen" 2>&1 | grep "^epoch $((${EPOCHS:-16} - 1)):"
  for t in futo-dev-qwerty qwerty-en; do
    taskset -c 1 nice $PY eval_layout_model.py --model $D/swipe/runs/var-$name/best.pt --layout $L/qwerty.json --test $D/swipe/layouts-phys/$t.pt --lexicon $EN -n 3000 --name var-$name 2>&1 | grep top-1 | sed "s/^/VARIANT $name $t /"
  done
  rm -rf $out   # synthetic data is cheap to remake; keep the drive tidy
done
