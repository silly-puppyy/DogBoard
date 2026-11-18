#!/bin/bash
# Fine-tune the real-data recogniser with finger-model swipes across keyboard shapes and scales, then
# score it at normal size (FUTO test, How We Swipe), on real swipes by keyboard shape, and on simulated extremes.
D=/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data
PY=$D/venv-llm/bin/python
EN=$(cd "$(dirname "$0")/../.." && pwd)/app/src/main/assets/dogboard/en.lexicon.tsv
cd "$(dirname "$0")"
taskset -c 1 nice python3 sizes.py make --futo $D/swipe/futo --finger $D/swipe/pursuit-v1.json --lexicon $EN --out $D/swipe/synth-sizes -n 300000 2>&1 | tail -1
taskset -c 2 nice $PY train.py --data $D/swipe/prepared-v3 --extra $D/swipe/synth-sizes --init $D/swipe/runs/v3-long/best.pt --out $D/swipe/runs/v4-sizes --epochs 8 --lr 5e-4 --augment --data-name futo-v3+finger-sizes 2>&1 | grep "^epoch [0-9]*:"
echo "== v4-sizes"
taskset -c 1 nice $PY evaluate.py --model $D/swipe/runs/v4-sizes/best.pt --lexicon $EN --prepared $D/swipe/prepared --hws $D/swipe/prepared/hws.pt 2>&1 | grep top-1
taskset -c 1 nice python3 shape_bins.py --futo $D/swipe/futo --model $D/swipe/runs/v4-sizes/best.pt --lexicon $EN
taskset -c 1 nice python3 sizes.py eval --futo $D/swipe/futo --finger $D/swipe/pursuit-v1.json --lexicon $EN --model $D/swipe/runs/v4-sizes/best.pt -n 1000
echo "SIZE TUNE DONE"
