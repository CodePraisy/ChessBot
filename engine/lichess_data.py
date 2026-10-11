"""Builds training data from the Lichess open database (CC0). Never trains a model.

  python engine/lichess_data.py evals                  # stream Stockfish-scored positions (default 20M)
  python engine/lichess_data.py evals --positions 2000000 --keep 0.25
  python engine/lichess_data.py evals --source lichess_db_eval.jsonl.zst   # a file you downloaded
  python engine/lichess_data.py puzzles                # rated tactics puzzles for the Java strength test

Positions are written as compact shards (see tf_features.py): engine/data/lichess/train-*.npz and
val-*.npz (about 2% of positions, chosen by a hash of the position so the split never changes).
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import random
import sys
import tempfile
import time
import urllib.request

import numpy as np

from tf_features import COMPACT, CP_LIMIT, compact_from_fen

EVAL_URL = "https://database.lichess.org/lichess_db_eval.jsonl.zst"
PUZZLE_URL = "https://database.lichess.org/lichess_db_puzzle.csv.zst"
DATA = Path(__file__).resolve().parent / "data"


def open_text(source: str):
    """Streams a local or remote .zst / plain text file line by line without storing the whole file."""
    raw = urllib.request.urlopen(source, timeout=60) if source.startswith(("http://", "https://")) else open(source, "rb")
    if source.endswith(".zst"):
        import zstandard
        raw = zstandard.ZstdDecompressor().stream_reader(raw)
    return io.TextIOWrapper(raw, encoding="utf-8")


def parse_eval(line: str, min_depth: int, quiet_only: bool):
    """Returns (compact position, centipawns for the side to move, fen) or None if skipped."""
    record = json.loads(line)
    evals = [e for e in record.get("evals", []) if e.get("pvs")]
    if not evals:
        return None
    best = max(evals, key=lambda e: e.get("depth", 0))
    if best.get("depth", 0) < min_depth:
        return None
    pv = best["pvs"][0]
    fen = record["fen"]
    white = fen.split()[1] == "w"
    if "mate" in pv:
        cp = CP_LIMIT if pv["mate"] > 0 else -CP_LIMIT
    elif "cp" in pv:
        cp = max(-CP_LIMIT, min(CP_LIMIT, int(pv["cp"])))
    else:
        return None
    compact = compact_from_fen(fen)
    if quiet_only and "mate" not in pv:
        # The evaluator scores quiet positions; captures and promotions are left to the search.
        first = pv.get("line", "").split(" ")[0]
        if len(first) == 5:
            return None
        if len(first) >= 4:
            square = (8 - int(first[3])) * 8 + (ord(first[2]) - 97)
            if compact[square if white else square ^ 56]:
                return None
    return compact, (cp if white else -cp), fen


def is_validation(fen: str, every: int) -> bool:
    return int(hashlib.blake2b(fen.split(" ")[0].encode(), digest_size=4).hexdigest(), 16) % every == 0


def atomic_save(path: Path, **arrays):
    with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".tmp", delete=False) as handle:
        np.savez(handle, **arrays)
        temporary = Path(handle.name)
    os.replace(temporary, path)


class ShardWriter:
    def __init__(self, folder: Path, prefix: str, size: int):
        self.folder, self.prefix, self.size = folder, prefix, size
        self.x, self.cp, self.count = [], [], len(list(folder.glob(prefix + "-*.npz")))
        self.written = 0

    def add(self, compact, cp):
        self.x.append(compact)
        self.cp.append(cp)
        if len(self.x) >= self.size:
            self.flush()

    def flush(self):
        if not self.x:
            return
        atomic_save(self.folder / f"{self.prefix}-{self.count:05d}.npz",
                    x=np.stack(self.x).astype(np.uint8), cp=np.asarray(self.cp, np.int16))
        self.count += 1
        self.written += len(self.x)
        self.x, self.cp = [], []


def evals(args):
    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)
    train = ShardWriter(out, "train", args.shard_size)
    val = ShardWriter(out, "val", max(1, args.shard_size // 10))
    lines = kept = skipped = 0
    started = time.time()
    print(f"Reading {args.source}\nKeeping up to {args.positions:,} positions in {out}", flush=True)
    try:
        with open_text(args.source) as stream:
            for line in stream:
                lines += 1
                if args.keep < 1 and rng.random() >= args.keep:
                    continue
                try:
                    item = parse_eval(line, args.min_depth, not args.include_tactical)
                except (ValueError, KeyError, IndexError):
                    item = None
                if item is None:
                    skipped += 1
                    continue
                compact, cp, fen = item
                (val if args.val_every and is_validation(fen, args.val_every) else train).add(compact, cp)
                kept += 1
                if kept % 100_000 == 0:
                    rate = lines / max(1e-9, time.time() - started)
                    print(f"  {kept:,} kept | {lines:,} lines read | {rate:,.0f} lines/s", flush=True)
                if kept >= args.positions:
                    break
    except KeyboardInterrupt:
        print("Stopped; positions read so far are kept.")
    finally:
        train.flush()
        val.flush()
        info = {"source": args.source, "lines_read": lines, "kept": kept, "skipped": skipped,
                "train_positions": train.written, "val_positions": val.written,
                "min_depth": args.min_depth, "quiet_only": not args.include_tactical, "keep": args.keep,
                "format": "compact65", "finished": time.strftime("%Y-%m-%d %H:%M:%S")}
        manifest = out / "data.json"
        old = json.loads(manifest.read_text()) if manifest.exists() else {}
        info["runs"] = old.get("runs", []) + [dict(info)]
        manifest.write_text(json.dumps(info, indent=2))
        print(f"Done: {train.written:,} training and {val.written:,} validation positions written to {out}")


def puzzles(args):
    bands = {}
    with open_text(args.source) as stream:
        reader = csv.reader(stream)
        header = next(reader)
        col = {name: i for i, name in enumerate(header)}
        for row in reader:
            rating = int(row[col["Rating"]])
            band = min(rating // 400, 7)
            keep = bands.setdefault(band, [])
            if len(keep) < args.per_band:
                keep.append([row[col["PuzzleId"]], row[col["FEN"]], row[col["Moves"]], rating])
            if all(len(bands.get(b, [])) >= args.per_band for b in range(1, 8)):
                break
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["PuzzleId", "FEN", "Moves", "Rating"])
        for band in sorted(bands):
            writer.writerows(bands[band])
    print(f"Wrote {sum(len(v) for v in bands.values())} puzzles to {args.out}")


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    e = sub.add_parser("evals", help="Stockfish-scored positions for training")
    e.add_argument("--source", default=EVAL_URL, help="URL or local .jsonl/.jsonl.zst file")
    e.add_argument("--out", type=Path, default=DATA / "lichess")
    e.add_argument("--positions", type=int, default=20_000_000)
    e.add_argument("--keep", type=float, default=1.0, help="keep each line with this probability (spreads the sample)")
    e.add_argument("--min-depth", type=int, default=12)
    e.add_argument("--include-tactical", action="store_true", help="also keep positions whose best move is a capture")
    e.add_argument("--val-every", type=int, default=50, help="1 in N positions go to validation (0 = none)")
    e.add_argument("--shard-size", type=int, default=1_000_000)
    e.add_argument("--seed", type=int, default=1)
    z = sub.add_parser("puzzles", help="rated puzzles for the strength test")
    z.add_argument("--source", default=PUZZLE_URL)
    z.add_argument("--out", type=Path, default=DATA / "puzzles.csv")
    z.add_argument("--per-band", type=int, default=250, help="puzzles per 400-point rating band")
    args = p.parse_args(argv)
    if args.command == "evals":
        if args.positions <= 0 or not 0 < args.keep <= 1 or args.shard_size <= 0:
            p.error("positions and shard-size must be positive; keep must be in (0, 1]")
        evals(args)
    else:
        puzzles(args)


if __name__ == "__main__":
    sys.exit(main())
