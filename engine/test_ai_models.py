"""Fast verification only: temporary fixtures, scripted games and a synthetic gradient check."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
from threading import Event
import time
import unittest
from unittest.mock import patch

import chess
import numpy as np

from ai_models import ChessModel, SearchStopped
from bridge import handle, move_form
from neural_model import FEATURES, ValueNetwork, encode
from train_gym import (Candidate, GameResult, generation_step, load_state, parser,
                       play_game, populate, publish, record_score, select_survivors, train)


class GymTests(unittest.TestCase):
    def setUp(self):
        self.rng = np.random.default_rng(4)
        self.network = ValueNetwork(self.rng)

    def test_encoding_includes_special_state(self):
        board = chess.Board()
        board.push_uci("e2e4")
        x = encode(board)
        self.assertEqual((FEATURES,), x.shape)
        self.assertEqual(-1, x[768])
        self.assertEqual(1, x[777])  # e-file en passant
        self.assertTrue(np.all(x[769:773] == 1))

    def test_synthetic_gradient_changes_weights_and_reduces_loss(self):
        # A tiny numerical correctness check, not self-play or production training.
        x = np.stack([encode(chess.Board())] * 8)
        y = np.ones(8, dtype=np.float32)
        before = float(np.mean((self.network.predict(x) - y) ** 2))
        old_weights = self.network.w2.copy()
        after = self.network.fit(x, y, self.rng, epochs=3)
        self.assertLess(after, before)
        self.assertFalse(np.array_equal(old_weights, self.network.w2))

    def test_checkpoint_round_trip_and_mutation_preserve_parent(self):
        x = encode(chess.Board())
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "test.npz"
            self.network.save(path, {"id": "test", "trained_positions": 0})
            loaded, metadata = ValueNetwork.load(path)
            np.testing.assert_array_equal(self.network.predict(x), loaded.predict(x))
            self.assertEqual("test", metadata["id"])
        parent = self.network.w1.copy()
        child = self.network.offspring(self.rng, 0.02)
        np.testing.assert_array_equal(parent, self.network.w1)
        self.assertFalse(np.array_equal(parent, child.w1))

    def test_legal_moves_and_mate_in_both_colours(self):
        model = ChessModel(self.network)
        board = chess.Board()
        move, _, _ = model.choose(board, 1, 500)
        self.assertIn(move, board.legal_moves)
        for fen in ("7k/5Q2/6K1/8/8/8/8/8 w - - 0 1",
                    "8/8/8/8/8/6k1/5q2/7K b - - 0 1"):
            board = chess.Board(fen)
            board.push(model.quick_move(board))
            self.assertTrue(board.is_checkmate())

    def test_deadline_and_cancel_restore_board(self):
        board = chess.Board()
        model = ChessModel(self.network)
        initial = board.fen()
        with patch.object(model, "_check_deadline", side_effect=[None, None, SearchStopped]):
            with self.assertRaises(SearchStopped):
                model._root(board, 2, time.perf_counter() + 10, None)
        self.assertEqual(initial, board.fen())
        self.assertFalse(board.move_stack)
        stop = Event()
        stop.set()
        move, _, depth = model.choose(board, 10, 500, stop)
        self.assertIn(move, board.legal_moves)
        self.assertEqual(0, depth)

    def test_bridge_keeps_request_id_and_legal_lines(self):
        board = chess.Board()
        model = ChessModel(self.network)
        request = {"type": "move", "id": 17, "position": {"fen": board.fen()},
                   "limits": {"skillLevel": 1, "maxTimeMs": 500}}
        reply = handle(model, request)
        self.assertEqual(17, reply["id"])
        self.assertIn(reply["move"], [move_form(m) for m in board.legal_moves])
        request.update(type="analyze", maxLines=2)
        reply = handle(model, request)
        self.assertTrue(reply["lines"])
        self.assertTrue(all(x["move"] in [move_form(m) for m in board.legal_moves] for x in reply["lines"]))
        request["position"]["fen"] = "bad fen"
        self.assertEqual({"type": "error", "id": 17}, {k: handle(model, request)[k] for k in ("type", "id")})

    def test_scores_are_symmetric_and_only_top_ten_survive(self):
        population, _ = populate([], 12, 1, self.rng, 0.01)
        before = sum(m.metadata["rating"] for m in population)
        record_score(population[0], population[1], 1)
        self.assertAlmostEqual(before, sum(m.metadata["rating"] for m in population))
        for i, candidate in enumerate(population):
            candidate.points, candidate.matches = float(12 - i), 12
        winners = select_survivors(population)
        self.assertEqual(10, len(winners))
        self.assertEqual(population[:10], winners)

    def test_scripted_generation_publishes_and_resumes_ten(self):
        args = parser().parse_args(["--population", "12", "--training-games", "2", "--tournament-rounds", "1"])
        population, next_id = populate([], 12, 1, self.rng, 0.01)
        fixture = GameResult(0, "scripted draw", ["e2e4"], [encode(chess.Board())])
        x, y = np.empty((0, FEATURES), np.float32), np.empty(0, np.float32)
        # No real games or real fitting run here. Verify orchestration with fixtures.
        with patch("train_gym.play_game", return_value=fixture) as game, \
                patch.object(ValueNetwork, "fit", return_value=0.0) as fit, \
                contextlib.redirect_stdout(io.StringIO()):
            winners, x, y = generation_step(population, 0, args, self.rng, x, y, lambda *a: None)
        self.assertEqual(14, game.call_count)  # 2 self-play + 12 colour-swapped matches
        self.assertEqual(12, fit.call_count)
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            publish(root, 1, winners, next_id, self.rng, x, y)
            self.assertEqual(10, len(list(root.rglob("model-*.npz"))))
            restored, generation, next_id, rng, rx, ry = load_state(root, 42)
            self.assertEqual(1, generation)
            self.assertEqual([m.id for m in winners], [m.id for m in restored])
            np.testing.assert_array_equal(x, rx)
            np.testing.assert_array_equal(y, ry)
            self.assertEqual(self.rng.bit_generator.state, rng.bit_generator.state)
            new, _ = populate(restored, 12, next_id, rng, 0.01)
            self.assertEqual(12, len(new))
            self.assertEqual([m.id for m in restored], [m.id for m in new[:10]])
            self.assertTrue(all(m.metadata["parent"] in {w.id for w in restored} for m in new[10:]))

    def test_game_ply_limit_is_draw_and_keeps_input_unchanged(self):
        # One scripted legal move checks game termination, without self-play.
        candidates, _ = populate([], 12, 1, self.rng, 0.01)
        start = chess.Board()
        with patch.object(ChessModel, "quick_move", return_value=chess.Move.from_uci("e2e4")):
            result = play_game(candidates[0], candidates[1], self.rng, 1, start=start, record=True)
        self.assertEqual(0, result.outcome)
        self.assertEqual(["e2e4"], result.moves)
        self.assertEqual(1, len(result.positions))
        self.assertEqual(chess.STARTING_FEN, start.fen())

    def test_terminal_orchestration_resumes_and_releases_lock(self):
        fixture = GameResult(0, "scripted draw", ["e2e4"], [encode(chess.Board())])
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / "isolated-gym"
            args = parser().parse_args(["--output", str(root), "--population", "12",
                "--generations", "1", "--training-games", "1", "--tournament-rounds", "1"])
            with patch("train_gym.play_game", return_value=fixture), \
                    patch.object(ValueNetwork, "fit", return_value=0.0), \
                    contextlib.redirect_stdout(io.StringIO()):
                train(args)
                train(args)
            state = json.loads((root / "champions.json").read_text())
            self.assertEqual(2, state["generation"])
            self.assertEqual(10, len(state["models"]))
            self.assertFalse((root / "training.lock").exists())
            self.assertTrue((root / "generations/generation-000001/champions.json").exists())
            with patch("train_gym.generation_step", side_effect=KeyboardInterrupt), \
                    contextlib.redirect_stdout(io.StringIO()):
                train(args)
            self.assertEqual(state, json.loads((root / "champions.json").read_text()))
            self.assertFalse((root / "training.lock").exists())


if __name__ == "__main__":
    unittest.main()
