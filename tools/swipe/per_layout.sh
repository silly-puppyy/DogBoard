#!/bin/bash
# Per-layout models trained only on the stitched human model (physical units), tested on real swipes.
D=/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data
PY=$D/venv-llm/bin/python
L=$D/swipe/futo/swipe-5/layouts
EN=/home/syn/Documents/Code/AI/keyboard/dogboard-batch8/app/src/main/assets/dogboard/en.lexicon.tsv
cd /home/syn/Documents/Code/AI/keyboard/dogboard-batch8/tools/swipe
taskset -c 1 nice $PY prepare_layouts.py --futo $D/swipe/futo --out $D/swipe/layouts-phys --units phys 2>&1 | grep -v Warn | grep -E "clearflow|dvorak|german|qwerty-en"
# real-data reference: v3 (grid units) on swipe-5's real QWERTY swipes
DOGBOARD_WANDB=0 taskset -c 1 nice $PY eval_layout_model.py --model $D/swipe/runs/v3-long/best.pt --layout $L/qwerty.json --test $D/swipe/layouts/qwerty-en.pt --lexicon $EN -n 3000 2>&1 | grep -v Warn | sed 's/^/REFERENCE v3 real-trained /'
for spec in "qwerty en 0.674 $EN" "clearflow en 0.756 $EN" "dvorak en 0.674 $EN" "german de 0.68 $D/swipe/lexicons/de.tsv"; do
  set -- $spec; lay=$1; lang=$2; asp=$3; lex=$4
  out=$D/swipe/synth-phys-$lay
  taskset -c 1,3 nice $PY prepare_synth.py --lexicon $lex --layout $L/$lay.json --out $out -n 400000 --units phys --aspect $asp --human lib:$D/swipe/stitch-lib-phys.pkl --workers 2 2>&1 | grep -v Warn | tail -1
  taskset -c 2 nice $PY train.py --data $out --out $D/swipe/runs/human-$lay-$lang --epochs 16 --augment --alphabet $L/$lay.json --lang $lang --layout $lay --data-name stitched-phys 2>&1 | grep --line-buffered "^epoch 15:\|Error\|Traceback"
  taskset -c 1 nice $PY eval_layout_model.py --model $D/swipe/runs/human-$lay-$lang/best.pt --layout $L/$lay.json --test $D/swipe/layouts-phys/$lay-$lang.pt --lexicon $lex --lang $lang -n 3000 2>&1 | grep -v Warn | sed "s/^/HUMAN-ONLY $lay-$lang /"
done
