"""JSON-lines adapter between Java's externalProcessProvider and the Python AI models."""

from __future__ import annotations

import argparse
import json
import sys
from typing import Any

import chess

from ai_models import ChessModel, MODELS


def emit(message: dict[str, Any]) -> None:
    print(json.dumps(message, separators=(",", ":")), flush=True)


def move_form(move: chess.Move) -> list[Any]:
    result: list[Any] = [
        [7 - chess.square_rank(move.from_square), chess.square_file(move.from_square)],
        [7 - chess.square_rank(move.to_square), chess.square_file(move.to_square)],
    ]
    if move.promotion:
        result.append(move.promotion)
    return result


def handle(model: ChessModel, message: dict[str, Any]) -> dict[str, Any] | None:
    kind = message.get("type")
    if kind in ("hello", "cancel"):
        return None
    if kind == "quit":
        raise EOFError
    request_id = message.get("id")
    try:
        position = message["position"]
        board = chess.Board(position["fen"])
        if kind == "move":
            limits = message.get("limits", {})
            move, _, _ = model.choose(board, int(limits.get("skillLevel", 5)), int(limits.get("maxTimeMs", 5000)))
            if move is None:
                return {"type": "error", "id": request_id, "message": "game is over"}
            return {"type": "move", "id": request_id, "move": move_form(move)}
        if kind == "analyze":
            lines, depth = model.analyze(board, int(message.get("maxLines", 3)))
            return {
                "type": "analysis",
                "id": request_id,
                "lines": [
                    {
                        "move": move_form(move),
                        "centipawns": score,
                        "mateIn": None,
                        "depth": depth,
                        "line": [move_form(item) for item in line],
                        "comment": model.profile.description + " Python model",
                    }
                    for move, score, line in lines
                ],
            }
        return {"type": "error", "id": request_id, "message": "unknown message type"}
    except (KeyError, TypeError, ValueError, chess.InvalidMoveError) as error:
        return {"type": "error", "id": request_id, "message": str(error)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", choices=MODELS, default="atlas")
    args = parser.parse_args()
    model = ChessModel(MODELS[args.model])
    emit({"type": "ready", "name": model.profile.name})
    for line in sys.stdin:
        try:
            message = json.loads(line)
            response = handle(model, message)
            if response is not None:
                emit(response)
        except EOFError:
            return
        except Exception as error:  # Never let a malformed request kill the engine process.
            emit({"type": "error", "id": None, "message": str(error)})


if __name__ == "__main__":
    main()
