"""Pilot training mix for the English student (private, on the SSD): every non-IRC line of
lm-text/train.txt (WildChat, Persona-Chat, Common Voice, Leipzig) plus a sample of Ubuntu IRC with
bot / log / non-English lines dropped, to about IRC_WORDS words. Every VAL_EVERY-th kept line goes
to val.txt instead of train. Line ranges per source come from lm_text.py's printed counts (in order).
Every line equal (lowercased, letters/digits only) to a benchmark sentence is dropped: the news buckets
come from Leipzig and Synthetic-Persona-Chat repeats test lines in its train split (found 2026-11-15:
196/200 control and 183/190 chat sentences were in the first mix).
Writes lm-text/pilot/{train,val}.txt and prints counts."""
import os, re, random, glob

D = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text'
COUNTS = [('wildchat', 561010), ('persona_chat', 294288), ('common_voice', 1584297),
          ('ubuntu_irc', 49248819), ('leipzig', 1998649)]
IRC_WORDS = 30_000_000
IRC_TOTAL_WORDS = 564_763_185
VAL_EVERY = 200

EN = set('the a an to of and is it i you that in for on this be have do not with but are was so what if can my me just no yes it\'s i\'m don\'t'.split())
BOT = re.compile(r'^(Error:|Passed:|Failed:|New bug|Launchpad bug|bug #?\d|#|\*|\[|<|!|\$|sudo |http)|bug #?\d{4,}|\b\d+%|^\S+:$', re.I)
OKCHARS = re.compile(r"[A-Za-z ,.'?!]")

NORM = lambda t: re.sub(r'[^a-z0-9 ]', '', WS.sub(' ', t.lower())).strip()
WS = re.compile(r'\s+')
EVAL = glob.glob('/home/syn/Documents/Code/AI/keyboard/data/eval/buckets/*.txt') + \
       [f'/home/syn/.claude/jobs/eb2fb8fd/tmp/predbench/{n}.txt' for n in ('chat', 'human')]
HELD = {NORM(l) for f in EVAL for l in open(f, encoding='utf-8') if l.strip()}

def irc_ok(t):
    w = t.split()
    if len(w) < 2 or BOT.search(t): return False
    if sum(1 for c in t if OKCHARS.match(c)) < 0.85 * len(t): return False  # code, paths, non-Latin
    return sum(1 for x in w if x.lower() in EN) >= max(1, len(w) // 8)  # English, not Spanish etc.

# filtered IRC keeps about 85 % of the words (sampled 2026-11-15); sample so the kept IRC lands near IRC_WORDS
p_irc = min(1.0, IRC_WORDS / (0.85 * IRC_TOTAL_WORDS))
rnd = random.Random(7)
os.makedirs(os.path.join(D, 'pilot'), exist_ok=True)
bounds, start = [], 0
for name, n in COUNTS: bounds.append((name, start, start + n)); start += n
stats = {name: [0, 0, 0] for name, _ in COUNTS}; kept = 0
with open(os.path.join(D, 'train.txt'), encoding='utf-8') as f, \
     open(os.path.join(D, 'pilot/train.txt'), 'w', encoding='utf-8') as tr, \
     open(os.path.join(D, 'pilot/val.txt'), 'w', encoding='utf-8') as va:
    src = 0
    for i, line in enumerate(f):
        while i >= bounds[src][2]: src += 1
        name = bounds[src][0]
        t = line.rstrip('\n')
        if NORM(t) in HELD: stats[name][2] += 1; continue
        if name == 'ubuntu_irc' and (rnd.random() > p_irc or not irc_ok(t)): continue
        kept += 1
        (va if kept % VAL_EVERY == 0 else tr).write(t + '\n')
        stats[name][0] += 1; stats[name][1] += len(t.split())
for name, (n, w, h) in stats.items(): print(f'{name:14s} {n:>10,} lines {w:>12,} words  {h:,} benchmark lines dropped', flush=True)
print('total', sum(s[1] for s in stats.values()), 'words; val every', VAL_EVERY)
