"""Checks exported .tflite windows against PyTorch on real text (private study): for sentences of a
benchmark set, the last-32-token window before each word; top-3 next tokens of torch vs the .tflite,
and the rate at which their top-1 agrees. Also times one interpreter call (desktop CPU, 1 thread).
Usage: check_litert.py CKPT model.tflite text.txt N_SENTENCES"""
import os, sys, time, numpy as np, torch, sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from student import Config, Student
from ai_edge_litert.interpreter import Interpreter
ckpt, tfl, text, n = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
s = torch.load(ckpt, map_location='cpu')
net = Student(Config(**s['config'])).eval(); net.load_state_dict(s['model'])
sp = spm.SentencePieceProcessor(model_file=os.path.join(s['tokdir'], 'sp16k.model'))
it = Interpreter(model_path=tfl, num_threads=1); it.allocate_tensors()
inp, outp = it.get_input_details()[0], it.get_output_details()[0]
T = inp['shape'][1]
agree = top3 = total = 0; times = []
for line in list(open(text, encoding='utf-8'))[:n]:
    ids = [1] + sp.encode(line.strip())
    for k in range(2, len(ids)):
        win = ids[max(0, k - T):k]; win = [0] * (T - len(win)) + win  # left-pad with <pad>
        x = np.array([win], dtype=np.int32)
        with torch.no_grad(): ref = net(torch.tensor(x, dtype=torch.long))[0, -1].numpy()
        it.set_tensor(inp['index'], x); t0 = time.perf_counter(); it.invoke(); times.append(time.perf_counter() - t0)
        got = it.get_tensor(outp['index'])[0]
        agree += int(ref.argmax() == got.argmax()); top3 += len(set(np.argsort(-ref)[:3]) & set(np.argsort(-got)[:3])) / 3; total += 1
print(f'{os.path.basename(tfl)}: {total} positions, top-1 agrees {100 * agree / total:.1f} %, top-3 overlap {100 * top3 / total:.1f} %, {1000 * np.median(times):.2f} ms per call (1 thread, desktop)')
