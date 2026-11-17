#!/bin/bash
# queue12 (2026-11-16 15:36): v4ft = v3 fine-tuned 6 k steps with typos from the realistic simulator
# (noisy_sim / sim6, validated on real phone typos), then the benches v4ft vs v3.
D=/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data
L=/home/syn/Documents/Code/AI/keyboard/dogboard/tools/lm; B=/home/syn/.claude/jobs/eb2fb8fd/tmp/predbench
export HF_HOME=$D/hf HF_HUB_OFFLINE=1 OMP_NUM_THREADS=2
TOK=v3 TYPOS=sim AUGNAME=train_typo_sim nice $D/venv-llm/bin/python $L/typo_aug.py 2>&1 | tail -1
DATA=v3 AUG=0.3 ACCUM=2 AUGNAME=train_typo_sim INIT=$D/lm/ckpt/v3-modern12x384.pt LR=3e-4 WARMUP=200 \
  $D/venv-llm/bin/python -u $L/train.py modern-12x384 v4ft-sim 6000 128 2>&1 | grep --line-buffered "^mix\|step [0-9]*000 \|Error\|initialised"
cd $B
for set in sms-na sms-na-sim human human-sim aalto-typed; do
  $D/venv-llm/bin/python -I student_nwp.py $D/lm/ckpt/v4ft-sim.pt $set.txt 400 $set/v4ft-sim.tsv 2>&1 | grep "Error"
  case $set in human-sim) python3 noisy.py fix human.txt $set/v4ft-sim.tsv;; sms-na-sim) python3 noisy.py fix sms-na.txt $set/v4ft-sim.tsv;; aalto-typed) python3 noisy.py fix aalto-clean.txt $set/v4ft-sim.tsv;; esac
  echo "== $set"; python3 score.py v3=$set/v3-modern12x384.tsv v4ft=$set/v4ft-sim.tsv apple=$set/apple.tsv 2>/dev/null | tail -3
done
$D/venv-llm/bin/python -I blimp_score.py student $D/lm/ckpt/v4ft-sim.pt 200 blimp/v4ft-sim.json 2>&1 | grep "BLiMP\|Error"
echo "queue12 done"
