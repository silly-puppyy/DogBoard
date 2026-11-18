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
import dataclasses
import math
import random
from dataclasses import dataclass, field


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
    dwell: float = 0.0           # ms resting on the first key before moving (recordings include it)
    dt: float = 8.0              # ms per step (a touch screen's sampling)
    max_ms: float = 4000.0
    # per-person spread of any other parameter (user 2026-11-17: "sample all parameters on a
    # gaussian"): name -> sd of a Gaussian in log space (log-normal: stays positive, spread relative)
    spreads: dict = field(default_factory=dict)
    # mode "pursuit" (2026-11-17, after mean paths showed the spring swinging outside corners where
    # people cut inside): the finger chases a guide point running ahead along the route
    mode: str = "spring"
    guide_speed: float = 14.0    # key widths per second along the route
    corner_slow: float = 0.5     # the guide's speed drops by this share at a 180-degree turn
    corner_r: float = 0.6        # ... within this distance of the turn (key widths)
    drift: float = 0.25          # sideways drift of the guide (sd, key widths): bows on long strokes
    drift_tau: float = 250.0     # ms, how slowly it drifts
    follow: float = 0.3          # the guide runs on straight past the last key this far (lift while moving)
    # mode "stroke" (2026-11-18, user: "simulate a physical finger"; tools/swipe/phenomena.py measured what the
    # pursuit model lacks: corner slowing 0.62 vs 0.76 of median speed, longer strokes faster (+0.22 vs -0.10),
    # 231 vs 180 ms a key, mid-stroke hesitations on 6.2 % of strokes vs 0.2 %). Each key-to-key stroke is an
    # aimed movement: duration fitts_a + fitts_b * log2(1 + L / 0.5) ms, a minimum-jerk profile (bell-shaped
    # speed), the next stroke starting before this one ends by overlap x (1 - turn / pi) of its duration (keeps
    # speed through straight passes, slows at corners); each stroke bows sideways in one arc (a person's bias +
    # per stroke); a stroke may hesitate (stop hes_ms, log-normal) partway; the finger is the damped spring
    # chasing that guide, with the drifting motor noise above.
    fitts_a: float = 60.0
    fitts_b: float = 70.0
    overlap: float = 0.45
    bow_bias: float = 0.03       # sd of a person's own bow (share of stroke length; sign = side)
    bow_sd: float = 0.07         # sd of a stroke's bow around it
    hes_p: float = 0.06          # chance a stroke hesitates
    hes_ms: float = 150.0        # median hesitation
    hes_spread: float = 0.6      # log-normal sd of a hesitation


def person(rng: random.Random, p: FingerParams):
    """One simulated person's own constants."""
    own = {k: getattr(p, k) * math.exp(rng.gauss(0, sd)) for k, sd in p.spreads.items() if sd > 0}
    return dict(
        speed=math.exp(rng.gauss(0, p.omega_spread)) if p.mode in ("pursuit", "stroke") else 1.0,
        bow=rng.gauss(0, p.bow_bias),
        params=dataclasses.replace(p, **own) if own else p,
        omega=p.omega * math.exp(rng.gauss(0, p.omega_spread)),
        switch=p.switch * math.exp(rng.gauss(0, p.switch_spread)),
        care=math.exp(rng.gauss(0, p.care_spread)),
        bias=(rng.gauss(0, p.bias), rng.gauss(0, p.bias * p.aim_y_ratio)),
    )


def path(word: str, centres: dict, rng: random.Random, p: FingerParams = FingerParams(), who=None):
    """[(x, y, t_ms)] for `word` on keys at `centres` (physical key units)."""
    who = who or person(rng, p)
    p = who.get("params", p)
    if p.mode == "pursuit":
        return _pursuit(word, centres, rng, p, who)
    if p.mode == "stroke":
        return _stroke(word, centres, rng, p, who)
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
    # resting on the first key: the touch is down, the finger barely moves
    while t + p.dt <= p.dwell:
        t += p.dt
        out.append((x + rng.gauss(0, 0.01), y + rng.gauss(0, 0.01), t))
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


def _aims(word, centres, rng, p, who):
    keys = [centres[c] for c in word]
    keys = [k for i, k in enumerate(keys) if i == 0 or k != keys[i - 1]]
    n = len(keys)
    aims = []
    for i, (x, y) in enumerate(keys):
        s = who["care"] * (p.aim_first if i == 0 else p.aim_last if i == n - 1 else p.aim_mid)
        aims.append((x + who["bias"][0] + rng.gauss(0, s),
                     y + who["bias"][1] + rng.gauss(0, s * p.aim_y_ratio) + (p.start_dy if i == 0 else 0.0)))
    return aims


