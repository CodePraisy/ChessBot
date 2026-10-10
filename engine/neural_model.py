"""CPU value network, board encoding and portable checkpoints for the training gym."""
from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile

import chess
import numpy as np

FEATURES = 782  # 12 piece planes, turn, castling, en-passant file, halfmove clock
HIDDEN = 64
FORMAT_VERSION = 1
MATERIAL = np.zeros(FEATURES, dtype=np.float32)
for color in range(2):
    for piece, value in enumerate((100, 320, 330, 500, 900, 0)):
        start = (color * 6 + piece) * 64
        MATERIAL[start:start + 64] = value * (1 if color == 0 else -1) / 1200


def encode(board: chess.Board) -> np.ndarray:
    x = np.zeros(FEATURES, dtype=np.float32)
    for color in (chess.WHITE, chess.BLACK):
        for piece_type in chess.PIECE_TYPES:
            start = ((0 if color else 6) + piece_type - 1) * 64
            for sq in chess.scan_forward(board.pieces_mask(piece_type, color)):
                x[start + sq] = 1
    x[768] = 1 if board.turn else -1
    x[769:773] = (board.has_kingside_castling_rights(chess.WHITE),
                  board.has_queenside_castling_rights(chess.WHITE),
                  board.has_kingside_castling_rights(chess.BLACK),
                  board.has_queenside_castling_rights(chess.BLACK))
    if board.ep_square is not None:
        x[773 + chess.square_file(board.ep_square)] = 1
    x[781] = min(board.halfmove_clock, 100) / 100
    return x


class ValueNetwork:
    """Learns White's game outcome; material provides a small starting prior.

    A tanh hidden layer learns corrections to material from actual game results.
    No hand-written personalities or TensorFlow/GPU runtime are required.
    """
    def __init__(self, rng: np.random.Generator):
        self.w1 = rng.normal(0, 1 / np.sqrt(FEATURES), (FEATURES, HIDDEN)).astype(np.float32)
        self.b1 = np.zeros(HIDDEN, dtype=np.float32)
        self.w2 = rng.normal(0, 0.05, HIDDEN).astype(np.float32)
        self.b2 = np.zeros(1, dtype=np.float32)

    def predict(self, x: np.ndarray) -> np.ndarray:
        hidden = np.tanh(x @ self.w1 + self.b1)
        return np.tanh(hidden @ self.w2 + self.b2[0] + x @ MATERIAL)

    def fit(self, x: np.ndarray, y: np.ndarray, rng: np.random.Generator,
            epochs: int = 3, learning_rate: float = 0.01, batch_size: int = 128) -> float:
        if not len(x) or x.shape != (len(y), FEATURES):
            raise ValueError("Training needs matching encoded positions and outcomes")
        for _ in range(epochs):
            order = rng.permutation(len(x))
            for start in range(0, len(x), batch_size):
                indices = order[start:start + batch_size]
                batch, target = x[indices], y[indices]
                hidden = np.tanh(batch @ self.w1 + self.b1)
                predicted = np.tanh(hidden @ self.w2 + self.b2[0] + batch @ MATERIAL)
                grad = 2 * (predicted - target) * (1 - predicted ** 2) / len(indices)
                hidden_grad = grad[:, None] * self.w2 * (1 - hidden ** 2)
                self.w2 -= learning_rate * (hidden.T @ grad)
                self.b2 -= learning_rate * np.sum(grad)
                self.w1 -= learning_rate * (batch.T @ hidden_grad)
                self.b1 -= learning_rate * np.sum(hidden_grad, axis=0)
        return float(np.mean((self.predict(x) - y) ** 2))

    def offspring(self, rng: np.random.Generator, mutation: float) -> ValueNetwork:
        child = object.__new__(ValueNetwork)
        for name in ("w1", "b1", "w2", "b2"):
            weights = getattr(self, name)
            setattr(child, name, weights.copy() + rng.normal(0, mutation, weights.shape).astype(np.float32))
        return child

    def save(self, path: Path, metadata: dict) -> None:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        info = dict(metadata, format_version=FORMAT_VERSION)
        with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".tmp", delete=False) as handle:
            temporary = Path(handle.name)
            np.savez_compressed(handle, w1=self.w1, b1=self.b1, w2=self.w2, b2=self.b2,
                                metadata=np.array(json.dumps(info)))
        os.replace(temporary, path)

    @classmethod
    def load(cls, path: Path) -> tuple[ValueNetwork, dict]:
        network = object.__new__(cls)
        shapes = {"w1": (FEATURES, HIDDEN), "b1": (HIDDEN,), "w2": (HIDDEN,), "b2": (1,)}
        with np.load(path, allow_pickle=False) as data:
            metadata = json.loads(str(data["metadata"].item()))
            if metadata.get("format_version") != FORMAT_VERSION:
                raise ValueError("Unsupported model checkpoint version")
            for name, shape in shapes.items():
                weights = np.array(data[name], dtype=np.float32, copy=True)
                if weights.shape != shape or not np.isfinite(weights).all():
                    raise ValueError("Invalid checkpoint weights: " + name)
                setattr(network, name, weights)
        return network, metadata
