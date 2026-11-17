"""Coordinates for swipes on any layout (tools/swipe/units.py).

FUTO's records give points as 0..1 over the letter area, plus the canvas size, and each layout's keys
(centre and half-size, 0..1). Layouts differ in shape (QWERTY 3 rows of 10, ClearFlow and KASROZ 5 rows
of 8 bigger keys, Dvorak 4 rows) and canvases differ in aspect (FUTO's main collection h/w 0.42, its
5th 0.67 to 0.79), so "x * 10, y * 3" (the first models' units) squashes other layouts. Modes:

    grid  x * 10, y * 3 (QWERTY key widths across, rows down: the first models)
    phys  x * 10, y * 10 * h / w: one length unit both ways, a tenth of the keyboard's width
    key   x / key width, y / key height of that layout (each layout's own keys are 1 x 1)
"""


def scale(mode, layout, canvas_w=None, canvas_h=None):
    """(sx, sy): multiply 0..1 coordinates by these."""
    if mode == "grid":
        return 10.0, 3.0
    if mode == "phys":
        aspect = (canvas_h / canvas_w) if canvas_w and canvas_h else 0.42
        return 10.0, 10.0 * aspect
    if mode == "key":
        k = layout["keys"][0]
        return 1.0 / (2 * k["rx"]), 1.0 / (2 * k["ry"])
    raise ValueError(mode)


def keys(layout, sx, sy):
    return {k["letter"]: (k["cx"] * sx, k["cy"] * sy) for k in layout["keys"]}


def points(data, sx, sy):
    return [(p["x"] * sx, p["y"] * sy) for p in data]
