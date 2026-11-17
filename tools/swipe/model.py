"""DogBoard's swipe recogniser (tools/swipe/model.py): the shape iOS uses (notes/design/swipe-typing-plan.md),
trained from scratch on open data (FUTO swipe, How We Swipe; both MIT) and synthetic paths.

A swipe is a sequence of points resampled at even spacing, in key units of a QWERTY layout
(x: 0..10 across the top row, y: 0..3 down the rows), with the step to the next point:
features (x, y, dx, dy) per point. The network reads them and emits, per point, a distribution over
blank + a..z; CTC aligns that with the word. Lexicon and language model come after (decode.py).
"""
import torch
import torch.nn as nn

ALPHABET = "abcdefghijklmnopqrstuvwxyz"  # class 0 = CTC blank, 1..26 = a..z
FEATURES = 4


class SwipeNet(nn.Module):
    """conv 5 (4 -> 128) -> ReLU -> BN -> BiLSTM 128 -> BN -> BiLSTM 128 -> FC 256 -> 27 (iOS's layout of layers)."""

    def __init__(self, hidden: int = 128, letters: int = len(ALPHABET)):
        super().__init__()
        self.conv = nn.Conv1d(FEATURES, hidden, kernel_size=5, padding=2)
        self.bn1 = nn.BatchNorm1d(hidden)
        self.lstm1 = nn.LSTM(hidden, hidden, batch_first=True, bidirectional=True)
        self.bn2 = nn.BatchNorm1d(2 * hidden)
        self.lstm2 = nn.LSTM(2 * hidden, hidden, batch_first=True, bidirectional=True)
        self.fc = nn.Linear(2 * hidden, letters + 1)  # blank + the layout's letters

    def forward(self, x):  # x: (batch, time, FEATURES) -> log-probs (batch, time, 27)
        h = self.bn1(torch.relu(self.conv(x.transpose(1, 2)))).transpose(1, 2)
        h, _ = self.lstm1(h)
        h = self.bn2(h.transpose(1, 2)).transpose(1, 2)
        h, _ = self.lstm2(h)
        return self.fc(h).log_softmax(-1)


def encode(word: str, alphabet: str = ALPHABET) -> list[int]:
    return [alphabet.index(c) + 1 for c in word]


def greedy(logp, alphabet: str = ALPHABET) -> str:
    """Best path: argmax per step, repeats merged, blanks dropped."""
    out, prev = [], 0
    for k in logp.argmax(-1).tolist():
        if k != prev and k != 0:
            out.append(alphabet[k - 1])
        prev = k
    return "".join(out)
