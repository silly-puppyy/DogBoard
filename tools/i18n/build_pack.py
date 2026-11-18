#!/usr/bin/env python3
"""Builds a language pack from its spec (tools/i18n/build_pack.py).

    tools/i18n/build_pack.py en-US [--data DIR] [--work DIR] [--stage NAME ...] [--force] [--dry-run]

The spec (tools/i18n/packs/<tag>.json) lists the pack's components and, under "build", the stages that
make them: each stage runs one command (the existing data scripts, KenLM, the swipe and LM trainers) with
its inputs and outputs, so the whole pack is one command and every step is visible. A stage runs when an
output is missing or older than an input (or with --force / --stage); --dry-run prints the plan. After
the stages, every component is checked against its size budget ("budget_bytes"), "validate" commands run
(the evaluations), and the manifest is written (write_manifest.py). notes/design/i18n-pipeline.md.

Variables in commands and paths: ${DATA} (downloaded sources, --data), ${WORK} (intermediate files,
--work/<tag>), ${ASSETS} (app/src/main/assets), ${PACK} (the pack's asset directory), ${REPO}.
"""
import argparse
import json
import os
import shlex
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def expand(s, env):
    for k, v in env.items():
        s = s.replace("${" + k + "}", v)
    return s


def stale(inputs, outputs):
    """Missing output, or an input newer than the oldest output."""
    if not outputs or any(not os.path.exists(o) for o in outputs):
        return True
    oldest = min(os.path.getmtime(o) for o in outputs)
    return any(os.path.exists(i) and os.path.getmtime(i) > oldest for i in inputs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tag")
    ap.add_argument("--data", default=os.environ.get("DOGBOARD_DATA", "/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data"))
    ap.add_argument("--work", default="")
    ap.add_argument("--stage", action="append", default=[], help="run only these stages (always)")
    ap.add_argument("--force", action="store_true", help="run every stage")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    spec_path = os.path.join(REPO, "tools", "i18n", "packs", f"{a.tag}.json")
    spec = json.load(open(spec_path, encoding="utf-8"))
    env = {"REPO": REPO, "DATA": a.data, "ASSETS": os.path.join(REPO, "app", "src", "main", "assets"),
           "WORK": a.work or os.path.join(a.data, "packs-work", a.tag)}
    env["PACK"] = os.path.join(env["ASSETS"], "dogboard", "packs", a.tag)
    os.makedirs(env["WORK"], exist_ok=True)

    # 1. stages
    for st in spec.get("build", []):
        name = st["stage"]
        ins = [expand(p, env) for p in st.get("inputs", [])]
        outs = [expand(p, env) for p in st.get("outputs", [])]
        # "manual" stages (hours of training: KenLM on FineWeb, the neural model) run only when named
        run = name in a.stage or (not st.get("manual") and (a.force or (not a.stage and stale(ins, outs))))
        cmd = [expand(c, env) for c in st["run"]]
        tag = "run " if run else ("manual" if st.get("manual") else "skip")
        print(f"[{tag}] {name}: {shlex.join(cmd)}")
        if not run or a.dry_run:
            continue
        missing = [i for i in ins if not os.path.exists(i)]
        if missing:
            sys.exit(f"  {name}: missing inputs {missing}")
        t0 = time.time()
        r = subprocess.run(cmd, cwd=REPO)
        if r.returncode != 0:
            sys.exit(f"  {name}: failed ({r.returncode})")
        print(f"  {name}: {time.time() - t0:.0f} s")

    # 2. budgets
    over = []
    for comp, f in spec["files"].items():
        p = os.path.join(env["ASSETS"], f["path"])
        b = f.get("budget_bytes")
        if b and os.path.exists(p) and os.path.getsize(p) > b:
            over.append(f"{comp}: {os.path.getsize(p)} bytes > budget {b}")
    for o in over:
        print("  over budget:", o)
    if over and not a.dry_run:
        sys.exit("size budgets exceeded")

    # 3. validation (the evaluations), then the manifest
    for v in spec.get("validate", []):
        cmd = [expand(c, env) for c in v["run"]]
        print(f"[validate] {v['name']}: {shlex.join(cmd)}")
        if not a.dry_run and subprocess.run(cmd, cwd=REPO, env={**os.environ, **{k: expand(x, env) for k, x in v.get('env', {}).items()}}).returncode != 0:
            sys.exit(f"  validation {v['name']} failed")
    if not a.dry_run:
        subprocess.run([sys.executable, os.path.join(REPO, "tools", "i18n", "write_manifest.py"), spec_path,
                        "--assets", env["ASSETS"]], check=True)


if __name__ == "__main__":
    main()
