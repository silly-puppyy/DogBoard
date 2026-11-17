#!/bin/bash
# queue10 (2026-11-16 05:25): v3 English student = v2 mix + one-to-one texting (dm, x3), capitalisation as a
# token (CAP A/B won 8/2/1), longer training (45 k steps ~1.5 B tokens; v2's val was still falling at 30 k).
# Starts after queue9 has generated the one-to-one texts.
D=/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data
L=/home/syn/Documents/Code/AI/keyboard/dogboard/tools/lm; B=/home/syn/.claude/jobs/eb2fb8fd/tmp/predbench
export HF_HOME=$D/hf HF_HUB_OFFLINE=1 OMP_NUM_THREADS=2
until grep -q "dm done" /tmp/claude-1000/-home-syn-Documents-Code-AI-keyboard/eb2fb8fd-7646-4213-845e-8d73c47f6e37/tasks/bavfpp9h9.output; do sleep 60; done
nice $D/venv-llm/bin/python $L/tok_prep_v3.py 2>&1 | grep -E "tokens|weights|missing|Error|Traceback"
TOK=v3 nice $D/venv-llm/bin/python $L/typo_aug.py 2>&1 | tail -1
DATA=v3 AUG=0.3 ACCUM=2 $D/venv-llm/bin/python -u $L/train.py modern-12x384 v3-modern12x384 45000 128 2>&1 | grep --line-buffered "^mix\|step [0-9]*000 \|Error\|resumed"
cd $B
CTRL=$HOME/Documents/Code/AI/keyboard/data/eval/buckets/control.txt
for set in human human-noisy control sms sms-na enron so aalto-clean aalto-typed; do
  txt=$set.txt; [ $set = control ] && txt=$CTRL; n=$(case $set in sms|sms-na|enron|so|aalto-*) echo 400;; *) echo 200;; esac)
  $D/venv-llm/bin/python -I student_nwp.py $D/lm/ckpt/v3-modern12x384.pt $txt $n $set/v3-modern12x384.tsv 2>&1 | grep "predictions in\|Error"
  case $set in human-noisy) python3 noisy.py fix human.txt $set/v3-modern12x384.tsv;; aalto-typed) python3 noisy.py fix aalto-clean.txt $set/v3-modern12x384.tsv;; esac
done
$D/venv-llm/bin/python -I blimp_score.py student $D/lm/ckpt/v3-modern12x384.pt 200 blimp/v3-modern12x384.json 2>&1 | grep "BLiMP\|Error"
for set in sms-na enron human; do $D/venv-llm/bin/python -I word_surprisal.py $set.txt 400 $D/lm/ckpt/v3-modern12x384.pt 2>&1 | grep -v "Warning\|warn\|Loading\|t = lambda\|Converting"; done
TEACHER=HuggingFaceTB/SmolLM2-1.7B $D/venv-llm/bin/python -I greedy_compare.py 14 $D/lm/ckpt/v2-modern12x384.pt $D/lm/ckpt/v3-modern12x384.pt 2>&1 | grep -v "Warning\|warn\|Loading\|t = lambda"
echo "v3 done"
