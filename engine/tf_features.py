"""Board encoding shared by the Lichess data tool, the TensorFlow trainer and Java's ai.neuralNet.

A position is stored compactly as 65 bytes, always from the side to move's point of view:
  [0:64] piece code per square: 0 empty, 1-6 own pawn..king, 7-12 opponent pawn..king.
         Squares use the Java layout (index = row * 8 + col, row 0 = rank 8). When Black is to
         move the board is mirrored top-to-bottom (square ^ 56), so the mover always plays "up".
  [64]   castling bits: 1 own king side, 2 own queen side, 4 opponent king side, 8 opponent queen side.
Network input (772 values) = one-hot pieces at index square * 12 + code - 1, then the 4 castling bits.
The network outputs a logit: win probability for the side to move = sigmoid(logit), and
centipawns = logit * SCALE (the same scale the training targets use).
"""
from __future__ import annotations

import numpy as np

FEATURES = 772
COMPACT = 65
SCALE = 400.0        # centipawns per logit unit: target = sigmoid(cp / SCALE)
CP_LIMIT = 2000      # evaluations (and mates) are clipped to +-CP_LIMIT before training
PIECES = "pnbrqk"


def compact_from_fen(fen: str) -> np.ndarray:
    parts = fen.split()
    white = parts[1] == "w"
    out = np.zeros(COMPACT, np.uint8)
    for r, row in enumerate(parts[0].split("/")):
        c = 0
        for ch in row:
            if ch.isdigit():
                c += int(ch)
                continue
            kind = PIECES.index(ch.lower()) + 1
            own = ch.isupper() == white
            square = r * 8 + c if white else (7 - r) * 8 + c
            out[square] = kind if own else kind + 6
            c += 1
    rights = parts[2] if len(parts) > 2 else "-"
    K, Q, k, q = ("K" in rights), ("Q" in rights), ("k" in rights), ("q" in rights)
    mine, theirs = ((K, Q), (k, q)) if white else ((k, q), (K, Q))
    out[64] = mine[0] | mine[1] << 1 | theirs[0] << 2 | theirs[1] << 3
    return out


def expand(compact: np.ndarray) -> np.ndarray:
    """NumPy version of the network input (used by tests and as the reference for Java)."""
    compact = np.atleast_2d(np.asarray(compact, np.uint8))
    x = np.zeros((len(compact), FEATURES), np.float32)
    rows, squares = np.nonzero(compact[:, :64])
    codes = compact[rows, squares].astype(np.int64)
    x[rows, squares * 12 + codes - 1] = 1
    for bit in range(4):
        x[:, 768 + bit] = (compact[:, 64] >> bit) & 1
    return x


def tf_expand(compact):
    """TensorFlow version of expand(); runs inside the input pipeline on whole batches."""
    import tensorflow as tf
    codes = tf.cast(compact[:, :64], tf.int32)
    planes = tf.reshape(tf.one_hot(codes, 13)[:, :, 1:], [-1, 768])
    bits = tf.cast(compact[:, 64:65], tf.int32)
    rights = tf.bitwise.bitwise_and(tf.bitwise.right_shift(bits, tf.constant([0, 1, 2, 3])), 1)
    return tf.concat([planes, tf.cast(rights, tf.float32)], axis=1)


def target(cp):
    """Training target: win probability for the side to move."""
    cp = np.clip(np.asarray(cp, np.float32), -CP_LIMIT, CP_LIMIT)
    return 1 / (1 + np.exp(-cp / SCALE))


def forward(weights: list[np.ndarray], x: np.ndarray) -> np.ndarray:
    """Reference forward pass for an exported model: ReLU hidden layers, linear logit output."""
    h = x
    pairs = list(zip(weights[0::2], weights[1::2]))
    for w, b in pairs[:-1]:
        h = np.maximum(h @ w + b, 0)
    w, b = pairs[-1]
    return (h @ w + b)[:, 0]
