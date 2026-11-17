"""v3's ln P(previous + next | true words before) for the two-word readings a RealTypoEvalTest run wrote
(DOGBOARD_RETRO_DUMP: context TAB "previous next"), for DOGBOARD_RETRO_NEURAL. Private study, GPU.
Usage: retro_scores.py CKPT retro-dump.tsv out.tsv"""
import os, sys, collections
sys.argv, args = sys.argv[:1] + [sys.argv[1], '/dev/null'], sys.argv[1:]
src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'rescore_eval.py')).read().split('\ncases = []')[0]
exec(compile(src, 'rescore_eval.py', 'exec'))
by_ctx = collections.defaultdict(list)
for line in open(args[1], encoding='utf-8'):
    ctx, phrase = line.rstrip('\n').split('\t')
    by_ctx[ctx].append(phrase)
with open(args[2], 'w', encoding='utf-8') as out:
    for ctx, phrases in by_ctx.items():
        for w, p in zip(phrases, neural_lp(ctx, phrases)):
            out.write(f'{ctx}\t{w}\t{p:.4f}\n')
