"""Export a student checkpoint to LiteRT (private study, venv-litert): first step, a fixed window of
T tokens in, next-token logits out (no KV cache yet), float32 and int8 (dynamic-range weights).
Checks the .tflite against PyTorch on real text and prints sizes.
Usage: export_litert.py CKPT OUT_DIR [T]"""
import os, sys, numpy as np, torch
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from student import Config, Student
import litert_torch

ckpt, out = sys.argv[1], sys.argv[2]
T = int(sys.argv[3]) if len(sys.argv) > 3 else 32
os.makedirs(out, exist_ok=True)
s = torch.load(ckpt, map_location='cpu')
net = Student(Config(**s['config'])).eval(); net.load_state_dict(s['model'])

class Window(torch.nn.Module):
    """ids [1, T] -> logits of the last position [1, vocab]."""
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, ids): return self.m(ids)[:, -1, :]

w = Window(net).eval()
sample = (torch.randint(4, 15000, (1, T), dtype=torch.int64),)
with torch.no_grad(): ref = w(*sample).numpy()
name = os.path.basename(ckpt)[:-3]
edge = litert_torch.convert(w, sample)
f32 = os.path.join(out, f'{name}-T{T}-f32.tflite'); edge.export(f32)
run_in = (sample[0].to(torch.int32),)  # the .tflite takes int32 token ids
got = edge(*run_in)
print(f'f32: {os.path.getsize(f32) / 1e6:.1f} MB, max |diff| vs torch {np.abs(np.asarray(got) - ref).max():.2e}')
try:
    from litert_torch.generative.quantize import quant_recipes
    # int8 channel-wise weights, dynamic activations
    q = litert_torch.convert(w, sample, quant_config=quant_recipes.full_dynamic_recipe())
    i8 = os.path.join(out, f'{name}-T{T}-int8.tflite'); q.export(i8)
    gq = np.asarray(q(*run_in))
    top_ref, top_q = np.argsort(-ref[0])[:3], np.argsort(-gq[0])[:3]
    print(f'int8: {os.path.getsize(i8) / 1e6:.1f} MB, top-3 torch {top_ref.tolist()} vs int8 {top_q.tolist()}')
except Exception as e:
    print('int8 export failed:', type(e).__name__, str(e)[:300])
