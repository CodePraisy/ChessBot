"""Terminal self-play training gym. Nothing trains when imported, checking status or verifying."""
from __future__ import annotations

# Limit BLAS threads before importing NumPy: these are many small CPU operations.
import os
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
os.environ.setdefault("OMP_NUM_THREADS", "1")

import argparse
from dataclasses import dataclass, field
import json
from pathlib import Path
import tempfile
import time
import unittest

import chess
import numpy as np

from ai_models import ChessModel
from neural_model import FEATURES, ValueNetwork, encode

SURVIVORS = 10
DEFAULT_OUTPUT = Path(__file__).resolve().parent / "models" / "gym"


@dataclass
class Candidate:
    network: ValueNetwork
    metadata: dict
    points: float = 0
    matches: int = 0

    @property
    def id(self):
        return self.metadata["id"]


@dataclass
class GameResult:
    outcome: float  # White's outcome: win=1, draw=0, loss=-1
    reason: str
    moves: list[str]
    positions: list[np.ndarray] = field(default_factory=list)


def opening(rng, plies=4):
    board = chess.Board()
    for _ in range(plies):
        moves = list(board.legal_moves)
        if not moves or board.is_game_over(claim_draw=True):
            break
        board.push(moves[int(rng.integers(len(moves)))])
    return board


def play_game(white: Candidate, black: Candidate, rng, max_plies: int,
              exploration: float = 0, start: chess.Board | None = None,
              record: bool = False) -> GameResult:
    board = start.copy(stack=True) if start is not None else opening(rng)
    models = {chess.WHITE: ChessModel(white.network), chess.BLACK: ChessModel(black.network)}
    positions = []
    for _ in range(max_plies):
        if board.is_game_over(claim_draw=True):
            break
        if record:
            positions.append(encode(board))
        move = models[board.turn].quick_move(board, rng if exploration else None, exploration)
        if move is None or move not in board.legal_moves:
            raise RuntimeError("Model returned an invalid move")
        board.push(move)
    result = board.outcome(claim_draw=True)
    if result is None:
        outcome, reason = 0.0, "ply limit (draw)"
    else:
        outcome = 0.0 if result.winner is None else (1.0 if result.winner else -1.0)
        reason = result.termination.name.lower()
    return GameResult(outcome, reason, [m.uci() for m in board.move_stack], positions)


def record_score(white, black, outcome):
    score = (outcome + 1) / 2
    expected = 1 / (1 + 10 ** ((black.metadata["rating"] - white.metadata["rating"]) / 400))
    change = 24 * (score - expected)
    white.metadata["rating"] += change
    black.metadata["rating"] -= change
    white.points += score
    black.points += 1 - score
    white.matches += 1
    black.matches += 1


def select_survivors(population):
    if len(population) < SURVIVORS or any(m.matches == 0 for m in population):
        raise ValueError("Every candidate must play before selecting ten survivors")
    return sorted(population, key=lambda m: (-(m.points / m.matches), -m.metadata["rating"], m.id))[:SURVIVORS]


def atomic_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                     suffix=".tmp", delete=False) as handle:
        json.dump(data, handle, indent=2)
        temporary = Path(handle.name)
    os.replace(temporary, path)


def publish(output, generation, winners, next_id, rng, replay_x, replay_y):
    """Publish a manifest only after all ten immutable checkpoints and replay data exist."""
    folder = output / "generations" / f"generation-{generation:06d}"
    folder.mkdir(parents=True, exist_ok=True)
    entries = []
    for rank, winner in enumerate(winners, 1):
        metadata = dict(winner.metadata, generation=generation, rank=rank,
                        tournament_score=winner.points / winner.matches)
        path = folder / (winner.id + ".npz")
        winner.network.save(path, metadata)
        entries.append(dict(metadata, checkpoint=path.relative_to(output).as_posix()))
    replay = folder / "replay.npz"
    with replay.open("wb") as handle:
        np.savez_compressed(handle, x=replay_x, y=replay_y)
    state = {"generation": generation, "next_id": next_id, "models": entries,
             "rng": rng.bit_generator.state, "replay": replay.relative_to(output).as_posix()}
    # Previous manifests/checkpoints are retained for recovery, but only ten are active.
    atomic_json(folder / "champions.json", state)
    atomic_json(output / "champions.json", state)


