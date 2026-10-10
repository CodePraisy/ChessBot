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
from concurrent.futures import ProcessPoolExecutor

from ai_models import ChessModel, SearchStopped
from bridge import handle, move_form
from neural_model import FEATURES, ValueNetwork, encode
from repetition import RepetitionTracker
from train_gym import (Candidate, GameResult, generation_step, load_state, parser,
                       play_game, play_job, populate, publish, record_score, select_survivors, train)


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

    def test_fast_encoding_matches_piece_map_reference(self):
        board = chess.Board()
        for _ in range(80):
            expected = encode(board)
            expected[:768] = 0
            for sq, piece in board.piece_map().items():
                plane = (0 if piece.color else 6) + piece.piece_type - 1
                expected[plane * 64 + sq] = 1
            np.testing.assert_array_equal(expected, encode(board))
            legal = list(board.legal_moves)
            if not legal:
                break
            board.push(legal[int(self.rng.integers(len(legal)))])

    def test_incremental_outcomes_match_chess_including_claims(self):
        boards = [chess.Board(fen) for fen in (
            chess.STARTING_FEN,
            "7k/5Q2/6K1/8/8/8/8/8 w - - 0 1",
            "7k/6Q1/6K1/8/8/8/8/8 b - - 0 1",
            "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1",
            "7k/8/6K1/8/8/8/8/8 w - - 0 1",
            "7k/8/6K1/8/8/8/8/R7 w - - 99 1",
            "7k/8/6K1/8/8/8/8/R7 w - - 100 1",
            "7k/8/6K1/8/8/8/8/R7 w - - 150 1",
            "4k3/8/8/3pP3/8/8/8/4K2R w - d6 0 1")]
        repeated = chess.Board()
        for move in ["g1f3", "g8f6", "f3g1", "f6g8"] * 4:
            repeated.push_uci(move)
            boards.append(repeated.copy())
        for board in boards:
            tracker = RepetitionTracker(board)
            self.assertEqual(board.outcome(claim_draw=True), tracker.outcome(board))
            before = dict(tracker.counts), tracker.repeated, board.fen(), list(board.move_stack)
            for move in list(board.legal_moves):
                board.push(move)
                key = tracker.add(board)
                self.assertEqual(board.outcome(claim_draw=True), tracker.outcome(board))
                tracker.remove(key)
                board.pop()
            self.assertEqual(before, (dict(tracker.counts), tracker.repeated, board.fen(), list(board.move_stack)))
        board = chess.Board()
        tracker = RepetitionTracker(board)
        for _ in range(120):
            self.assertEqual(board.outcome(claim_draw=True), tracker.outcome(board))
            legal = list(board.legal_moves)
            if not legal:
                break
            board.push(legal[int(self.rng.integers(len(legal)))])
            tracker.add(board)

    def test_parallel_games_match_serial_with_fixed_seeds(self):
        population, _ = populate([], 12, 1, self.rng, 0.01)
        jobs = [(population[0], population[1], seed, 4, 0.1, None, True)
                for seed in (7, 8)]
        serial = list(map(play_job, jobs))
        with ProcessPoolExecutor(max_workers=2) as executor:
            parallel = list(executor.map(play_job, jobs))
        for a, b in zip(serial, parallel):
            self.assertEqual((a.moves, a.outcome, a.reason), (b.moves, b.outcome, b.reason))
            np.testing.assert_array_equal(a.positions, b.positions)

    def test_parallel_generation_matches_serial_selection_and_replay(self):
        args = parser().parse_args(["--population", "12", "--training-games", "1",
                                   "--tournament-rounds", "1", "--max-plies", "2"])
        outputs, states = [], []
        with ProcessPoolExecutor(max_workers=2) as executor:
            for pool in (None, executor):
                rng = np.random.default_rng(99)
                population, _ = populate([], 12, 1, rng, 0.01)
                protected = population[0].network.w1.copy()
                x, y = np.empty((0, FEATURES), np.float32), np.empty(0, np.float32)
                with patch.object(ValueNetwork, "fit", return_value=0.0) as fit, \
                        contextlib.redirect_stdout(io.StringIO()):
                    winners, x, y = generation_step(population, 10, args, rng, x, y,
                                                     lambda *a: None, pool)
                self.assertEqual(2, fit.call_count)
                np.testing.assert_array_equal(protected, population[0].network.w1)
                outputs.append(([m.metadata for m in winners], x, y))
                states.append(rng.bit_generator.state)
        self.assertEqual(outputs[0][0], outputs[1][0])
        np.testing.assert_array_equal(outputs[0][1], outputs[1][1])
        np.testing.assert_array_equal(outputs[0][2], outputs[1][2])
        self.assertEqual(states[0], states[1])

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
                "--generations", "1", "--training-games", "1", "--tournament-rounds", "1", "--workers", "1"])
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
