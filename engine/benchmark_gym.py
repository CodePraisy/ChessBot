"""Short, repeatable CPU benchmark; never loads or writes the user's gym."""
import argparse
from concurrent.futures import ProcessPoolExecutor
import time

from train_gym import Candidate, ValueNetwork, np, play_job, worker_init


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--games", type=int, default=16)
    p.add_argument("--workers", type=int, default=4)
    p.add_argument("--max-plies", type=int, default=160)
    args = p.parse_args()
    if min(args.games, args.workers, args.max_plies) <= 0:
        p.error("games, workers and max-plies must be positive")
    rng = np.random.default_rng(2026)
    a, b = [Candidate(ValueNetwork(rng), {"id": str(i)}) for i in range(2)]
    jobs = [(a, b, seed, args.max_plies, 0.1, None, True)
            for seed in range(2026, 2026 + args.games)]
    started = time.perf_counter()
    serial = list(map(play_job, jobs))
    serial_seconds = time.perf_counter() - started
    with ProcessPoolExecutor(max_workers=args.workers, initializer=worker_init) as executor:
        started = time.perf_counter()
        list(executor.map(play_job, jobs[:args.workers]))
        startup = time.perf_counter() - started
        started = time.perf_counter()
        parallel = list(executor.map(play_job, jobs))
        parallel_seconds = time.perf_counter() - started
    if [(r.moves, r.outcome, r.reason) for r in serial] != [(r.moves, r.outcome, r.reason) for r in parallel]:
        raise RuntimeError("Serial and parallel games disagree")
    plies = sum(len(r.positions) for r in serial)
    print(f"Identical games: {args.games}, total played plies: {plies}")
    print(f"Serial: {serial_seconds:.3f}s | {plies/serial_seconds:.0f} plies/s")
    print(f"{args.workers} workers: {parallel_seconds:.3f}s | {plies/parallel_seconds:.0f} plies/s | {serial_seconds/parallel_seconds:.2f}x serial")
    print(f"Worker startup/warm-up: {startup:.3f}s (once per training launch)")
    # One disposable synthetic fit measures gradient throughput, not chess progress.
    positions = np.stack([x for r in serial for x in r.positions])
    indices = np.arange(4096) % len(positions)
    x = positions[indices]
    y = np.zeros(len(x), np.float32)
    network = ValueNetwork(np.random.default_rng(1))
    started = time.perf_counter()
    network.fit(x, y, np.random.default_rng(2), epochs=3)
    fit_seconds = time.perf_counter() - started
    estimate = parallel_seconds / args.games * 60 + fit_seconds * 10
    print(f"Synthetic fit: 4096 positions x 3 epochs in {fit_seconds:.3f}s")
    print(f"Rough later-generation estimate (60 games + 10 fits): {estimate:.1f}s; actual game lengths vary")
    print("No saved models were read, trained or written.")


if __name__ == "__main__":
    main()
