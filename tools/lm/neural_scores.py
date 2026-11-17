"""v3's ln P(candidate | true words before) for every candidate in a RealTypoEvalTest dump
(DOGBOARD_DUMP_CANDIDATES), for DOGBOARD_NEURAL_SCORES (the eval then refines the candidates with the
neural model as iOS does and applies the real autocorrect rules). Private study, GPU.
Usage: neural_scores.py CKPT dump.tsv out.tsv"""
import os, sys
sys.argv, args = sys.argv[:1] + [sys.argv[1], '/dev/null'], sys.argv[1:]
# reuse rescore_eval's model loading and neural_lp without running its study
src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'rescore_eval.py')).read().split('\ncases = []')[0]
exec(compile(src, 'rescore_eval.py', 'exec'))
seen = set()
with open(args[2], 'w', encoding='utf-8') as out:
    for line in open(args[1], encoding='utf-8'):
        ctx, typed, intended, outcome, cands = line.rstrip('\n').split('\t')
        words = [c.rsplit(',', 3)[0] for c in cands.split('|') if c]
        key = (ctx, typed.lower())
        if not words or key in seen: continue
        seen.add(key)
        for w, p in zip(words, neural_lp(ctx, words)):
            out.write(f'{ctx}\t{typed.lower()}\t{w}\t{p:.4f}\n')
