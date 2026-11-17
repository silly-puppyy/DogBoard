#!/bin/bash
# sequence-level distillation text: Qwen3-1.7B-Base continues 1.6 M pilot-line openings (~40 M tokens,
# ~3.5 h at batch 384), after queue6's GPU benchmarks. Resumable (gen_teacher.py skips done prompts).
D=/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data
export HF_HOME=$D/hf HF_HUB_OFFLINE=1 OMP_NUM_THREADS=1
until grep -q "aalto done" /tmp/claude-1000/-home-syn-Documents-Code-AI-keyboard/eb2fb8fd-7646-4213-845e-8d73c47f6e37/tasks/b7t1r4eb3.output; do sleep 30; done
# (dropped 2026-11-15 22:30: greedy reading showed the pilot-line openings + Qwen continue in the
#  assistant / tech-support register, the opposite of what we lack; its time goes to more group chat)
# then group-chat style text (user 2026-11-15: instead of Discord data), ~200 k prompts ~2 h (cut from 400 k so v2 trains tonight), by SmolLM2-1.7B:
# best measured on real texting (sms-na top-3 38.0 vs Qwen3-1.7B 36.0, Apple 36.1); no GQA, so batch 96 fits
$D/venv-llm/bin/python -u /home/syn/Documents/Code/AI/keyboard/dogboard/tools/lm/gen_groupchat.py HuggingFaceTB/SmolLM2-1.7B 200000 $D/lm-text/teacher/groupchat.tsv 96 2>&1 | grep --line-buffered "tok/s\|Error\|done"
# === v2 English student (user 2026-11-16: "build the english autocorrect llm overnight") ===
# the mix tokenised by tok_prep_v2.py (+ TOK=v2 typo_aug.py) while the group chat generated; add the group chat, train, bench
until [ -s $D/lm-text/v2/tok/weights.json ] && [ -s $D/lm-text/v2/tok/train_typo.mask ] && ! pgrep -f "typo_aug.py" >/dev/null; do sleep 60; done
$D/venv-llm/bin/python /home/syn/Documents/Code/AI/keyboard/dogboard/tools/lm/encode_groupchat_v2.py 2>&1 | tail -1
DATA=v2 AUG=0.3 ACCUM=2 $D/venv-llm/bin/python -u /home/syn/Documents/Code/AI/keyboard/dogboard/tools/lm/train.py modern-12x384 v2-modern12x384 30000 128 2>&1 | grep --line-buffered "^mix\|step [0-9]*000 \|Error\|resumed"
cd /home/syn/.claude/jobs/eb2fb8fd/tmp/predbench
CTRL=$HOME/Documents/Code/AI/keyboard/data/eval/buckets/control.txt
for set in human human-noisy control sms sms-na enron so aalto-clean aalto-typed chat; do
  txt=$set.txt; [ $set = control ] && txt=$CTRL; n=$(case $set in sms|sms-na|enron|so|aalto-*) echo 400;; *) echo 200;; esac)
  $D/venv-llm/bin/python -I student_nwp.py $D/lm/ckpt/v2-modern12x384.pt $txt $n $set/v2-modern12x384.tsv 2>&1 | grep "predictions in\|Error"
  case $set in human-noisy) python3 noisy.py fix human.txt $set/v2-modern12x384.tsv;; aalto-typed) python3 noisy.py fix aalto-clean.txt $set/v2-modern12x384.tsv;; esac
done
$D/venv-llm/bin/python -I blimp_score.py student $D/lm/ckpt/v2-modern12x384.pt 200 blimp/v2-modern12x384.json 2>&1 | grep "BLiMP\|Error"
for set in sms-na enron human; do $D/venv-llm/bin/python -I word_surprisal.py $set.txt 400 $D/lm/ckpt/v2-modern12x384.pt 2>&1 | grep -v "Warning\|warn\|Loading\|t = lambda"; done
HF_HOME=$D/hf HF_HUB_OFFLINE=1 TEACHER=HuggingFaceTB/SmolLM2-1.7B $D/venv-llm/bin/python -I greedy_compare.py 14 $D/lm/ckpt/base-modern12x384.pt $D/lm/ckpt/v2-modern12x384.pt 2>&1 | grep -v "Warning\|warn\|Loading\|t = lambda"
echo "v2 done"
cd /home/syn/Documents/Code/AI/keyboard/dogboard/tools/lm
# CAP-token A/B (user 2026-11-15, Apple's scheme): same shape / steps / data as base-modern12x384, caps.py tokenisation
until [ -s $D/lm-text/pilot-caps/train.bin ] && ! [ -f $D/lm-text/pilot-caps/sp_sample.txt ]; do sleep 30; done
TOKDIR=$D/lm-text/pilot-caps CAPS=1 ACCUM=2 $D/venv-llm/bin/python -u /home/syn/Documents/Code/AI/keyboard/dogboard/tools/lm/train.py modern-12x384 caps-modern12x384 8000 128 2>&1 | grep --line-buffered "step 8000\|Error\|resumed"
B=/home/syn/.claude/jobs/eb2fb8fd/tmp/predbench; cd $B
CTRL=$HOME/Documents/Code/AI/keyboard/data/eval/buckets/control.txt
for set in human human-noisy control control-noisy sms sms-na enron so aalto-clean aalto-typed; do
  txt=$set.txt; [ $set = control ] && txt=$CTRL; n=$(case $set in sms|sms-na|enron|so|aalto-*) echo 400;; *) echo 200;; esac)
  $D/venv-llm/bin/python -I student_nwp.py $D/lm/ckpt/caps-modern12x384.pt $txt $n $set/caps-modern12x384.tsv 2>&1 | grep "predictions in\|Error"
  case $set in human-noisy) python3 noisy.py fix human.txt $set/caps-modern12x384.tsv;; control-noisy) python3 noisy.py fix $CTRL $set/caps-modern12x384.tsv;; aalto-typed) python3 noisy.py fix aalto-clean.txt $set/caps-modern12x384.tsv;; esac
done
$D/venv-llm/bin/python -I blimp_score.py student $D/lm/ckpt/caps-modern12x384.pt 200 blimp/caps-modern12x384.json 2>&1 | grep "BLiMP\|Error"
echo "caps A/B done"
# FineWeb-only ablation (user 2026-11-15: "dilute a model on just general internet text"): same shape,
# steps, tokenizer and token budget as base-modern12x384, only the text differs; then the unseen sets
until [ -s $D/lm-text/v2/fineweb.txt ]; do sleep 60; done
$D/venv-llm/bin/python /home/syn/Documents/Code/AI/keyboard/dogboard/tools/lm/tok_fineweb.py 2>&1 | tail -1
TOKDIR=$D/lm-text/fineweb-only ACCUM=2 $D/venv-llm/bin/python -u /home/syn/Documents/Code/AI/keyboard/dogboard/tools/lm/train.py modern-12x384 fineweb-modern12x384 8000 128 2>&1 | grep --line-buffered "step 8000\|Error"
cd /home/syn/.claude/jobs/eb2fb8fd/tmp/predbench
for set in human sms sms-na enron so; do
  n=$(case $set in human) echo 200;; *) echo 400;; esac)
  $D/venv-llm/bin/python -I student_nwp.py $D/lm/ckpt/fineweb-modern12x384.pt $set.txt $n $set/fineweb-modern12x384.tsv 2>&1 | grep "predictions in\|Error"
done
for set in sms-na enron human; do $D/venv-llm/bin/python -I word_surprisal.py $set.txt 400 $D/lm/ckpt/fineweb-modern12x384.pt 2>&1 | grep "bits/word\|words:"; done
echo "fineweb ablation done"
