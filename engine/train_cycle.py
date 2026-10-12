"""The whole training cycle in one command: data -> main models -> one gym per size.

  python engine/train_cycle.py                                  # all five sizes, 8 epochs, 5 generations
  python engine/train_cycle.py --epochs 8 --generations 10
  python engine/train_cycle.py --sizes small,medium --epochs 4 --generations 3
  python engine/train_cycle.py --dry-run                        # print the steps without running them

For each size:
  1. main training: engine/models/tf/<size> is trained up to --epochs epochs ("Neural <size>")
  2. gym start: a population of variants of that size, in engine/models/tf-gym/<size>
  3. gym refine: up to --generations rounds. Each round the variants of that size play a tournament
     (only against each other, plus Classic engine as a fixed yardstick), only the winner survives,
     and the empty places are refilled with new variants of the winner (fine-tuned on its self-play).
     The winner is published as "Neural gym-<size>-0000123".

Sizes never play each other: small variants only compete with small variants, and so on.
Ctrl+C is safe. Running the same command again skips finished work and continues where it stopped;
raising --epochs or --generations later trains the extra epochs / rounds only.
Run run.bat once first: the gym tournaments use the compiled Java engine.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

ENGINE = Path(__file__).resolve().parent
ROOT = ENGINE.parent
DATA = ENGINE / "data" / "lichess"
MODELS = ENGINE / "models" / "tf"
GYMS = ENGINE / "models" / "tf-gym"
ALL_SIZES = ["super-small", "small", "medium", "large", "larger"]


def run(cmd: list[str], dry: bool) -> None:
    print("\n>>", " ".join(str(c) for c in cmd), flush=True)
    if not dry:
        subprocess.run([sys.executable, *map(str, cmd)], cwd=ROOT, check=True)


def trained_epochs(size: str) -> int:
    meta = MODELS / size / "model.json"
    return json.loads(meta.read_text()).get("epochs", 0) if meta.exists() else 0


def gym_round(folder: Path) -> int:
    state = folder / "gym.json"
    return json.loads(state.read_text()).get("round", 0) if state.exists() else 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--sizes", default=",".join(ALL_SIZES), help="comma-separated sizes (default: all five)")
    p.add_argument("--epochs", type=int, default=8, help="main training epochs per size")
    p.add_argument("--generations", type=int, default=5, help="gym refine rounds per size")
    p.add_argument("--positions", type=int, default=2_000_000, help="Lichess positions to download if none exist")
    p.add_argument("--max-positions", type=int, default=0, help="positions per main-training epoch (0 = all)")
    p.add_argument("--population", type=int, default=6, help="gym members per size")
    p.add_argument("--gym-epochs", type=int, default=2, help="epochs for each gym variant in stage 1")
    p.add_argument("--gym-positions", type=int, default=2_000_000, help="positions per epoch for gym variants")
    p.add_argument("--games", type=int, default=4, help="tournament games per pairing")
    p.add_argument("--survivors", type=int, default=1, help="variants of each size kept each generation")
    p.add_argument("--threads", type=int, default=max(1, (os.cpu_count() or 2) // 2))
    p.add_argument("--selfplay-games", type=int, default=100)
    p.add_argument("--no-gym", action="store_true", help="main training only")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args(argv)
    sizes = [s.strip() for s in args.sizes.split(",") if s.strip()]
    bad = [s for s in sizes if s not in ALL_SIZES]
    if bad:
        p.error(f"unknown size(s) {', '.join(bad)}; choose from {', '.join(ALL_SIZES)}")

    # 0. data
    if not list(DATA.glob("train-*.npz")):
        run(["engine/lichess_data.py", "evals", "--positions", args.positions], args.dry_run)
    else:
        print(f"Data: using existing shards in {DATA}")

    # 1. main models
    for size in sizes:
        done = trained_epochs(size)
        if done >= args.epochs:
            print(f"Main {size}: {done} epochs already, skipping")
            continue
        cmd = ["engine/train_tf.py", "--size", size, "--epochs", args.epochs - done, "--quiet"]
        if done:
            cmd.append("--resume")
        if args.max_positions:
            cmd += ["--max-positions", args.max_positions]
        print(f"\n=== Main {size}: epoch {done} -> {args.epochs} ===")
        run(cmd, args.dry_run)

    if args.no_gym:
        return

    # 2-3. one gym per size
    for size in sizes:
        folder = GYMS / size
        gym = ["engine/gym_tf.py", "--dir", folder, "--champion", f"gym-{size}"]
        print(f"\n=== Gym {size} ===")
        run(gym + ["start", "--sizes", size, "--population", args.population,
                   "--epochs", args.gym_epochs, "--positions", args.gym_positions, "--quiet"], args.dry_run)
        left = args.generations - gym_round(folder)
        if left <= 0:
            print(f"Gym {size}: {args.generations} generations already done, skipping")
            continue
        run(gym + ["refine", "--rounds", left, "--games", args.games, "--threads", args.threads,
                   "--selfplay-games", args.selfplay_games, "--survivors", args.survivors, "--quiet"], args.dry_run)

    print("\nCycle finished. Restart the game to see the new models.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopped. Run the same command again to continue.")
    except subprocess.CalledProcessError as e:
        sys.exit(e.returncode)