def _pursuit(word, centres, rng, p, who):
    """Pursuit: a guide point runs along the route through the aim points (slower at sharp turns,
    drifting sideways, running on past the end); the finger chases it on a damped spring, so it lags
    and cuts inside the corners. The touch lifts when the guide finishes its run-on."""
    aims = _aims(word, centres, rng, p, who)
    if len(aims) == 1:
        x, y = aims[0]
        return [(x, y, 0.0), (x + rng.gauss(0, 0.03), y + rng.gauss(0, 0.03), p.dt * 3)]
    # the route: aim points, plus the follow-through past the last one
    (ax, ay), (bx, by) = aims[-2], aims[-1]
    L = math.hypot(bx - ax, by - ay) or 1.0
    f = p.follow * math.exp(rng.gauss(0, p.lift_spread))
    route = aims + [(bx + (bx - ax) / L * f, by + (by - ay) / L * f)]
    seglen = [math.hypot(q[0] - r[0], q[1] - r[1]) for r, q in zip(route, route[1:])]
    cum = [0.0]
    for d in seglen:
        cum.append(cum[-1] + d)
    total = cum[-1]
    # turn at each inner vertex (radians, 0..pi)
    turns = [0.0]
    for i in range(1, len(route) - 1):
        v1 = (route[i][0] - route[i - 1][0], route[i][1] - route[i - 1][1])
        v2 = (route[i + 1][0] - route[i][0], route[i + 1][1] - route[i][1])
        n1, n2 = math.hypot(*v1), math.hypot(*v2)
        turns.append(0.0 if n1 == 0 or n2 == 0 else math.acos(max(-1.0, min(1.0, (v1[0] * v2[0] + v1[1] * v2[1]) / (n1 * n2)))))
    turns.append(0.0)

    def at(s):
        j = 0
        while j < len(seglen) - 1 and cum[j + 1] < s:
            j += 1
        u = 0.0 if seglen[j] == 0 else (s - cum[j]) / seglen[j]
        r, q = route[j], route[j + 1]
        dx, dy = q[0] - r[0], q[1] - r[1]
        n = math.hypot(dx, dy) or 1.0
        # how close to a turning vertex (for slowing down)
        near = min(abs(s - cum[k]) for k in range(len(cum)))
        k = min(range(len(cum)), key=lambda k: abs(s - cum[k]))
        return r[0] + u * dx, r[1] + u * dy, -dy / n, dx / n, near, turns[k]

    dt = p.dt / 1000.0
    v = p.guide_speed * who.get("speed", 1.0)
    w, z = who["omega"], p.zeta
    keep = math.exp(-p.dt / max(p.drift_tau, 1e-3))
    kick = p.drift * math.sqrt(1 - keep * keep)
    b = rng.gauss(0, p.drift) * 0.3  # starts near the route
    fkeep = math.exp(-p.dt / max(p.noise_tau, 1e-3))
    fkick = p.noise * math.sqrt(1 - fkeep * fkeep)
    fx = fy = 0.0
    x, y = aims[0]
    vx = vy = 0.0
    t, s = 0.0, 0.0
    out = [(x, y, 0.0)]
    while t + p.dt <= p.dwell:
        t += p.dt
        out.append((x + rng.gauss(0, 0.01), y + rng.gauss(0, 0.01), t))
    sub = max(1, math.ceil(w * dt / 0.3))
    h = dt / sub
    while s < total and t < p.max_ms:
        gx, gy, nx, ny, near, turn = at(s)
        slow = 1.0 - p.corner_slow * (turn / math.pi) * math.exp(-near / max(p.corner_r, 1e-3))
        s += v * max(slow, 0.1) * dt
        b = b * keep + rng.gauss(0, kick)
        gx, gy = gx + b * nx, gy + b * ny
        fx = fx * fkeep + rng.gauss(0, fkick)
        fy = fy * fkeep + rng.gauss(0, fkick)
        for _ in range(sub):
            vx += (w * w * (gx - x) - 2 * z * w * vx + fx) * h
            vy += (w * w * (gy - y) - 2 * z * w * vy + fy) * h
            x += vx * h
            y += vy * h
        t += p.dt
        out.append((x, y, t))
    return out


