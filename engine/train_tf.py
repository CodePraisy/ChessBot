"""Trains one TensorFlow model size on Lichess positions (prepared by lichess_data.py).

  python engine/train_tf.py --size small                 # train from scratch
  python engine/train_tf.py --size small --resume        # continue where the last run stopped
  python engine/train_tf.py --size large --epochs 6 --max-positions 5000000
  python engine/train_tf.py --list                       # sizes and parameter counts

Each finished epoch saves engine/models/tf/<size>/ (model.keras for more training, weights.npz +
model.json for the Java game). Ctrl+C stops; the last finished epoch is kept.
"""
from __future__ import annotations

import os
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import argparse
import csv
import json
from pathlib import Path
import sys
import tempfile
import time

import numpy as np

from tf_features import COMPACT, CP_LIMIT, SCALE, target, tf_expand
from tf_models import MODELS, SIZES, build, export, parameter_count

DATA = Path(__file__).resolve().parent / "data" / "lichess"


def shards(folder: Path, prefix: str) -> list[Path]:
    return sorted(folder.glob(prefix + "-*.npz"))


def count(files) -> int:
    total = 0
    for f in files:
        with np.load(f) as d:
            total += len(d["cp"])
    return total


def train_dataset(files, batch, seed, limit):
    import tensorflow as tf
    rng = np.random.default_rng(seed)

    def batches():
        seen = 0
        for i in rng.permutation(len(files)):
            with np.load(files[i]) as d:
                x, cp = d["x"], d["cp"]
            order = rng.permutation(len(x))
            for start in range(0, len(order) - batch + 1, batch):
                pick = order[start:start + batch]
                yield x[pick], target(cp[pick])[:, None]
                seen += batch
                if limit and seen >= limit:
                    return
    spec = (tf.TensorSpec([batch, COMPACT], tf.uint8), tf.TensorSpec([batch, 1], tf.float32))
    return (tf.data.Dataset.from_generator(batches, output_signature=spec)
            .map(lambda x, y: (tf_expand(x), y), num_parallel_calls=tf.data.AUTOTUNE)
            .repeat().prefetch(tf.data.AUTOTUNE))


def validation_dataset(files, batch, limit):
    import tensorflow as tf
    xs, cps, total = [], [], 0
    for f in files:
        with np.load(f) as d:
            xs.append(d["x"]), cps.append(d["cp"])
        total += len(cps[-1])
        if total >= limit:
            break
    if not xs:
        return None
    x, cp = np.concatenate(xs)[:limit], np.concatenate(cps)[:limit]
    return (tf.data.Dataset.from_tensor_slices((x, target(cp)[:, None])).batch(batch)
            .map(lambda a, b: (tf_expand(a), b)).prefetch(2))


def cp_error(y, logit):
    """Mean absolute error in centipawns (targets clipped to +-CP_LIMIT)."""
    import tensorflow as tf
    y = tf.clip_by_value(y, 1e-4, 1 - 1e-4)
    wanted = tf.math.log(y / (1 - y)) * SCALE
    return tf.reduce_mean(tf.abs(tf.clip_by_value(logit * SCALE, -CP_LIMIT, CP_LIMIT) - wanted))


