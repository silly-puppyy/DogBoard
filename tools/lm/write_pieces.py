"""The app's piece table (PieceTokenizer, assets/dogboard/lm/*.pieces.tsv) from a SentencePiece model: a header line,
then one line per id: piece, score, type (c control, u unknown, b byte, d user-defined such as <cap>, n normal).
Usage: write_pieces.py SP_MODEL OUT.tsv "LABEL" """
import sys
import sentencepiece as spm
model, out, label = sys.argv[1], sys.argv[2], sys.argv[3]
sp = spm.SentencePieceProcessor(model_file=model)
with open(out, 'w', encoding='utf-8') as f:
    f.write(f'#dogboard-pieces 1\t{label}\n')
    for i in range(sp.vocab_size()):
        t = 'c' if sp.is_control(i) else 'u' if sp.is_unknown(i) else 'b' if sp.is_byte(i) else 'd' if sp.IdToPiece(i) == '<cap>' else 'n'
        f.write(f'{sp.IdToPiece(i)}\t{sp.GetScore(i):.6f}\t{t}\n')