def load_state(output, seed):
    rng = np.random.default_rng(seed)
    manifest = output / "champions.json"
    if not manifest.exists():
        return [], 0, 1, rng, np.empty((0, FEATURES), np.float32), np.empty(0, np.float32)
    state = json.loads(manifest.read_text(encoding="utf-8"))
    if len(state["models"]) != SURVIVORS:
        raise ValueError("Saved gym must have exactly ten survivors")
    winners = []
    for entry in state["models"]:
        network, metadata = ValueNetwork.load(output / entry["checkpoint"])
        winners.append(Candidate(network, metadata))
    with np.load(output / state["replay"], allow_pickle=False) as replay:
        x, y = replay["x"].copy(), replay["y"].copy()
    rng.bit_generator.state = state["rng"]
    return winners, state["generation"], state["next_id"], rng, x, y


def populate(winners, size, next_id, rng, mutation):
    population = [Candidate(m.network, dict(m.metadata)) for m in winners]
    while len(population) < size:
        parent = winners[int(rng.integers(len(winners)))] if winners else None
        network = parent.network.offspring(rng, mutation) if parent else ValueNetwork(rng)
        metadata = {"id": f"model-{next_id:06d}", "parent": parent.id if parent else None,
                    "rating": parent.metadata["rating"] if parent else 1000.0,
                    "trained_positions": parent.metadata["trained_positions"] if parent else 0}
        population.append(Candidate(network, metadata))
        next_id += 1
    return population, next_id


def generation_step(population, elite_count, args, rng, replay_x, replay_y, log):
    new_x, new_y = [], []
    for game in range(args.training_games):
        a, b = rng.choice(len(population), 2, replace=False)
        result = play_game(population[a], population[b], rng, args.max_plies,
                           exploration=args.exploration, record=True)
        new_x.extend(result.positions)
        new_y.extend([result.outcome] * len(result.positions))
        log("self-play", game + 1, population[a], population[b], result)
    if new_x:
        replay_x = np.concatenate((replay_x, np.stack(new_x)))[-args.replay_size:]
        replay_y = np.concatenate((replay_y, np.asarray(new_y, np.float32)))[-args.replay_size:]
    if not len(replay_x):
        raise RuntimeError("Self-play produced no trainable positions")
    for model in population[elite_count:]:
        indices = rng.choice(len(replay_x), min(len(replay_x), args.samples_per_model), replace=False)
        loss = model.network.fit(replay_x[indices], replay_y[indices], rng,
                                 epochs=args.epochs, learning_rate=args.learning_rate)
        model.metadata["trained_positions"] += len(indices) * args.epochs
        print(f"  Learned {model.id}: loss={loss:.4f}, examples={len(indices)}", flush=True)
    # Evaluation has no exploration or weight updates. Each pairing uses both colours
    # and the same opening, so every candidate plays an equal number of games.
    match = 0
    for _ in range(args.tournament_rounds):
        order = rng.permutation(len(population))
        for index in range(0, len(order), 2):
            a, b = population[order[index]], population[order[index + 1]]
            start = opening(rng)
            for white, black in ((a, b), (b, a)):
                result = play_game(white, black, rng, args.max_plies, start=start)
                record_score(white, black, result.outcome)
                match += 1
                log("tournament", match, white, black, result)
    return select_survivors(population), replay_x, replay_y


