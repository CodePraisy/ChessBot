"""Writes the small, untrained model fixtures Java's tests use to check it evaluates exactly like Python.

  python engine/make_test_fixtures.py      # -> test/fixtures/neural/ (no TensorFlow needed)
"""
import json
from pathlib import Path

import chess
import numpy as np

from neural_model import ValueNetwork, encode
from tf_features import FEATURES, SCALE, compact_from_fen, expand, forward

OUT = Path(__file__).resolve().parent.parent / "test" / "fixtures" / "neural"
FENS = [
    chess.STARTING_FEN,
    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R b Kq - 3 12",
    "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
    "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 b - - 17 40",
    "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1",
]


def main():
    rng = np.random.default_rng(2026)
    layers = [FEATURES, 16, 8, 1]
    weights = []
    for a, b in zip(layers, layers[1:]):
        weights += [rng.normal(0, 0.3, (a, b)).astype(np.float32), rng.normal(0, 0.1, b).astype(np.float32)]
    mlp = OUT / "mlp"
    mlp.mkdir(parents=True, exist_ok=True)
    np.savez(mlp / "weights.npz", **{f"{k}{i}": w for i, pair in enumerate(zip(weights[0::2], weights[1::2]))
                                     for k, w in zip("wb", pair)})
    (mlp / "model.json").write_text(json.dumps({"format": "chessbot-mlp", "version": 2, "features": "mover772",
        "scale": SCALE, "activation": "relu", "layers": layers, "name": "Fixture net", "size": "fixture",
        "trained_positions": 0, "parameters": int(sum(w.size for w in weights))}, indent=2))
    gym = ValueNetwork(rng)
    gym.save(OUT / "gym.npz", {"id": "fixture", "trained_positions": 0})
    expected = []
    for fen in FENS:
        board = chess.Board(fen)
        logit = float(forward(weights, expand(compact_from_fen(fen)))[0])
        value = float(gym.predict(encode(board)))
        gym_cp = int(np.arctanh(np.clip(value, -0.999, 0.999)) * 600) * (1 if board.turn else -1)
        expected.append({"fen": fen, "mlp": logit * SCALE, "gym": gym_cp,
                         "features": np.flatnonzero(expand(compact_from_fen(fen))[0]).tolist()})
    (OUT / "expected.json").write_text(json.dumps(expected, indent=1))
    print(f"Wrote fixtures to {OUT}")


if __name__ == "__main__":
    main()
