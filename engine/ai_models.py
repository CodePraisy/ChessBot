"""Small searchable chess models used by bridge.py.

The Java application remains the rules authority, while these Python models use the
same FEN position to search only legal moves. Each model shares alpha-beta search and
has its own evaluation weights, so its play style is meaningfully different.
"""

from __future__ import annotations

from dataclasses import dataclass
import time
from typing import Iterable

import chess

MATE_SCORE = 100_000
PIECE_VALUE = {
    chess.PAWN: 100,
    chess.KNIGHT: 320,
    chess.BISHOP: 330,
    chess.ROOK: 500,
    chess.QUEEN: 900,
    chess.KING: 0,
}


@dataclass(frozen=True)
class ModelProfile:
    key: str
    name: str
    description: str
    center_weight: int
    pawn_advance_weight: int
    king_safety_weight: int
    check_bonus: int


MODELS = {
    "atlas": ModelProfile("atlas", "Atlas — Balanced", "Balanced", 6, 7, 24, 80),
    "blitz": ModelProfile("blitz", "Blitz — Attacking", "Attacking", 13, 10, 12, 180),
    "bastion": ModelProfile("bastion", "Bastion — Positional", "Positional", 5, 5, 42, 65),
}


class SearchStopped(Exception):
    pass


class ChessModel:
    def __init__(self, profile: ModelProfile):
        self.profile = profile

    def choose(self, board: chess.Board, skill: int, time_ms: int) -> tuple[chess.Move | None, int, int]:
        legal = list(board.legal_moves)
        if not legal:
            return None, 0, 0
        deadline = time.perf_counter() + max(0.25, (time_ms - 100) / 1000)
        best_move, best_score, completed_depth = legal[0], 0, 0
        for depth in range(1, self._depth_for(skill) + 1):
            try:
                choices = self._root(board, depth, deadline, 1)
                best_move, best_score = choices[0]
                completed_depth = depth
            except SearchStopped:
                break
        return best_move, best_score, completed_depth

    def analyze(self, board: chess.Board, max_lines: int) -> tuple[list[tuple[chess.Move, int, list[chess.Move]]], int]:
        deadline = time.perf_counter() + 2.0
        try:
            choices = self._root(board, 3, deadline, max(1, max_lines))
            lines: list[tuple[chess.Move, int, list[chess.Move]]] = []
            for move, score in choices:
                board.push(move)
                replies = self._root(board, 2, deadline, 1)
                board.pop()
                pv = [move] + ([replies[0][0]] if replies else [])
                lines.append((move, score, pv))
            return lines, 3
        except SearchStopped:
            return [], 0

    def _root(self, board: chess.Board, depth: int, deadline: float, wanted: int) -> list[tuple[chess.Move, int]]:
        choices: list[tuple[chess.Move, int]] = []
        for move in self._ordered_moves(board):
            self._check_deadline(deadline)
            board.push(move)
            score = -self._negamax(board, depth - 1, -MATE_SCORE, MATE_SCORE, deadline, 1)
            board.pop()
            choices.append((move, score))
        choices.sort(key=lambda item: (-item[1], item[0].uci()))
        return choices[:wanted]

    def _negamax(self, board: chess.Board, depth: int, alpha: int, beta: int, deadline: float, ply: int) -> int:
        self._check_deadline(deadline)
        if board.is_checkmate():
            return -MATE_SCORE + ply
        if board.is_game_over(claim_draw=False):
            return 0
        if depth == 0:
            return self._evaluate(board)
        best = -MATE_SCORE
        for move in self._ordered_moves(board):
            board.push(move)
            score = -self._negamax(board, depth - 1, -beta, -alpha, deadline, ply + 1)
            board.pop()
            best = max(best, score)
            alpha = max(alpha, score)
            if alpha >= beta:
                break
        return best

    def _evaluate(self, board: chess.Board) -> int:
        white_score = 0
        for square, piece in board.piece_map().items():
            score = PIECE_VALUE[piece.piece_type]
            rank = chess.square_rank(square)
            file = chess.square_file(square)
            center_distance = abs(rank - 3.5) + abs(file - 3.5)
            if piece.piece_type in (chess.KNIGHT, chess.BISHOP, chess.PAWN):
                score += int(max(0, 4 - center_distance) * self.profile.center_weight)
            if piece.piece_type == chess.PAWN:
                advance = rank if piece.color == chess.WHITE else 7 - rank
                score += advance * self.profile.pawn_advance_weight
            if piece.piece_type == chess.KING:
                score += self._king_safety(board, square, piece.color)
            white_score += score if piece.color == chess.WHITE else -score
        return white_score if board.turn == chess.WHITE else -white_score

    def _king_safety(self, board: chess.Board, king: chess.Square, color: chess.Color) -> int:
        rank, file = chess.square_rank(king), chess.square_file(king)
        home_rank = 0 if color == chess.WHITE else 7
        castled = rank == home_rank and file in (2, 6)
        score = self.profile.king_safety_weight if castled else 0
        pawn_rank = rank + (1 if color == chess.WHITE else -1)
        if 0 <= pawn_rank < 8:
            for file_offset in (-1, 0, 1):
                pawn_file = file + file_offset
                if 0 <= pawn_file < 8 and board.piece_at(chess.square(pawn_file, pawn_rank)) == chess.Piece(chess.PAWN, color):
                    score += self.profile.king_safety_weight // 3
        return score

    def _ordered_moves(self, board: chess.Board) -> Iterable[chess.Move]:
        def order(move: chess.Move) -> tuple[int, str]:
            moving = board.piece_at(move.from_square)
            captured = board.piece_at(move.to_square)
            score = 0
            if captured:
                score += PIECE_VALUE[captured.piece_type] * 20 - PIECE_VALUE[moving.piece_type]
            if move.promotion:
                score += PIECE_VALUE[move.promotion]
            board.push(move)
            if board.is_check():
                score += self.profile.check_bonus
            board.pop()
            return -score, move.uci()

        return sorted(board.legal_moves, key=order)

    @staticmethod
    def _depth_for(skill: int) -> int:
        if skill <= 2:
            return 1
        if skill <= 5:
            return 2
        if skill <= 8:
            return 3
        return 4

    @staticmethod
    def _check_deadline(deadline: float) -> None:
        if time.perf_counter() >= deadline:
            raise SearchStopped
