"""Incremental standard-chess draw checks, including claims on the next move."""
from collections import Counter

import chess


class RepetitionTracker:
    def __init__(self, board: chess.Board):
        self.counts = Counter()
        history = board.copy(stack=True)
        while True:
            self.counts[history._transposition_key()] += 1
            if not history.move_stack:
                break
            history.pop()
        self.repeated = sum(count >= 2 for count in self.counts.values())

    def add(self, board):
        # python-chess 1.11.2's key includes legal en passant and castling rights.
        key = board._transposition_key()
        self.counts[key] += 1
        if self.counts[key] == 2:
            self.repeated += 1
        return key

    def remove(self, key):
        if self.counts[key] == 2:
            self.repeated -= 1
        self.counts[key] -= 1
        if not self.counts[key]:
            del self.counts[key]

    def outcome(self, board):
        if type(board) is not chess.Board:
            return board.outcome(claim_draw=True)
        legal = list(board.legal_moves)
        if not legal and board.is_check():
            return chess.Outcome(chess.Termination.CHECKMATE, not board.turn)
        if board.is_insufficient_material():
            return chess.Outcome(chess.Termination.INSUFFICIENT_MATERIAL, None)
        if not legal:
            return chess.Outcome(chess.Termination.STALEMATE, None)
        if board.halfmove_clock >= 150:
            return chess.Outcome(chess.Termination.SEVENTYFIVE_MOVES, None)
        count = self.counts[board._transposition_key()]
        if count >= 5:
            return chess.Outcome(chess.Termination.FIVEFOLD_REPETITION, None)
        if board.can_claim_fifty_moves():
            return chess.Outcome(chess.Termination.FIFTY_MOVES, None)
        if count >= 3:
            return chess.Outcome(chess.Termination.THREEFOLD_REPETITION, None)
        # Most positions have no repeated history: no need to probe every reply.
        if self.repeated:
            for move in legal:
                board.push(move)
                try:
                    if self.counts[board._transposition_key()] >= 2:
                        return chess.Outcome(chess.Termination.THREEFOLD_REPETITION, None)
                finally:
                    board.pop()
        return None
