#!/bin/bash
# queue13 (2026-11-16 16:30): control for v4ft-old: the same 6 k-step fine-tune of v3 with the OLD typo noise (train_typo), so the gain can be put on the simulator, not the extra steps.
# (noisy_sim / sim6, validated on real phone typos), then the benches v4ft vs v3.
D=/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data
L=/home/syn/Documents/Code/AI/keyboard/dogboard/tools/lm; B=/home/syn/.claude/jobs/eb2fb8fd/tmp/predbench
export HF_HOME=$D/hf HF_HUB_OFFLINE=1 OMP_NUM_THREADS=2
DATA=v3 AUG=0.3 ACCUM=2 AUGNAME=train_typo INIT=$D/lm/ckpt/v3-modern12x384.pt LR=3e-4 WARMUP=200 \
  $D/venv-llm/bin/python -u $L/train.py modern-12x384 v4ft-old 6000 128 2>&1 | grep --line-buffered "^mix\|step [0-9]*000 \|Error\|initialised"
cd $B
for set in sms-na sms-na-sim human human-sim aalto-typed; do
  $D/venv-llm/bin/python -I student_nwp.py $D/lm/ckpt/v4ft-old.pt $set.txt 400 $set/v4ft-old.tsv 2>&1 | grep "Error"
  case $set in human-sim) python3 noisy.py fix human.txt $set/v4ft-old.tsv;; sms-na-sim) python3 noisy.py fix sms-na.txt $set/v4ft-old.tsv;; aalto-typed) python3 noisy.py fix aalto-clean.txt $set/v4ft-old.tsv;; esac
  echo "== $set"; python3 score.py v3=$set/v3-modern12x384.tsv v4ftsim=$set/v4ft-sim.tsv v4ftold=$set/v4ft-old.tsv apple=$set/apple.tsv 2>/dev/null | tail -4
done
$D/venv-llm/bin/python -I blimp_score.py student $D/lm/ckpt/v4ft-old.pt 200 blimp/v4ft-old.json 2>&1 | grep "BLiMP\|Error"
echo "queue13 done"
