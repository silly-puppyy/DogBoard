#!/bin/bash
# queue9 (2026-11-16 04:45): after queue8 (CAP A/B + FineWeb ablation), one-to-one texting about plans,
# times and errands from SmolLM2-1.7B (gen_groupchat.py STYLE=dm), for the next (v3) mix
D=/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data
export HF_HOME=$D/hf HF_HUB_OFFLINE=1 OMP_NUM_THREADS=1
until grep -q "fineweb ablation done" /tmp/claude-1000/-home-syn-Documents-Code-AI-keyboard/eb2fb8fd-7646-4213-845e-8d73c47f6e37/tasks/b14o47skr.output; do sleep 60; done
STYLE=dm $D/venv-llm/bin/python -u /home/syn/Documents/Code/AI/keyboard/dogboard/tools/lm/gen_groupchat.py HuggingFaceTB/SmolLM2-1.7B 300000 $D/lm-text/teacher/dm.tsv 96 2>&1 | grep --line-buffered "to go\|Error"
echo "dm done: $(wc -l < $D/lm-text/teacher/dm.tsv) messages"
