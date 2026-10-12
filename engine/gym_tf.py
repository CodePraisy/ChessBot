"""The TensorFlow gym: a population of models, trained on Lichess, then refined by playing each other.

Run it after the main training (train_tf.py). Two stages:

  1. start   Trains a population of variants on Lichess positions. Each variant has slightly different
             settings (learning rate, batch size, regularisation, layer widths, random seed) and sees its
             own random slice of the data. If a main model already exists (engine/models/tf/<size>), its
             variants start from a copy of it instead of from nothing, and the original joins as-is.
  2. refine  Rounds of: tournament (the Java engine, every member vs every member, plus Classic as a fixed
             yardstick) -> the bottom half is deleted -> each survivor plays itself, its deeper search scores
             those positions -> children are copies of survivors, fine-tuned on that self-play data mixed with
             fresh Lichess positions (so they don't forget), each with a slightly mutated learning rate.
             Survivors are kept unchanged, so a child has to beat its parent to stay.
             The best member is copied to engine/models/tf/gym-champion ("Neural gym champion" in the game).

  python engine/gym_tf.py start --population 8 --sizes small,medium --positions 2000000 --epochs 2
  python engine/gym_tf.py refine --rounds 5
  python engine/gym_tf.py status
  python engine/gym_tf.py promote            # copy the current leader into the game again

Everything lives in engine/models/tf-gym/. Ctrl+C is safe: finished steps are saved and running the same
command again continues. Compile the Java side first (run.bat / run.sh) - the tournaments use it.
"""
from __future__ import annotations

import os
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import argparse
import csv
import json
import math
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

import numpy as np

from tf_features import target, tf_expand
from tf_models import MODELS, SIZES, build, export
from train_tf import DATA, count, cp_error, shards, smoke_data, train_dataset, validation_dataset

ROOT = Path(__file__).resolve().parent.parent
GYM = Path(__file__).resolve().parent / "models" / "tf-gym"
CLASSIC = "Classic engine"


# ---------------------------------------------------------------- state

def load_state(folder: Path) -> dict:
    path = folder / "gym.json"
    return json.loads(path.read_text()) if path.exists() else {}


def save_state(folder: Path, state: dict) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=folder, suffix=".tmp", delete=False) as handle:
        json.dump(state, handle, indent=2)
        temporary = Path(handle.name)
    os.replace(temporary, folder / "gym.json")


def member_dir(folder: Path, member: dict) -> Path:
    return folder / "population" / member["id"]


def new_id(state: dict) -> str:
    state["next_id"] = state.get("next_id", 0) + 1
    return f"m{state['next_id']:03d}"


# ---------------------------------------------------------------- training helpers

def compile_model(model, lr: float, total_steps: int):
    import tensorflow as tf
    schedule = tf.keras.optimizers.schedules.CosineDecay(lr, max(1, total_steps), alpha=0.1)
    model.compile(optimizer=tf.keras.optimizers.Adam(schedule),
                  loss=tf.keras.losses.BinaryCrossentropy(from_logits=True), metrics=[cp_error])


def save_member(model, folder: Path, state: dict, member: dict) -> None:
    out = member_dir(folder, member)
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=out, suffix=".keras", delete=False) as handle:
        saved = Path(handle.name)
    model.save(saved)
    os.replace(saved, out / "model.keras")
    info = {k: member.get(k) for k in ("size", "trained_positions", "val_cp_error", "learning_rate", "batch", "l2",
                                        "origin", "parent", "born_round")}
    export(model, out, dict(info, name=f"Gym {member['id']}", gym_member=member["id"]))
    save_state(folder, state)


def validation_error(model, val) -> float | None:
    if val is None:
        return None
    return float(model.evaluate(val, verbose=0, return_dict=True)["cp_error"])


def jitter_layers(base: list[int], rng) -> list[int]:
    return [max(8, int(round(w * rng.uniform(0.75, 1.25) / 8)) * 8) for w in base]


# ---------------------------------------------------------------- stage 1: start

