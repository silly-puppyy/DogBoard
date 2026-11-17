"""A physical model of a swiping finger (tools/swipe/physics.py): human-like swipes for any layout, from a
handful of parameters (user 2026-11-17: physics simulation, as little from the real data as possible,
no stitching of real paths).

What hundreds of real swipes per word show (analyze_words.py, FUTO, physical units): corners are
rounded, more when fast; the finger is still moving when it lifts, so ends land past the last key;
starts are tight; offsets at different keys of one swipe are independent (correlation 0.01), with a
person-level share (23% of the variance of a swipe's mean offset); slow swipes wander more.

The finger is a point mass pulled by a spring towards its current target with damping, plus a small
random force (motor noise):  a = w^2 (target - p) - 2 z w v + noise.  Targets are the word's keys,
each with its own aiming error (and the person's bias). The finger moves on to the next target once
it is within `switch` of the current one (key widths): early switching plus momentum round the
corners. After the last target is reached it lifts `lift` ms later, still moving: the run-on. Each
simulated person draws stiffness (speed), switch distance and care (aim spread) around the medians.

Units: physical (units.py "phys": a tenth of the keyboard's width, both directions). Nothing here is
specific to a layout: give key centres in those units.
"""
import math
import random
from dataclasses import dataclass


@dataclass
class FingerParams:
    omega: float = 28.0          # spring stiffness, rad/s (speed)
    omega_spread: float = 0.30   # log-normal spread between people
    zeta: float = 0.75           # damping ratio (< 1: some overshoot)
    switch: float = 0.45         # move on to the next key within this distance (key widths)
    switch_spread: float = 0.35
    aim_first: float = 0.16      # aiming error sd at the first key (key widths)
    aim_mid: float = 0.18
    aim_last: float = 0.18
    aim_y_ratio: float = 0.8     # vertical aim error relative to horizontal
    care_spread: float = 0.35    # log-normal spread of the aim error between people
    bias: float = 0.10           # a person's own consistent offset (sd)
    start_dy: float = 0.06       # touch-down a little below the key centre
    noise: float = 6.0           # random force: its sd (key widths / s^2)
    noise_tau: float = 120.0     # how slowly the force drifts (ms): smooth bows, not jitter
    reach: float = 0.25          # the last target counts as reached within this distance
    lift: float = 40.0           # ms from reaching the last key to lifting
    lift_spread: float = 0.6
    dt: float = 8.0              # ms per step (a touch screen's sampling)
    max_ms: float = 4000.0


def person(rng: random.Random, p: FingerParams):
    """One simulated person's own constants."""
    return dict(
        omega=p.omega * math.exp(rng.gauss(0, p.omega_spread)),
        switch=p.switch * math.exp(rng.gauss(0, p.switch_spread)),
        care=math.exp(rng.gauss(0, p.care_spread)),
        bias=(rng.gauss(0, p.bias), rng.gauss(0, p.bias * p.aim_y_ratio)),
    )


def path(word: str, centres: dict, rng: random.Random, p: FingerParams = FingerParams(), who=None):
    """[(x, y, t_ms)] for `word` on keys at `centres` (physical key units)."""
    who = who or person(rng, p)
    keys = [centres[c] for c in word]
    keys = [k for i, k in enumerate(keys) if i == 0 or k != keys[i - 1]]  # a double letter: no move
    n = len(keys)
    targets = []
    for i, (x, y) in enumerate(keys):
        s = who["care"] * (p.aim_first if i == 0 else p.aim_last if i == n - 1 else p.aim_mid)
        targets.append((x + who["bias"][0] + rng.gauss(0, s),
                        y + who["bias"][1] + rng.gauss(0, s * p.aim_y_ratio) + (p.start_dy if i == 0 else 0.0)))
    w, z, dt = who["omega"], p.zeta, p.dt / 1000.0
    x, y = targets[0]
    vx = vy = 0.0
    out = [(x, y, 0.0)]
    if n == 1:
        return out + [(x + rng.gauss(0, 0.03), y + rng.gauss(0, 0.03), p.dt * 3)]
    i, t, lift_at = 1, 0.0, None
    # the random force drifts (Ornstein-Uhlenbeck): sd p.noise, correlation time p.noise_tau
    keep = math.exp(-p.dt / max(p.noise_tau, 1e-3))
    kick = p.noise * math.sqrt(1 - keep * keep)
    fx, fy = rng.gauss(0, p.noise), rng.gauss(0, p.noise)
    # sub-steps keep the integration stable for stiff springs (w * h well below 2)
    sub = max(1, math.ceil(w * dt / 0.3))
    h = dt / sub
    while t < p.max_ms:
        tx, ty = targets[i]
        fx = fx * keep + rng.gauss(0, kick)
        fy = fy * keep + rng.gauss(0, kick)
        for _ in range(sub):
            vx += (w * w * (tx - x) - 2 * z * w * vx + fx) * h
            vy += (w * w * (ty - y) - 2 * z * w * vy + fy) * h
            x += vx * h
            y += vy * h
        t += p.dt
        out.append((x, y, t))
        d = math.hypot(tx - x, ty - y)
        if i < n - 1:
            if d < who["switch"]:
                i += 1
        elif lift_at is None and d < p.reach:
            lift_at = t + p.lift * math.exp(rng.gauss(0, p.lift_spread))
        if lift_at is not None and t >= lift_at:
            break
    return out