def train(args):
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    lock = output / "training.lock"
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        raise RuntimeError(f"Gym is already running, or an interrupted run left {lock}. Check before removing it.")
    os.close(fd)
    try:
        winners, generation, next_id, rng, x, y = load_state(output, args.seed)
        print(f"Chess training gym | {args.population} candidates | top {SURVIVORS} survive")
        print(f"Output: {output}\nResuming generation {generation}. Ctrl+C stops; restart resumes the last completed generation.")
        for _ in range(args.generations):
            generation += 1
            started = time.perf_counter()
            population, next_id = populate(winners, args.population, next_id, rng, args.mutation)
            print(f"\nGeneration {generation} | {len(winners)} protected survivors + {len(population)-len(winners)} challengers", flush=True)
            with (output / "games.jsonl").open("a", encoding="utf-8") as history:
                def log(stage, number, white, black, result):
                    print(f"  {stage} {number}: {white.id} vs {black.id} | {result.outcome:+.0f} | {result.reason} | {len(result.moves)} plies", flush=True)
                    history.write(json.dumps({"generation": generation, "stage": stage, "white": white.id,
                        "black": black.id, "outcome": result.outcome, "reason": result.reason, "moves": result.moves}) + "\n")
                    history.flush()
                winners, x, y = generation_step(population, len(winners), args, rng, x, y, log)
            publish(output, generation, winners, next_id, rng, x, y)
            print(f"\n  Top 10 saved ({time.perf_counter()-started:.1f}s). Replay: {len(y)} positions")
            for rank, model in enumerate(winners, 1):
                print(f"  {rank:2d}. {model.id} | score {model.points:.1f}/{model.matches} | pool rating {model.metadata['rating']:.0f}")
        print("\nTraining finished. Restart the Java game to load the surviving models.")
    except KeyboardInterrupt:
        print("\nStopped. Last completed generation is saved; unfinished generation will be repeated.")
    finally:
        lock.unlink(missing_ok=True)


def parser():
    p = argparse.ArgumentParser(description="Self-play neural training gym; top ten models survive each generation.")
    p.add_argument("--generations", type=int, default=100, help="additional generations to run (default: 100)")
    p.add_argument("--population", type=int, default=20, help="even candidate count, greater than 10")
    p.add_argument("--training-games", type=int, default=20)
    p.add_argument("--tournament-rounds", type=int, default=2, help="two colour-swapped games per pairing per round")
    p.add_argument("--max-plies", type=int, default=160, help="unfinished games at this limit count as draws")
    p.add_argument("--epochs", type=int, default=3)
    p.add_argument("--samples-per-model", type=int, default=4096)
    p.add_argument("--replay-size", type=int, default=20000)
    p.add_argument("--learning-rate", type=float, default=0.01)
    p.add_argument("--mutation", type=float, default=0.01)
    p.add_argument("--exploration", type=float, default=0.1)
    p.add_argument("--seed", type=int, default=42, help="used only when starting a new gym")
    p.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    p.add_argument("--status", action="store_true", help="show saved survivors without training")
    p.add_argument("--verify", action="store_true", help="run isolated tests, without a training run")
    return p


def main():
    p = parser()
    args = p.parse_args()
    if args.verify:
        suite = unittest.defaultTestLoader.discover(str(Path(__file__).parent), pattern="test_*.py")
        raise SystemExit(0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1)
    if args.status:
        path = args.output / "champions.json"
        if not path.exists():
            print("No training has run yet. Run python engine/train_gym.py to start.")
        else:
            state = json.loads(path.read_text(encoding="utf-8"))
            print(f"Generation {state['generation']} | {len(state['models'])} survivors")
            for model in state["models"]:
                print(f"  #{model['rank']:2d} {model['id']} | pool rating {model['rating']:.0f} | trained positions {model['trained_positions']}")
        return
    if args.population <= SURVIVORS or args.population % 2:
        p.error("--population must be even and greater than 10")
    for name in ("generations", "training_games", "tournament_rounds", "max_plies", "epochs", "samples_per_model", "replay_size"):
        if getattr(args, name) <= 0:
            p.error(name.replace("_", "-") + " must be positive")
    if not 0 <= args.exploration <= 1 or not np.isfinite(args.mutation) or args.mutation < 0:
        p.error("exploration must be between 0 and 1; mutation must be finite and nonnegative")
    if not np.isfinite(args.learning_rate) or args.learning_rate <= 0:
        p.error("learning rate must be finite and positive")
    try:
        train(args)
    except (ValueError, RuntimeError, OSError) as error:
        p.exit(1, f"Gym error: {error}\n")


if __name__ == "__main__":
    main()
