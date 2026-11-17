"""A geometric swipe decoder (tools/swipe/geometric.py): the classic baseline (SHARK2, Kristensson and
Zhai 2004; FlorisBoard's and many keyboards' glide typing work this way): each word's ideal path through
its key centres, compared with the swipe after both are resampled to N points; shape distance (both
centred and scaled) plus location distance, with the word's frequency as prior. No training, so it
shows how hard a test set is on its own.
"""
import numpy as np

N = 32


def resample_n(pts, n=N):
    pts = np.asarray(pts, dtype=np.float64)
    if len(pts) == 1:
        return np.repeat(pts, n, axis=0)
    seg = np.hypot(*np.diff(pts, axis=0).T)
    cum = np.concatenate([[0.0], np.cumsum(seg)])
    if cum[-1] == 0:
        return np.repeat(pts[:1], n, axis=0)
    t = np.linspace(0, cum[-1], n)
    return np.stack([np.interp(t, cum, pts[:, 0]), np.interp(t, cum, pts[:, 1])], axis=1)


def normalise(p):
    c = p - p.mean(axis=-2, keepdims=True)
    span = np.maximum(np.ptp(c[..., 0], axis=-1), np.ptp(c[..., 1], axis=-1))
    return c / np.maximum(span, 1e-6)[..., None, None]


class Geometric:
    def __init__(self, words_with_logp, centres, sigma_shape=0.06, sigma_loc=0.35, end_gate=1.6):
        self.words = [w for w, _ in words_with_logp]
        self.prior = np.array([lp for _, lp in words_with_logp])
        self.tpl = np.stack([resample_n([centres[c] for c in w]) for w in self.words])
        self.shape = normalise(self.tpl)
        self.s_shape, self.s_loc, self.gate = sigma_shape, sigma_loc, end_gate

    def decode(self, xy, top=3):
        p = resample_n(xy)
        # only words starting and ending near the swipe's ends (as SHARK2's pruning)
        keep = (np.hypot(*(self.tpl[:, 0] - p[0]).T) < self.gate) & (np.hypot(*(self.tpl[:, -1] - p[-1]).T) < self.gate)
        idx = np.nonzero(keep)[0]
        if len(idx) == 0:
            return []
        d_shape = np.hypot(*(self.shape[idx] - normalise(p)).transpose(2, 0, 1)).mean(axis=1)
        d_loc = np.hypot(*(self.tpl[idx] - p).transpose(2, 0, 1)).mean(axis=1)
        score = -(d_shape / self.s_shape) ** 2 - (d_loc / self.s_loc) ** 2 + self.prior[idx]
        order = np.argsort(-score)[:top]
        return [(self.words[idx[i]], float(score[i])) for i in order]


def xy_of(features):
    """Back from bench features (x, y, dx, dy) to the path's points."""
    return [(float(r[0]), float(r[1])) for r in features]
