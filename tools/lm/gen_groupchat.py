"""Group-chat style text from the teacher (private study, GPU; user 2026-11-15 instead of Discord
data): the base model sees a short made-up group chat (2-4 people with random first names, a random
everyday topic, 3 seed lines written here) and writes the next messages. Kept: the generated lines
of the form "Name: message", the name stripped. Every example is invented: no real messages.
Resumable: appends; the first column is the prompt index.
Usage: gen_groupchat.py MODEL_ID n_prompts out.tsv [batch]
SEEDS=seeds/<lang>.json: another language's names, topics, seed lines and headings (texting style of that
language; 2026-11-22, user: "we can always make our own synthetic data" for languages short of chat text)."""
import os, re, sys, random, time, torch
from transformers import AutoModelForCausalLM, AutoTokenizer

model_id, n_prompts, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
BATCH = int(sys.argv[4]) if len(sys.argv) > 4 else 256
MAX_NEW = 120
NAMES = ('Sam Alex Jordan Maya Leo Priya Chris Nina Omar Tess Ben Zoe Ravi Kim Luis Ana Jay Mia Theo Lena '
         'Dan Ivy Max Ruby Eli Noor Kai Jess Tom Ada Finn Sara Joe Lily Ari Beth Cole Dee Gus Hana').split()
TOPICS = [
    ('dinner plans', ['where we eating tonight', 'idk im down for anything', 'not pizza again pls']),
    ('running late', ['omw sorry', 'how late', 'like 10 min traffic is insane']),
    ('a game last night', ['did u see that ending', 'no way that was real', 'i screamed']),
    ('weekend trip', ['so are we still doing saturday', 'yes!! what time', 'early ish? 9?']),
    ('homework', ['anyone done the reading', 'lol no', 'its due tmrw right?']),
    ('a new show', ['ok i started it', 'and??', 'episode 2 is so good']),
    ('moving apartments', ['need help moving sat', 'how many boxes we talking', 'too many tbh']),
    ('birthday gift', ['what are we getting her', 'maybe a plant', 'she killed the last one 😭']),
    ('work stuff', ['meeting got moved to 3', 'ugh fine', 'can someone send the doc']),
    ('the gym', ['gym later?', 'my legs are dead from tuesday', 'arms day then']),
    ('a concert', ['tickets go on sale at 10', 'set alarms', 'who has the fastest wifi lol']),
    ('family dinner', ['mom says dinner at 6', 'should i bring something', 'dessert maybe']),
    ('a lost phone', ['has anyone seen my charger', 'which one', 'the long one']),
    ('weather', ['its pouring here', 'same lol', 'so much for the picnic']),
    ('a funny video', ['im crying at this', 'send it', 'just did check the chat']),
    ('a group project', ['who is doing the slides', 'i can do intro', 'ill take the data part']),
]
# STYLE=dm: one-to-one texts about plans, times and errands (where Apple's model is strongest:
# "going to be home today?", "Thursday at 10 AM?", "office tomorrow"); same made-up seeds rule
DM_TOPICS = [
    ('picking someone up', ['what time should i get you', 'around 5:30?', 'ok text me when you land']),
    ('dinner tonight', ['are you home for dinner', 'yeah by 7 probably', 'want me to grab something']),
    ('moving a meeting', ['can we push our call to thursday', 'sure what time works', '10am?']),
    ('running errands', ['are you near the store', 'just left, need anything?', 'milk and bread pls']),
    ('a doctor appointment', ['how did the appointment go', 'fine, have to go back next week', 'want me to drive you']),
    ('weekend plans', ['what are you doing saturday', 'nothing yet why', 'brunch?']),
    ('a late arrival', ['running 10 min late sorry', 'no worries', 'grab me a coffee if you see one']),
    ('a package', ['did my package come today', 'yeah its on the porch', 'thank you!!']),
    ('the kids', ['can you get the kids from school', 'what time do they get out', '3:15']),
    ('work schedule', ['are you working tomorrow', 'yeah 9 to 5', 'ok lets do dinner after']),
    ('a birthday', ['happy birthday!! hope you have the best day', 'aww thank you', 'drinks tonight?']),
    ('checking in', ['hey how are you feeling', 'better today', 'good, call me later if you want']),
    ('a trip', ['what time is your flight', '6am ugh', 'ill set an alarm and drive you']),
    ('the car', ['the car is making a weird noise again', 'take it in tomorrow?', 'yeah ill call them']),
    ('house stuff', ['did you pay the electric bill', 'not yet, ill do it tonight', 'thanks']),
    ('meeting up', ['where are you', 'by the entrance', 'omw']),
]
HEADS = ('Group chat ({n} friends, about {topic})', 'Text messages between {a} and {b} ({topic})')
if os.environ.get('SEEDS'):
    import json
    _s = json.load(open(os.environ['SEEDS'], encoding='utf-8'))
    NAMES, TOPICS, DM_TOPICS = _s['names'], [tuple(t) for t in _s['group']], [tuple(t) for t in _s['dm']]
    HEADS = (_s['group_head'], _s['dm_head'])