def _mj(u):
    """Minimum-jerk position profile, 0..1 for u in 0..1 (bell-shaped speed)."""
    u = min(1.0, max(0.0, u))
    return u * u * u * (10 - 15 * u + 6 * u * u)


def _stroke(word, centres, rng, p, who):
    """The physical finger (mode "stroke", see FingerParams): a guide made of overlapping aimed strokes, each a
    minimum-jerk move with Fitts timing, a sideways bow and maybe a hesitation; a damped spring finger chases it."""
    aims = _aims(word, centres, rng, p, who)
    if len(aims) == 1:
        x, y = aims[0]
        return [(x, y, 0.0), (x + rng.gauss(0, 0.03), y + rng.gauss(0, 0.03), p.dt * 3)]
    (ax, ay), (bx, by) = aims[-2], aims[-1]
    L = math.hypot(bx - ax, by - ay) or 1.0
    f = p.follow * math.exp(rng.gauss(0, p.lift_spread))
    route = aims + [(bx + (bx - ax) / L * f, by + (by - ay) / L * f)]
    speed = who.get("speed", 1.0)
    strokes = []  # (start ms, duration ms, from, to, normal, bow, hesitation at u, hesitation ms)
    start = p.dwell
    for i in range(len(route) - 1):
        (x0, y0), (x1, y1) = route[i], route[i + 1]
        d = math.hypot(x1 - x0, y1 - y0)
        last = i == len(route) - 2  # the run-on past the last key
        dur = (p.fitts_a + p.fitts_b * math.log2(1 + d / 0.5)) / speed * (0.5 if last else 1.0)
        nx, ny = ((y0 - y1) / d, (x1 - x0) / d) if d > 0 else (0.0, 0.0)
        bow = 0.0 if last else (who.get("bow", 0.0) + rng.gauss(0, p.bow_sd)) * d
        hu, hd = 0.0, 0.0
        if not last and rng.random() < p.hes_p:
            hu, hd = rng.uniform(0.2, 0.8), p.hes_ms * math.exp(rng.gauss(0, p.hes_spread))
        strokes.append((start, dur, (x0, y0), (x1, y1), (nx, ny), bow, hu, hd))
        if i + 2 < len(route):
            (x2, y2) = route[i + 2]
            v1, v2 = (x1 - x0, y1 - y0), (x2 - x1, y2 - y1)
            n1, n2 = math.hypot(*v1), math.hypot(*v2)
            turn = 0.0 if n1 == 0 or n2 == 0 else math.acos(max(-1.0, min(1.0, (v1[0] * v2[0] + v1[1] * v2[1]) / (n1 * n2))))
            start += dur + hd - p.overlap * (1 - turn / math.pi) * dur
    end = strokes[-1][0] + strokes[-1][1] + strokes[-1][7]

    def guide(t):
        gx, gy = route[0]
        for s0, dur, (x0, y0), (x1, y1), (nx, ny), bow, hu, hd in strokes:
            if t <= s0:
                break
            tt = t - s0
            if hd > 0:  # the hesitation freezes this stroke's progress at hu for hd ms
                th = hu * dur
                tt = tt if tt < th else (th if tt < th + hd else tt - hd)
            u = _mj(tt / dur)
            gx += (x1 - x0) * u + nx * bow * math.sin(math.pi * u)
            gy += (y1 - y0) * u + ny * bow * math.sin(math.pi * u)
        return gx, gy

    dt = p.dt / 1000.0
    w, z = who["omega"], p.zeta
    fkeep = math.exp(-p.dt / max(p.noise_tau, 1e-3))
    fkick = p.noise * math.sqrt(1 - fkeep * fkeep)
    fx = fy = 0.0
    x, y = route[0]
    vx = vy = 0.0
    out = [(x, y, 0.0)]
    sub = max(1, math.ceil(w * dt / 0.3))
    h = dt / sub
    t = 0.0
    while t < end and t < p.max_ms:
        t += p.dt
        gx, gy = guide(t)
        fx = fx * fkeep + rng.gauss(0, fkick)
        fy = fy * fkeep + rng.gauss(0, fkick)
        for _ in range(sub):
            vx += (w * w * (gx - x) - 2 * z * w * vx + fx) * h
            vy += (w * w * (gy - y) - 2 * z * w * vy + fy) * h
            x += vx * h
            y += vy * h
        out.append((x, y, t))
    return out

