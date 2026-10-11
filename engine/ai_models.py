"""Legal chess search using the gym's learned value network."""
from __future__ import annotations

import time
from threading import Event

import chess
import numpy as np

from neural_model import ValueNetwork, encode
from repetition import RepetitionTracker

MATE_SCORE = 100_000


class SearchStopped(Exception):
    pass


class ChessModel:
    def __init__(self, network: ValueNetwork, name: str = "Untrained model"):
        self.network, self.name = network, name

    def quick_move(self, board: chess.Board, rng: np.random.Generator | None = None,
                   exploration: float = 0.0,
                   repetitions: RepetitionTracker | None = None) -> chess.Move | None:
        """Batch one-ply inference keeps terminal self-play practical on a CPU."""
        moves = list(board.legal_moves)
        if not moves:
            return None
        if rng is not None and rng.random() < exploration:
            return moves[int(rng.integers(len(moves)))]
        mover = 1 if board.turn else -1
        repetitions = repetitions or RepetitionTracker(board)
        features, terminal = [], {}
        for i, move in enumerate(moves):
            board.push(move)
            key = repetitions.add(board)
            try:
                features.append(encode(board))
                result = repetitions.outcome(board)
                if result is not None and result.termination == chess.Termination.CHECKMATE:
                    return move
                if result is not None:
                    terminal[i] = 0.0
            finally:
                repetitions.remove(key)
                board.pop()
        scores = self.network.predict(np.stack(features)) * mover
        for i, score in terminal.items():
            scores[i] = score
        return moves[int(np.argmax(scores))]

    def choose(self, board: chess.Board, skill: int, time_ms: int,
               stop: Event | None = None, max_depth: int | None = None) -> tuple[chess.Move | None, int, int]:
        legal = list(board.legal_moves)
        if not legal:
            return None, 0, 0
        deadline = time.perf_counter() + max(0.001, (time_ms - 50) / 1000)
        best, score, completed = legal[0], 0, 0
        depth_limit = 1 + (max(1, min(10, skill)) - 1) // 3
        if max_depth is not None:
            depth_limit = min(depth_limit, max(1, int(max_depth)))
        for depth in range(1, depth_limit + 1):
            try:
                choices = self._root(board, depth, deadline, stop, prune=True,
                                     preferred=best if completed else None)
                best, score = choices[0]
                completed = depth
            except SearchStopped:
                break
        return best, score, completed

    def analyze(self, board: chess.Board, max_lines: int,
                stop: Event | None = None) -> tuple[list, int]:
        if max_lines <= 0:
            return [], 0
        choices, completed = [], 0
        deadline = time.perf_counter() + 2
        for depth in range(1, 4):
            try:
                choices = self._root(board, depth, deadline, stop)
                completed = depth
            except SearchStopped:
                break
        return [(move, score, [move]) for move, score in choices[:max_lines]], completed

    def _root(self, board, depth, deadline, stop, prune=False, preferred=None):
        choices = []
        repetitions = RepetitionTracker(board)
        moves = self._ordered_moves(board)
        if preferred in moves:
            moves.remove(preferred)
            moves.insert(0, preferred)
        alpha = -MATE_SCORE
        for move in moves:
            self._check_deadline(deadline, stop)
            board.push(move)
            key = repetitions.add(board)
            try:
                score = -self._negamax(board, depth - 1, -MATE_SCORE,
                                      -alpha if prune else MATE_SCORE, deadline, 1, stop, repetitions)
            finally:
                repetitions.remove(key)
                board.pop()
            choices.append((move, score))
            alpha = max(alpha, score)
        # Pruned losers can have bounds equal to alpha. Keep the earlier exact
        # best move ahead of these ties; analysis still requests full root scores.
        return sorted(choices, key=(lambda item: -item[1]) if prune
                      else (lambda item: (-item[1], item[0].uci())))

    def _negamax(self, board, depth, alpha, beta, deadline, ply, stop, repetitions):
        self._check_deadline(deadline, stop)
        outcome = repetitions.outcome(board)
        if outcome is not None:
            return 0 if outcome.winner is None else -MATE_SCORE + ply
        self._check_deadline(deadline, stop)
        if depth == 0:
            value = float(self.network.predict(encode(board)))
            return int(np.arctanh(np.clip(value, -0.999, 0.999)) * 600) * (1 if board.turn else -1)
        best = -MATE_SCORE
        for move in self._ordered_moves(board):
            board.push(move)
            key = repetitions.add(board)
            try:
                score = -self._negamax(board, depth - 1, -beta, -alpha, deadline, ply + 1, stop, repetitions)
            finally:
                repetitions.remove(key)
                board.pop()
            best, alpha = max(best, score), max(alpha, score)
            if alpha >= beta:
                break
        return best

    @staticmethod
    def _ordered_moves(board):
        return sorted(board.legal_moves, key=lambda m: (-int(board.is_capture(m)), -int(m.promotion or 0), m.uci()))

    @staticmethod
    def _check_deadline(deadline, stop):
        if (stop is not None and stop.is_set()) or time.perf_counter() >= deadline:
            raise SearchStopped