def start(args):
    import tensorflow as tf
    folder = args.dir
    state = load_state(folder)
    data, temporary = args.data, None
    if args.smoke:
        temporary = tempfile.TemporaryDirectory()
        data = Path(temporary.name)
        smoke_data(data, args.smoke, args.seed)
    train_files, val_files = shards(data, "train"), shards(data, "val")
    if not train_files:
        raise SystemExit(f"No training shards in {data}. Run: python engine/lichess_data.py evals")
    available = count(train_files)
    per_epoch = min(available, args.positions) if args.positions else available
    val = validation_dataset(val_files, 4096, args.val_positions)

    if not state.get("members"):
        rng = np.random.default_rng(args.seed)
        sizes = [s.strip() for s in args.sizes.split(",") if s.strip()]
        for s in sizes:
            if s not in SIZES:
                raise SystemExit(f"Unknown size {s!r}; choose from {', '.join(SIZES)}")
        state = {"created": time.strftime("%Y-%m-%d %H:%M:%S"), "round": 0, "members": [], "history": [],
                 "config": {"sizes": sizes, "positions": per_epoch, "epochs": args.epochs, "data": str(data)}}
        for k in range(args.population):
            size = sizes[k % len(sizes)]
            main = MODELS / size / "model.keras"
            warm = main.exists() and not args.from_scratch and not args.smoke
            first_of_size = k < len(sizes)
            member = {"id": new_id(state), "size": size, "seed": int(rng.integers(1 << 30)), "born_round": 0,
                      "parent": None, "epochs": 0, "trained_positions": 0, "points": {}}
            if warm and first_of_size:
                # the main model joins unchanged, so the gym can never end up worse than what you started with
                member.update(origin=f"main {size}", hidden=None, learning_rate=None, batch=None, l2=0.0, epochs=args.epochs)
            elif warm:
                member.update(origin=f"variant of main {size}", hidden=None, l2=0.0,
                              learning_rate=float(10 ** rng.uniform(math.log10(1e-4), math.log10(6e-4))),
                              batch=int(rng.choice([512, 1024, 2048])))
            else:
                member.update(origin="from scratch", l2=float(rng.choice([0.0, 0.0, 1e-6, 1e-5])),
                              hidden=SIZES[size] if first_of_size else jitter_layers(SIZES[size], rng),
                              learning_rate=float(10 ** rng.uniform(math.log10(4e-4), math.log10(2e-3))),
                              batch=int(rng.choice([512, 1024, 2048])))
            state["members"].append(member)
        save_state(folder, state)
        print(f"New gym in {folder}: {args.population} members")

    try:
        for member in state["members"]:
            out = member_dir(folder, member)
            target_epochs = state["config"]["epochs"]
            if member["born_round"] > 0 or (member["epochs"] >= target_epochs and (out / "weights.npz").exists()):
                continue  # children are trained by refine
            if member["learning_rate"] is None:  # the main model, copied as-is
                model = tf.keras.models.load_model(MODELS / member["size"] / "model.keras", compile=False)
                main_meta = json.loads((MODELS / member["size"] / "model.json").read_text())
                member["trained_positions"] = int(main_meta.get("trained_positions", 0))
                member["val_cp_error"] = validation_error(_compiled(model), val)
                save_member(model, folder, state, member)
                print(f"{member['id']}: copied the main {member['size']} model")
                continue
            if (out / "model.keras").exists():
                model = tf.keras.models.load_model(out / "model.keras", compile=False)
            elif member["origin"].startswith("variant"):
                model = tf.keras.models.load_model(MODELS / member["size"] / "model.keras", compile=False)
                main_meta = json.loads((MODELS / member["size"] / "model.json").read_text())
                member["trained_positions"] = int(main_meta.get("trained_positions", 0))
            else:
                model = build(member["size"], member["seed"], member["hidden"], member["l2"])
            batch = min(member["batch"], per_epoch)
            steps = max(1, per_epoch // batch)
            left = target_epochs - member["epochs"]
            compile_model(model, member["learning_rate"], steps * left)
            layers = member["hidden"] or "main model"
            print(f"{member['id']}: {member['size']} {layers}, lr {member['learning_rate']:.2e}, batch {batch}, "
                  f"l2 {member['l2']:g} | {left} epoch(s) of {per_epoch:,} positions", flush=True)
            for _ in range(left):
                model.fit(train_dataset(train_files, batch, member["seed"] + member["epochs"], per_epoch),
                          epochs=1, steps_per_epoch=steps, verbose=2 if args.quiet else 1)
                member["epochs"] += 1
                member["trained_positions"] += steps * batch
                member["val_cp_error"] = validation_error(model, val)
                save_member(model, folder, state, member)
                err = member["val_cp_error"]
                print(f"  {member['id']} epoch {member['epochs']} saved" + (f" | validation error {err:.0f} centipawns" if err else ""))
    except KeyboardInterrupt:
        print("\nStopped. Finished epochs are saved; run the same command to continue.")
        return
    finally:
        if temporary:
            temporary.cleanup()
    print("Stage 1 done. Next: python engine/gym_tf.py refine")


def _compiled(model):
    compile_model(model, 1e-4, 1)
    return model


# ---------------------------------------------------------------- Java

def java(args, cls: str, *extra: str) -> None:
    compiled = ROOT / "bin" / "ai" / f"{cls}.class"
    source = ROOT / "src" / "ai" / f"{cls}.java"
    if not compiled.exists() or compiled.stat().st_mtime < source.stat().st_mtime:
        raise SystemExit("The Java engine isn't compiled (or is out of date). Run run.bat (or run.sh) once, close the game, "
                         "then run this again.")
    command = [args.java, "-cp", os.pathsep.join([str(ROOT / "bin"), str(ROOT / "src")]), f"ai.{cls}", *extra]
    subprocess.run(command, cwd=ROOT, check=True)


def tournament(args, folder: Path, state: dict) -> dict:
    out = folder / "rounds" / f"round-{state['round'] + 1:03d}.json"
    ids = sorted(m["id"] for m in state["members"])
    last = state.get("last_league")
    if last and last.get("members") == ids and Path(last["file"]).exists():
        print("Using the tournament already played for this population.")
        return json.loads(Path(last["file"]).read_text())
    games = args.games + args.games % 2
    print(f"Tournament: {len(ids)} members + {CLASSIC}, {games} games per pairing, {args.time} ms a move", flush=True)
    extra = ["--league", "--models", str(folder / "population"), "--games", str(games), "--time", str(args.time),
             "--threads", str(args.threads), "--json", str(out)]
    java(args, "strengthTest", *extra)
    state["last_league"] = {"members": ids, "file": str(out)}
    save_state(folder, state)
    return json.loads(out.read_text())


def selfplay(args, folder: Path, member: dict, seed: int) -> Path:
    out = folder / "selfplay" / f"{member['id']}-r{seed}.jsonl"
    if out.exists() and out.stat().st_size > 0:
        return out
    out.parent.mkdir(parents=True, exist_ok=True)
    partial = out.with_suffix(".part")
    partial.unlink(missing_ok=True)
    print(f"Self-play for {member['id']}: {args.selfplay_games} games at depth {args.selfplay_depth}", flush=True)
    java(args, "selfPlayData", "--model", str(member_dir(folder, member)), "--games", str(args.selfplay_games),
         "--depth", str(args.selfplay_depth), "--time", str(args.selfplay_time), "--threads", str(args.threads),
         "--out", str(partial), "--seed", str(seed))
    os.replace(partial, out)
    return out


def read_selfplay(path: Path):
    from lichess_data import parse_eval
    xs, cps = [], []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            try:
                item = parse_eval(line, 1, True)
            except (ValueError, KeyError, IndexError):
                item = None
            if item:
                xs.append(item[0]), cps.append(item[1])
    if not xs:
        return np.zeros((0, 65), np.uint8), np.zeros(0, np.int16)
    return np.stack(xs).astype(np.uint8), np.asarray(cps, np.int16)


def lichess_sample(files: list[Path], n: int, rng):
    if n <= 0 or not files:
        return np.zeros((0, 65), np.uint8), np.zeros(0, np.int16)
    xs, cps, have = [], [], 0
    for i in rng.permutation(len(files)):
        with np.load(files[i]) as d:
            x, cp = d["x"], d["cp"]
        pick = rng.choice(len(x), size=min(len(x), n - have), replace=False)
        xs.append(x[pick]), cps.append(cp[pick])
        have += len(pick)
        if have >= n:
            break
    return np.concatenate(xs), np.concatenate(cps)


# ---------------------------------------------------------------- stage 2: refine

def standings(league: dict, state: dict) -> list[dict]:
    points = {s["name"]: s["points"] for s in league["standings"]}
    ranked = sorted(state["members"], key=lambda m: -points.get(f"Gym {m['id']}", 0.0))
    for m in ranked:
        m["points"][str(state["round"] + 1)] = points.get(f"Gym {m['id']}", 0.0)
    return ranked


def classic_score(league: dict, name: str) -> float | None:
    for p in league["pairs"]:
        if p["a"] == name and p["b"] == CLASSIC:
            return p["score"]
        if p["b"] == name and p["a"] == CLASSIC:
            return 1 - p["score"]
    return None


def refine(args):
    import tensorflow as tf
    folder = args.dir
    state = load_state(folder)
    if not state.get("members"):
        raise SystemExit("No gym yet. Run: python engine/gym_tf.py start")
    pending = set(state.get("pending_children", []))
    unfinished = [m["id"] for m in state["members"]
                  if m["id"] not in pending and not (member_dir(folder, m) / "weights.npz").exists()]
    if unfinished:
        raise SystemExit(f"Stage 1 isn't finished for {', '.join(unfinished)}. Run: python engine/gym_tf.py start")
    data = Path(state["config"].get("data", DATA)) if args.data is None else args.data
    train_files = shards(data, "train")
    val = validation_dataset(shards(data, "val"), 4096, args.val_positions)
    rng = np.random.default_rng(args.seed + state["round"])
    try:
        if pending:  # a previous run stopped while fine-tuning children
            train_children(args, folder, state, train_files, val, rng)
        for _ in range(args.rounds):
            league = tournament(args, folder, state)
            ranked = standings(league, state)
            keep = max(1, math.ceil(len(ranked) * args.keep))
            survivors, losers = ranked[:keep], ranked[keep:]
            leader = survivors[0]
            vs_classic = classic_score(league, f"Gym {leader['id']}")
            round_no = state["round"] + 1
            print(f"\nRound {round_no} standings:")
            for m in ranked:
                tag = "keeps its place" if m in survivors else "removed"
                print(f"  Gym {m['id']:5s} {m['points'][str(round_no)]:5.1f} points  ({m['origin']}, {tag})")
            if vs_classic is not None:
                print(f"Leader Gym {leader['id']} scored {100 * vs_classic:.0f}% against {CLASSIC}")
            state["history"].append({"round": round_no, "leader": leader["id"], "leader_points": leader["points"][str(round_no)],
                                     "leader_vs_classic": vs_classic, "survivors": [m["id"] for m in survivors],
                                     "removed": [m["id"] for m in losers], "time": time.strftime("%Y-%m-%d %H:%M:%S")})
            write_history(folder, state)
            if not args.no_promote:
                promote_member(folder, state, leader)

            # breed: each removed slot becomes a child of a survivor (best survivors first)
            children = []
            for i, _ in enumerate(losers):
                parent = survivors[i % len(survivors)]
                lr = float(np.clip(parent.get("refine_lr", args.refine_lr) * 2 ** rng.uniform(-1, 1), 2e-5, 1e-3))
                children.append({"id": new_id(state), "size": parent["size"], "seed": int(rng.integers(1 << 30)),
                                 "born_round": round_no, "parent": parent["id"], "origin": f"child of {parent['id']}",
                                 "hidden": parent.get("hidden"), "l2": parent.get("l2", 0.0), "batch": parent.get("batch") or 1024,
                                 "learning_rate": lr, "refine_lr": lr, "epochs": parent.get("epochs", 0),
                                 "trained_positions": parent.get("trained_positions", 0), "points": {}})
            for m in losers:
                shutil.rmtree(member_dir(folder, m), ignore_errors=True)
            state["members"] = survivors + children
            state["round"] = round_no
            state["pending_children"] = [c["id"] for c in children]
            save_state(folder, state)
            train_children(args, folder, state, train_files, val, rng)
        # play the last children too, so the champion is decided by a tournament
        if state.get("members"):
            league = tournament(args, folder, state)
            ranked = standings(league, state)  # recorded as next round's points; that round reuses this tournament
            save_state(folder, state)
            if not args.no_promote:
                promote_member(folder, state, ranked[0])
            print(f"\nCurrent leader: Gym {ranked[0]['id']} ({ranked[0]['origin']})")
    except KeyboardInterrupt:
        print("\nStopped. Progress is saved; run the same command to continue.")


def train_children(args, folder: Path, state: dict, train_files, val, rng):
    import tensorflow as tf
    by_id = {m["id"]: m for m in state["members"]}
    data_for = {}
    for child_id in list(state.get("pending_children", [])):
        child = by_id[child_id]
        parent = by_id.get(child["parent"])
        parent_dir = member_dir(folder, parent) if parent else None
        if parent_dir is None or not (parent_dir / "model.keras").exists():
            print(f"Parent of {child_id} is missing; skipping it")
            state["members"].remove(child)
            state["pending_children"].remove(child_id)
            save_state(folder, state)
            continue
        if parent["id"] not in data_for:
            path = selfplay(args, folder, parent, seed=state["round"] * 1000 + int(parent["id"][1:]))
            data_for[parent["id"]] = read_selfplay(path)
        x_self, cp_self = data_for[parent["id"]]
        x_li, cp_li = lichess_sample(train_files, len(x_self) * args.mix, rng)
        x = np.concatenate([x_self, x_li])
        cp = np.concatenate([cp_self, cp_li])
        if len(x) == 0:
            print(f"No self-play positions for {child_id}; dropping it")
            state["members"].remove(child)
            state["pending_children"].remove(child_id)
            save_state(folder, state)
            continue
        batch = min(child["batch"], len(x))
        steps = max(1, len(x) // batch) * args.refine_epochs
        dataset = (tf.data.Dataset.from_tensor_slices((x, target(cp)[:, None])).shuffle(len(x), seed=child["seed"])
                   .batch(batch, drop_remainder=True).map(lambda a, b: (tf_expand(a), b)).repeat())
        model = tf.keras.models.load_model(parent_dir / "model.keras", compile=False)
        compile_model(model, child["learning_rate"], steps)
        print(f"{child_id}: child of {parent['id']}, lr {child['learning_rate']:.2e} | {len(x_self):,} self-play + "
              f"{len(x_li):,} Lichess positions, {args.refine_epochs} epoch(s)", flush=True)
        model.fit(dataset, epochs=1, steps_per_epoch=steps, verbose=2 if args.quiet else 1)
        child["epochs"] += args.refine_epochs
        child["trained_positions"] += steps * batch
        child["val_cp_error"] = validation_error(model, val)
        state["pending_children"].remove(child_id)
        save_member(model, folder, state, child)
    for f in (folder / "selfplay").glob("*.jsonl"):
        f.unlink()


def write_history(folder: Path, state: dict) -> None:
    with (folder / "history.csv").open("w", newline="") as handle:
        w = csv.writer(handle)
        w.writerow(["round", "time", "leader", "leader_points", "leader_vs_classic", "survivors", "removed"])
        for h in state["history"]:
            w.writerow([h["round"], h["time"], h["leader"], h["leader_points"], h["leader_vs_classic"],
                        " ".join(h["survivors"]), " ".join(h["removed"])])


# ---------------------------------------------------------------- promote / status

def promote_member(folder: Path, state: dict, member: dict) -> None:
    source = member_dir(folder, member)
    out = MODELS / "gym-champion"
    out.mkdir(parents=True, exist_ok=True)
    for name in ("weights.npz", "model.keras"):
        shutil.copy2(source / name, out / name)
    meta = json.loads((source / "model.json").read_text())
    meta.update(name="Neural gym champion", gym_member=member["id"], gym_round=state["round"])
    (out / "model.json").write_text(json.dumps(meta, indent=2))
    state["champion"] = member["id"]
    save_state(folder, state)
    print(f"Gym {member['id']} is now 'Neural gym champion' in the game ({out})")


def promote(args):
    state = load_state(args.dir)
    if not state.get("members"):
        raise SystemExit("No gym yet.")
    leader = next((h["leader"] for h in reversed(state["history"])), None)
    member = next((m for m in state["members"] if m["id"] == leader), None) or state["members"][0]
    promote_member(args.dir, state, member)


def status(args):
    state = load_state(args.dir)
    if not state.get("members"):
        print("No gym yet. Run: python engine/gym_tf.py start")
        return
    print(f"Gym in {args.dir}: round {state['round']}, champion {state.get('champion', 'none yet')}")
    print(f"  {'id':5s} {'size':11s} {'origin':22s} {'trained':>12s} {'val error':>9s}  points by round")
    for m in state["members"]:
        err = m.get("val_cp_error")
        ready = (member_dir(args.dir, m) / "weights.npz").exists()
        pts = " ".join(f"r{r}:{p:g}" for r, p in m["points"].items()) or ("-" if ready else "not trained yet")
        print(f"  {m['id']:5s} {m['size']:11s} {m['origin'][:22]:22s} {m.get('trained_positions', 0):>12,} "
              f"{(f'{err:.0f} cp' if err else '-'):>9s}  {pts}")
    for h in state["history"]:
        vs = h["leader_vs_classic"]
        print(f"  round {h['round']}: leader {h['leader']}" + (f", {100 * vs:.0f}% vs {CLASSIC}" if vs is not None else ""))


# ---------------------------------------------------------------- command line

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--dir", type=Path, default=GYM, help="gym folder (default engine/models/tf-gym)")
    sub = p.add_subparsers(dest="command", required=True)
    s = sub.add_parser("start", help="stage 1: train the population on Lichess positions")
    s.add_argument("--population", type=int, default=8)
    s.add_argument("--sizes", default="small,medium", help="comma-separated sizes, shared out across the population")
    s.add_argument("--positions", type=int, default=2_000_000, help="positions per epoch for each member (0 = all)")
    s.add_argument("--epochs", type=int, default=2)
    s.add_argument("--from-scratch", action="store_true", help="ignore the main models in engine/models/tf")
    s.add_argument("--data", type=Path, default=DATA)
    s.add_argument("--val-positions", type=int, default=100_000)
    s.add_argument("--seed", type=int, default=11)
    s.add_argument("--quiet", action="store_true")
    s.add_argument("--smoke", type=int, default=0, help=argparse.SUPPRESS)
    r = sub.add_parser("refine", help="stage 2: tournaments, selection and self-play fine-tuning")
    r.add_argument("--rounds", type=int, default=3)
    r.add_argument("--games", type=int, default=4, help="games per pairing (even)")
    r.add_argument("--time", type=int, default=250, help="ms per move in tournaments")
    r.add_argument("--threads", type=int, default=max(1, (os.cpu_count() or 2) // 2))
    r.add_argument("--keep", type=float, default=0.5, help="fraction of the population that survives a round")
    r.add_argument("--selfplay-games", type=int, default=100)
    r.add_argument("--selfplay-depth", type=int, default=7)
    r.add_argument("--selfplay-time", type=int, default=2000, help="ms cap per self-play move")
    r.add_argument("--mix", type=int, default=3, help="Lichess positions per self-play position in fine-tuning")
    r.add_argument("--refine-epochs", type=int, default=2)
    r.add_argument("--refine-lr", type=float, default=2e-4)
    r.add_argument("--data", type=Path, default=None, help="Lichess shards (default: what stage 1 used)")
    r.add_argument("--val-positions", type=int, default=100_000)
    r.add_argument("--java", default="java")
    r.add_argument("--no-promote", action="store_true", help="don't copy the leader into the game")
    r.add_argument("--seed", type=int, default=5)
    r.add_argument("--quiet", action="store_true")
    sub.add_parser("status", help="show the population and round history")
    sub.add_parser("promote", help="copy the current leader into the game as 'Neural gym champion'")
    args = p.parse_args(argv)
    if args.command == "start":
        if args.population < 2 or args.epochs < 1:
            p.error("population must be at least 2 and epochs at least 1")
        start(args)
    elif args.command == "refine":
        if not 0 < args.keep < 1 or args.rounds < 1 or args.games < 1 or args.mix < 0:
            p.error("keep must be between 0 and 1; rounds and games must be positive")
        refine(args)
    elif args.command == "promote":
        promote(args)
    else:
        status(args)


if __name__ == "__main__":
    sys.exit(main())
