"""Java JSON-lines inference adapter for a saved gym checkpoint. Never trains."""
from __future__ import annotations

import os
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
os.environ.setdefault("OMP_NUM_THREADS", "1")

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sys
from threading import Event, Lock

import chess
import numpy as np

from ai_models import ChessModel, MATE_SCORE
from neural_model import ValueNetwork

OUTPUT_LOCK = Lock()


def emit(message):
    with OUTPUT_LOCK:
        print(json.dumps(message, separators=(",", ":")), flush=True)


def move_form(move):
    result = [[7 - chess.square_rank(move.from_square), chess.square_file(move.from_square)],
              [7 - chess.square_rank(move.to_square), chess.square_file(move.to_square)]]
    if move.promotion:
        result.append(move.promotion)
    return result


def handle(model, message, stop=None):
    request_id = message.get("id")
    try:
        kind = message.get("type")
        if kind in ("hello", "cancel", "quit"):
            return None
        board = chess.Board(message["position"]["fen"])
        if not board.is_valid():
            raise ValueError("Invalid chess position")
        if kind == "move":
            limits = message.get("limits", {})
            move, _, _ = model.choose(board, int(limits.get("skillLevel", 5)),
                                      int(limits.get("maxTimeMs", 5000)), stop)
            if move is None:
                raise ValueError("Game is over")
            return {"type": "move", "id": request_id, "move": move_form(move)}
        if kind == "analyze":
            lines, depth = model.analyze(board, min(10, max(0, int(message.get("maxLines", 3)))), stop)
            suggestions = []
            for move, score, line in lines:
                mate = None
                if abs(score) > MATE_SCORE - 1000:
                    plies = MATE_SCORE - abs(score)
                    mate = max(1, (plies + 1) // 2) * (1 if score > 0 else -1)
                suggestions.append({"move": move_form(move), "centipawns": score if mate is None else None,
                    "mateIn": mate, "depth": depth, "line": [move_form(m) for m in line],
                    "comment": model.name + " neural evaluation"})
            return {"type": "analysis", "id": request_id, "lines": suggestions}
        raise ValueError("Unknown message type")
    except Exception as error:
        return {"type": "error", "id": request_id, "message": str(error)}


def main():
    parser = argparse.ArgumentParser(description="Run inference for one gym checkpoint.")
    parser.add_argument("--checkpoint", type=Path)
    args = parser.parse_args()
    if args.checkpoint:
        network, metadata = ValueNetwork.load(args.checkpoint)
        model = ChessModel(network, metadata["id"])
    else:
        # Used only for diagnostics before checkpoints exist; Java loads saved survivors.
        model = ChessModel(ValueNetwork(np.random.default_rng(0)))
    events = {}
    emit({"type": "ready", "name": model.name})
    with ThreadPoolExecutor(max_workers=1) as executor:
        def work(message, stop):
            response = handle(model, message, stop)
            if response is not None and not stop.is_set():
                emit(response)
            events.pop(message.get("id"), None)

        try:
            for line in sys.stdin:
                try:
                    message = json.loads(line)
                    if not isinstance(message, dict):
                        raise ValueError("Request must be an object")
                    kind, request_id = message.get("type"), message.get("id")
                    if kind == "quit":
                        break
                    if kind == "cancel":
                        active = events.get(request_id)
                        if active is not None:
                            active.set()
                    elif kind != "hello":
                        stop = Event()
                        events[request_id] = stop
                        executor.submit(work, message, stop)
                except (ValueError, TypeError) as error:
                    emit({"type": "error", "id": None, "message": str(error)})
        finally:
            for stop in list(events.values()):
                stop.set()


if __name__ == "__main__":
    main()