if os.environ.get('STYLE') == 'dm': TOPICS = DM_TOPICS
tok = AutoTokenizer.from_pretrained(model_id); tok.padding_side = 'left'
if tok.pad_token is None: tok.pad_token = tok.eos_token
model = AutoModelForCausalLM.from_pretrained(model_id, dtype=torch.bfloat16).cuda().eval()
LINE = re.compile(r'^([^\W\d_]{2,12}): (.{1,300})$')  # a name of any alphabet

def prompt(i):
    r = random.Random(i)
    topic, seeds = r.choice(TOPICS)
    people = r.sample(NAMES, 2 if os.environ.get('STYLE') == 'dm' else r.randint(2, 4))
    lines = [f'{people[k % len(people)]}: {s}' for k, s in enumerate(seeds)]
    head = HEADS[1].format(a=people[0], b=people[1], topic=topic) if os.environ.get('STYLE') == 'dm' \
        else HEADS[0].format(n=len(people), topic=topic)
    return head + '\n\n' + '\n'.join(lines) + '\n'

done = set()
if os.path.exists(out): done = {int(l.split('\t', 1)[0]) for l in open(out, encoding='utf-8') if '\t' in l}
todo = [i for i in range(n_prompts) if i not in done]
print(f'{len(done)} done, {len(todo)} to go', flush=True)
t0 = time.time(); n_tok = 0
with open(out, 'a', encoding='utf-8') as f, torch.no_grad():
    for b in range(0, len(todo), BATCH):
        idx = todo[b:b + BATCH]
        enc = tok([prompt(i) for i in idx], return_tensors='pt', padding=True).to('cuda')
        gen = model.generate(**enc, do_sample=True, temperature=0.9, top_p=0.95, max_new_tokens=MAX_NEW, pad_token_id=tok.pad_token_id)
        new = gen[:, enc.input_ids.shape[1]:]; n_tok += int((new != tok.pad_token_id).sum())
        for i, g in zip(idx, tok.batch_decode(new, skip_special_tokens=True)):
            kept = []
            for line in g.split('\n')[:-1]:  # the last line may be cut off
                m = LINE.match(line.strip())
                if not m or m.group(1) not in NAMES: break  # left the chat format (a new heading, prose): stop
                kept.append((m.group(1), m.group(2).strip().replace('\t', ' ')))
            # KEEP_NAMES=1: the speaker's name as a second column (filter_gender_ru.py checks first-person agreement)
            for name, msg in kept: f.write(f'{i}\t{name}\t{msg}\n' if os.environ.get('KEEP_NAMES') == '1' else f'{i}\t{msg}\n')
        f.flush()
        if (b // BATCH) % 20 == 0: print(f'{b + len(idx)} prompts, {n_tok / (time.time() - t0):.0f} tok/s', flush=True)
