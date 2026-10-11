"""Isolated checks for the Lichess data tool and TensorFlow models. Uses temporary folders only."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

import chess
import numpy as np

from lichess_data import main as data_main, parse_eval
from tf_features import FEATURES, compact_from_fen, expand, forward

HAS_TF = importlib.util.find_spec("tensorflow") is not None


def eval_line(fen, cp=None, mate=None, line="e2e4", depth=30):
    pv = {"line": line, **({"cp": cp} if cp is not None else {"mate": mate})}
    return json.dumps({"fen": fen, "evals": [{"pvs": [pv], "knodes": 1000, "depth": depth}]})


class FeatureTests(unittest.TestCase):
    def test_colour_mirror_gives_identical_input(self):
        board = chess.Board("r3k2r/pp1n1ppp/2p1bn2/q7/3P4/2N1BN2/PPPQ1PPP/R3K2R w KQq - 2 10")
        for _ in range(2):
            mirrored = board.mirror()
            self.assertTrue(np.array_equal(compact_from_fen(board.fen()), compact_from_fen(mirrored.fen())))
            board.push(next(iter(board.legal_moves)))

    def test_feature_layout(self):
        x = expand(compact_from_fen(chess.STARTING_FEN))[0]
        self.assertEqual(x.shape, (FEATURES,))
        self.assertEqual(x[:768].sum(), 32)
        self.assertEqual(x[768:].tolist(), [1, 1, 1, 1])
        # own king (code 6) on e1: Java square row 7, col 4 = 60
        self.assertEqual(x[60 * 12 + 5], 1)
        black = expand(compact_from_fen(chess.Board().fen().replace(" w ", " b ")))[0]
        # Black to move: the board is mirrored so Black's king (now "own") is also on square 60
        self.assertEqual(black[60 * 12 + 5], 1)


class DataTests(unittest.TestCase):
    def test_parse_and_filters(self):
        fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"
        compact, cp, _ = parse_eval(eval_line(fen, cp=40, line="b8c6"), 12, True)
        self.assertEqual(cp, -40)  # Lichess scores are White's view; stored for the side to move
        self.assertIsNone(parse_eval(eval_line(fen, cp=40, line="d7d5", depth=5), 12, True))
        capture = "rnbqkbnr/ppp2ppp/8/3pp3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 3"
        self.assertIsNone(parse_eval(eval_line(capture, cp=10, line="e4d5"), 12, True))
        self.assertIsNotNone(parse_eval(eval_line(capture, cp=10, line="e4d5"), 12, False))
        self.assertEqual(parse_eval(eval_line(fen, mate=-3, line="b8c6"), 12, True)[1], 2000)

    def test_eval_file_to_shards(self):
        import zstandard
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            lines = []
            board = chess.Board()
            for i in range(120):
                move = list(board.legal_moves)[i % len(list(board.legal_moves))]
                lines.append(eval_line(board.fen(), cp=i - 60, line=move.uci()))
                board.push(move)
                if board.is_game_over():
                    board = chess.Board()
            source = folder / "evals.jsonl.zst"
            source.write_bytes(zstandard.ZstdCompressor().compress(("\n".join(lines) + "\n").encode()))
            data_main(["evals", "--source", str(source), "--out", str(folder / "out"), "--include-tactical",
                       "--positions", "100", "--shard-size", "40", "--val-every", "10"])
            info = json.loads((folder / "out" / "data.json").read_text())
            self.assertEqual(info["kept"], 100)
            self.assertEqual(info["train_positions"] + info["val_positions"], 100)
            self.assertGreater(info["val_positions"], 0)
            with np.load(sorted((folder / "out").glob("train-*.npz"))[0]) as shard:
                self.assertEqual(shard["x"].shape[1], 65)
                self.assertEqual(shard["x"].dtype, np.uint8)


@unittest.skipUnless(HAS_TF, "TensorFlow is not installed")
class ModelTests(unittest.TestCase):
    def test_every_size_trains_exports_and_matches_numpy(self):
        from tf_models import SIZES, build, load_exported
        from train_tf import main as train_main
        positions = np.stack([compact_from_fen(f) for f in (
            chess.STARTING_FEN, "8/8/4k3/8/2Q5/8/4K3/8 b - - 0 1",
            "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R w KQkq - 1 5")])
        with tempfile.TemporaryDirectory() as folder:
            for size in SIZES:
                out = Path(folder) / size
                train_main(["--size", size, "--smoke", "600", "--epochs", "1", "--batch", "128",
                            "--out", str(out), "--quiet"])
                weights, meta = load_exported(out)
                self.assertEqual(meta["layers"], [FEATURES] + SIZES[size] + [1])
                self.assertEqual(meta["epochs"], 1)
                import tensorflow as tf
                model = tf.keras.models.load_model(out / "model.keras", compile=False)
                keras = model.predict(expand(positions), verbose=0)[:, 0]
                self.assertTrue(np.allclose(keras, forward(weights, expand(positions)), atol=1e-4))
            out = Path(folder) / "small"
            train_main(["--size", "small", "--smoke", "600", "--epochs", "1", "--batch", "128",
                        "--out", str(out), "--resume", "--quiet"])
            self.assertEqual(load_exported(out)[1]["epochs"], 2)
            self.assertTrue(build("medium").count_params() > build("small").count_params())


if __name__ == "__main__":
    unittest.main()
