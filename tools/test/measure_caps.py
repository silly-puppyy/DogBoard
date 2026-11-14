#!/usr/bin/env python3
"""Measures key caps (white regions), gaps and glyph sizes in two keyboard screenshots (1080 px, 420 dpi).

    uv run --with numpy --with scipy --with pillow tools/test/measure_caps.py OURS.png OTHER.png
"""
import sys
import numpy as np
from PIL import Image
from scipy import ndimage
D=2.625
def caps(path, y0):
    g=np.asarray(Image.open(path).convert('L')).astype(int)
    m=g[y0:2300]>=246
    lab,n=ndimage.label(m)
    out=[]
    for sl in ndimage.find_objects(lab):
        ys,xs=sl; h=(ys.stop-ys.start); w=(xs.stop-xs.start)
        if h>60 and 40<w<1000: out.append((xs.start, ys.start+y0, w, h))
    return g, sorted(out, key=lambda r:(r[1]//50, r[0]))
def glyph(g, r, thr=140):
    x,y,w,h=r; sub=g[y+4:y+h-4, x+4:x+w-4]
    yy,xx=np.where(sub<thr)
    if len(xx)==0: return None
    return round((xx.max()-xx.min()+1)/D,1), round((yy.max()-yy.min()+1)/D,1)
for name,path,y0 in [('OURS',sys.argv[1],1500),('GB',sys.argv[2],1640)]:
    g,cs=caps(path,y0)
    print(name,'caps found',len(cs))
    rows={}
    for r in cs: rows.setdefault(round(r[1]/D/10),[]).append(r)
    for k in sorted(rows):
        rs=rows[k]
        print(' row top %.1f dp: n=%d, cap w %s h %.1f dp, gaps %s' % (rs[0][1]/D, len(rs), sorted(set(round(r[2]/D,1) for r in rs))[:4], rs[0][3]/D,
              sorted(set(round((rs[i+1][0]-rs[i][0]-rs[i][2])/D,1) for i in range(len(rs)-1)))[:3]))
        print('   glyphs:', [glyph(g,r) for r in rs[:3]], '... last', glyph(g,rs[-1]))
