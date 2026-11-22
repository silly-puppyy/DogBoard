"""Experiment tracking for DogBoard's models (tools/track.py): one wandb setup for every model family,
so language models and swipe models in every language sit side by side (user 2026-11-17).

Projects: dogboard-llm (language models), dogboard-swipe (swipe recognisers); later others the same
way. Every run carries the same fields, so runs group and filter by language, layout and data:

    family   llm | swipe
    lang     en, de, fr, ... (ISO 639-1)
    layout   qwerty, azerty, qwertz, ... (swipe; "" for language models)
    data     what it trained on: "futo-v3", "synth-h2", "fineweb+chat-v3", ...
    version  the model's own name: "v2-aug", "modern12x384-v3", ...

Run names: <family>-<lang>[-<layout>]-<version> (swipe-en-qwerty-v2-aug). Local run files go to the
data drive (keyboard-data/wandb), not the home disk. Login once with `wandb login` (the key stays in
~/.netrc). Without wandb or a login, or with DOGBOARD_WANDB=0, everything still runs and only
prints: tracking never stops training.
"""
import os

WANDB_DIR = "/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/wandb"
PROJECTS = {"llm": "dogboard-llm", "swipe": "dogboard-swipe"}


class _Off:
    """Stands in for a wandb run when tracking is off."""

    summary = {}

    def log(self, *a, **k):
        pass

    def finish(self):
        pass


def run_name(family, lang, version, layout=""):
    return "-".join(x for x in (family, lang, layout, version) if x)


def start(family, lang, version, layout="", data="", config=None, resume=True, tags=()):
    """A wandb run for one model (resumed if it already exists), or a no-op stand-in."""
    if os.environ.get("DOGBOARD_WANDB", "1") == "0":
        return _Off()
    try:
        import wandb
    except ImportError:
        print("track: wandb not installed, not tracking", flush=True)
        return _Off()
    name = run_name(family, lang, version, layout)
    os.makedirs(WANDB_DIR, exist_ok=True)
    try:
        return wandb.init(
            project=PROJECTS[family], name=name, id=name, resume="allow" if resume else None,
            dir=WANDB_DIR, group=f"{lang}-{layout}" if layout else lang,
            tags=[family, lang] + ([layout] if layout else []) + list(tags),
            # the run's own config wins over the shared fields (train.py passes its data too: a TypeError
            # here dropped tracking for the whole run, 2026-11-22)
            config={**dict(family=family, lang=lang, layout=layout, data=data, version=version), **(config or {})},
        )
    except Exception as e:  # no login, offline, ...: keep training
        print(f"track: wandb unavailable ({type(e).__name__}: {e}), not tracking", flush=True)
        return _Off()
