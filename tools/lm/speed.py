"""Per-token decode speed of student shapes: int8 dynamic quantisation (weights int8), one CPU
thread, KV cache, 24 tokens of context then 8 decode steps (a word or two). Random weights: speed
depends on the shape, not the training. Prints params, size (int8 MB) and ms per token."""
import sys, time, torch
sys.path.insert(0, '/home/syn/Documents/Code/AI/keyboard/dogboard/tools/lm')
from student import Config, Student
torch.set_num_threads(1)
SHAPES = {
    'apple-6x512-gpt2': Config(vocab=15000, layers=6, width=512, heads=8, kv_heads=8, ffn=2048, family='gpt2'),
    'modern-6x512-gqa': Config(layers=6, width=512, heads=8, kv_heads=2, ffn=1536),
    'modern-12x384-gqa': Config(layers=12, width=384, heads=6, kv_heads=2, ffn=1024),
    'modern-16x320-gqa': Config(layers=16, width=320, heads=5, kv_heads=1, ffn=864),
    'modern-24x288-gqa': Config(layers=24, width=288, heads=6, kv_heads=2, ffn=768),
}
for name, c in SHAPES.items():
    m = Student(c).eval()
    n = sum(p.numel() for p in m.parameters())
    q = torch.ao.quantization.quantize_dynamic(m, {torch.nn.Linear}, dtype=torch.qint8)
    with torch.no_grad():
        times = []
        for rep in range(5):
            caches = [dict() for _ in range(c.layers)]
            ids = torch.randint(0, c.vocab, (1, 24))
            q(ids, 0, caches)
            t0 = time.perf_counter()
            for s in range(8):
                q(torch.randint(0, c.vocab, (1, 1)), 24 + s, caches)
            times.append((time.perf_counter() - t0) / 8 * 1000)
    print(f'{name:22s} {n/1e6:5.1f} M params  ~{n/1e6:4.0f} MB int8  {sorted(times)[2]:6.2f} ms/token (1 thread)')
