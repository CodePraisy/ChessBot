import unittest

import chess

from ai_models import ChessModel, MODELS


class ChessModelTests(unittest.TestCase):
    def test_every_profile_returns_a_legal_move(self):
        board = chess.Board()
        models = [ChessModel(profile) for profile in MODELS.values()]
        moves = [model.choose(board.copy(), skill=1, time_ms=1_000)[0] for model in models]
        self.assertEqual(3, len({model.profile.name for model in models}))
        self.assertTrue(all(move in board.legal_moves for move in moves))

    def test_analysis_returns_legal_principal_variations(self):
        board = chess.Board()
        lines, depth = ChessModel(MODELS["atlas"]).analyze(board, max_lines=2)
        self.assertGreater(depth, 0)
        self.assertTrue(lines)
        self.assertTrue(all(move in board.legal_moves for move, _, _ in lines))


if __name__ == "__main__":
    unittest.main()
