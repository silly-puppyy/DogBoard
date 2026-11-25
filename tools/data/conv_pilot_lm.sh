#!/bin/sh
# KenLM 3-gram per text of the conversational-source pilot, the packs' lmplz call (auto_pack.py step 4).
#   conv_pilot_lm.sh DATA_DIR LANG NAME...      (NAME = A, B-40, ...: pilot/LANG/NAME.txt -> NAME.arpa)
D=$1; L=$2; shift 2
K=$D/kenlm/build/bin/lmplz; P=$D/conv-sources/pilot/$L
mkdir -p $P/tmp
for N in "$@"; do
  [ -f $P/$N.arpa ] && continue
  while [ "$(free -g | awk 'NR==2{print $7}')" -lt 8 ]; do sleep 60; done
  taskset -c 8-13 nice $K -o 3 -S 3G -T $P/tmp --prune 0 0 1 < $P/$N.txt > $P/$N.arpa.tmp 2> $P/$N.lmplz.log && mv $P/$N.arpa.tmp $P/$N.arpa
done