def smoke_data(folder: Path, positions: int, seed: int):
    """Tiny synthetic data (random legal positions scored by material) used only to test the code path."""
    import chess
    from lichess_data import ShardWriter
    from tf_features import compact_from_fen
    rng = np.random.default_rng(seed)
    values = {chess.PAWN: 100, chess.KNIGHT: 320, chess.BISHOP: 330, chess.ROOK: 500, chess.QUEEN: 900, chess.KING: 0}
    train, val = ShardWriter(folder, "train", positions), ShardWriter(folder, "val", positions)
    for i in range(positions + positions // 10):
        board = chess.Board()
        for _ in range(int(rng.integers(0, 40))):
            moves = list(board.legal_moves)
            if not moves:
                break
            board.push(moves[int(rng.integers(len(moves)))])
        score = sum(values[p.piece_type] * (1 if p.color == board.turn else -1) for p in board.piece_map().values())
        (train if i < positions else val).add(compact_from_fen(board.fen()), score)
    train.flush(), val.flush()


def train(args):
    import tensorflow as tf
    out = args.out or MODELS / args.size
    out.mkdir(parents=True, exist_ok=True)
    data = args.data
    temporary = None
    if args.smoke:
        temporary = tempfile.TemporaryDirectory()
        data = Path(temporary.name)
        smoke_data(data, args.smoke, args.seed)
    train_files, val_files = shards(data, "train"), shards(data, "val")
    if not train_files:
        raise SystemExit(f"No training shards in {data}. Run: python engine/lichess_data.py evals")
    available = count(train_files)
    per_epoch = min(available, args.max_positions) if args.max_positions else available
    steps = per_epoch // args.batch
    if steps <= 0:
        raise SystemExit(f"Only {available} positions: fewer than one batch of {args.batch}")

    previous = {}
    keras_file = out / "model.keras"
    if args.resume and keras_file.exists():
        model = tf.keras.models.load_model(keras_file, compile=False)
        if (out / "model.json").exists():
            previous = json.loads((out / "model.json").read_text())
        print(f"Resuming {args.size} after {previous.get('epochs', 0)} epochs")
    else:
        if keras_file.exists() and not args.smoke:
            raise SystemExit(f"{out} already has a model. Use --resume to continue it, or --out for a new folder.")
        model = build(args.size, args.seed)
    schedule = tf.keras.optimizers.schedules.CosineDecay(args.lr, steps * args.epochs, alpha=0.05)
    model.compile(optimizer=tf.keras.optimizers.Adam(schedule),
                  loss=tf.keras.losses.BinaryCrossentropy(from_logits=True), metrics=[cp_error])

    state = {"epochs": previous.get("epochs", 0), "trained_positions": previous.get("trained_positions", 0)}
    source = {}
    if (data / "data.json").exists():
        source = {k: v for k, v in json.loads((data / "data.json").read_text()).items() if k != "runs"}

    class Saver(tf.keras.callbacks.Callback):
        def on_epoch_end(self, epoch, logs=None):
            logs = logs or {}
            state["epochs"] += 1
            state["trained_positions"] += steps * args.batch
            with tempfile.NamedTemporaryFile(dir=out, suffix=".keras", delete=False) as handle:
                saved = Path(handle.name)
            self.model.save(saved)
            os.replace(saved, keras_file)
            info = dict(size=args.size, epochs=state["epochs"], trained_positions=state["trained_positions"],
                        loss=logs.get("loss"), cp_error=logs.get("cp_error"),
                        val_loss=logs.get("val_loss"), val_cp_error=logs.get("val_cp_error"),
                        data=source if not args.smoke else {"source": "synthetic smoke test"},
                        batch=args.batch, learning_rate=args.lr, name=f"Neural {args.size}")
            export(self.model, out, info)
            log = out / "train_log.csv"
            new = not log.exists()
            with log.open("a", newline="") as handle:
                writer = csv.writer(handle)
                if new:
                    writer.writerow(["time", "epoch", "positions", "loss", "cp_error", "val_loss", "val_cp_error"])
                writer.writerow([time.strftime("%Y-%m-%d %H:%M:%S"), state["epochs"], state["trained_positions"],
                                 logs.get("loss"), logs.get("cp_error"), logs.get("val_loss"), logs.get("val_cp_error")])
            val = logs.get("val_cp_error")
            print(f"  saved epoch {state['epochs']} to {out}" + (f" | validation error {val:.0f} centipawns" if val else ""))

    print(f"Model {args.size}: {parameter_count(args.size):,} parameters | {per_epoch:,} positions per epoch "
          f"| {args.epochs} epochs | batch {args.batch}", flush=True)
    try:
        model.fit(train_dataset(train_files, args.batch, args.seed + state["epochs"], per_epoch),
                  validation_data=validation_dataset(val_files, 4096, args.val_positions),
                  epochs=args.epochs, steps_per_epoch=steps, callbacks=[Saver()], shuffle=False, verbose=2 if args.quiet else 1)
    except KeyboardInterrupt:
        print("\nStopped. The last finished epoch is saved; --resume continues from it.")
    finally:
        if temporary:
            temporary.cleanup()
    return out


def parser():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--size", choices=list(SIZES), default="small")
    p.add_argument("--data", type=Path, default=DATA)
    p.add_argument("--out", type=Path, help="default: engine/models/tf/<size>")
    p.add_argument("--epochs", type=int, default=4)
    p.add_argument("--batch", type=int, default=1024)
    p.add_argument("--lr", type=float, default=1e-3)
    p.add_argument("--max-positions", type=int, default=0, help="positions per epoch (0 = all)")
    p.add_argument("--val-positions", type=int, default=200_000)
    p.add_argument("--resume", action="store_true")
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--quiet", action="store_true", help="one line per epoch instead of a progress bar")
    p.add_argument("--smoke", type=int, default=0, help=argparse.SUPPRESS)  # tests only: synthetic data
    p.add_argument("--list", action="store_true", help="show the model sizes")
    return p


def main(argv=None):
    p = parser()
    args = p.parse_args(argv)
    if args.list:
        for name, layers in SIZES.items():
            print(f"  {name:12s} 772 -> {' -> '.join(map(str, layers))} -> 1   ({parameter_count(name):,} parameters)")
        return
    if min(args.epochs, args.batch) <= 0 or args.lr <= 0:
        p.error("epochs, batch and lr must be positive")
    train(args)


if __name__ == "__main__":
    sys.exit(main())
