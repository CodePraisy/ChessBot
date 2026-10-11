# Training the TensorFlow models on Lichess data

**The idea in one line:** instead of bots teaching each other by trial and error, each model studies
millions of real positions that Stockfish has already scored, then the Java engine uses it to look
many moves ahead.

- **Old gym** (`train_gym.py`): ten models mutate and play each other; every position is labelled only
  with the game's final result. Slow to learn, and the Python search only looked 1–4 moves ahead.
- **New pipeline**: a TensorFlow network learns to predict Stockfish's score for 20 million+ Lichess
  positions. The game then runs a fast Java search (about 2 million positions a second with the
  hand-written evaluation) that calls the network at the end of each line.

The old gym still works and its ten survivors are still selectable; they now run through the same Java search.

## 1. Install (once)

From the project folder, in your existing venv:

```
engine\venv\Scripts\activate
pip install -r engine/requirements-tf.txt
```

TensorFlow on native Windows runs on the CPU only (Google dropped Windows GPU support after 2.10).
For your RTX 3050, see "GPU through WSL2" at the end. The CPU works fine; it's just slower.

## 2. Get the data

```
python engine/lichess_data.py evals                       # 20 million scored positions (default)
python engine/lichess_data.py evals --positions 2000000   # a quicker 2 million to start with
python engine/lichess_data.py puzzles                     # ~2000 rated puzzles for testing
```

This streams the Lichess evaluation database (free, CC0) straight from database.lichess.org and stops
once it has enough positions, so it never stores the whole multi-gigabyte file. Output goes to
`engine/data/lichess/` as compact shards (65 bytes a position, about 1.3 GB for 20 million). About 2% of
positions go to a separate validation set the model never trains on.

- Only *quiet* positions are kept by default (positions where the best move isn't a capture). The search
  handles captures itself, so the network learns calm judgement. `--include-tactical` keeps everything.
- `--keep 0.25` keeps one line in four, so the sample covers more of the file (and downloads 4× more).
- Ctrl+C is safe: shards already written are kept. Running it again adds more shards.
- Already downloaded `lichess_db_eval.jsonl.zst` yourself? Use `--source path\to\file.jsonl.zst`.

## 3. Train

```
python engine/train_tf.py --list                    # the five sizes
python engine/train_tf.py --size small              # train from scratch (4 passes over the data)
python engine/train_tf.py --size small --resume     # continue a model; Ctrl+C keeps the last finished epoch
python engine/train_tf.py --size large --epochs 2 --max-positions 5000000
```

| Size | Shape | Parameters | Notes |
|---|---|---|---|
| super-small | 772 → 32 → 1 | 25 k | trains in minutes; very fast search |
| small | 772 → 128 → 1 | 99 k | good first model |
| medium | 772 → 256 → 32 → 1 | 206 k | |
| large | 772 → 512 → 64 → 1 | 429 k | |
| larger | 772 → 1024 → 256 → 32 → 1 | 1.06 M | best judgement, slowest search |

Bigger models judge positions better but take longer per position, so the search sees fewer moves ahead
in the same 2 seconds. Which size plays best is something to measure (step 5), not assume.

Before a long run, time one short epoch: `--max-positions 1000000 --epochs 1`. Training prints a
validation error in centipawns after each epoch. It should fall from roughly 300+ toward 100 or less.
If it stops falling, more epochs won't help; more data or a bigger model might.

Each epoch saves `engine/models/tf/<size>/`:
- `model.keras`: the full TensorFlow model and what `--resume` continues from
- `weights.npz` + `model.json`: what the Java game loads
- `train_log.csv`: loss and validation error per epoch

## 4. Play

Start the game as usual (`run.bat`). Trained models appear in the AI menus as **Neural small**, etc.
The most-trained model is the default opponent, and **Classic engine** (hand-written, no training) is always
available. Engines always play at full strength with about 2 seconds a move; there's no difficulty slider any more.

## 5. Measure strength

```
java -cp "bin;src" ai.strengthTest --list
java -cp "bin;src" ai.strengthTest --league --games 10                          # all trained models + Classic
java -cp "bin;src" ai.strengthTest --engine "Neural small" --vs "Classic engine" --games 20
java -cp "bin;src" ai.strengthTest --engine "Neural small" --puzzles engine/data/puzzles.csv --limit 400
java -cp "bin;src" ai.strengthTest --engine "Neural small" --vs stockfish --stockfish C:\stockfish\stockfish.exe --elo 1320 --games 20
```

(Run `run.bat` once first so `bin` is compiled.)

- Stockfish is free from stockfishchess.org. With `--elo`, it plays at that rating; **1320 is the lowest
  setting**, about your level. Beating Stockfish at 1320 over 20+ games is a fair "can it beat me" test.
- Games use fixed openings, and each is played once with each colour. The result shows a 95% range: with
  20 games that range is wide, so use 40+ games before trusting small differences.
- Puzzle results are split by puzzle rating (400–799, 800–1199, ...).

## 6. Refine with self-play (after a model is trained)

The engine plays itself, and every position is scored by its own deeper search. Searching ahead is
better than one quick look, so the model learns from a stronger version of itself:

```
java -cp "bin;src" ai.selfPlayData --engine "Neural medium" --games 200 --depth 8
python engine/lichess_data.py evals --source engine/data/selfplay.jsonl --out engine/data/selfplay --min-depth 1
python engine/train_tf.py --size medium --resume --data engine/data/selfplay --lr 0.0002
```

Then compare the refined model against a copy of the old one with `strengthTest`. Keep it only if it scores better.

## GPU through WSL2 (optional)

1. Install a current NVIDIA Windows driver. Then run `wsl --install -d Ubuntu` in PowerShell and restart.
2. In Ubuntu: `sudo apt install python3-venv`, then
   `python3 -m venv ~/tfgpu && source ~/tfgpu/bin/activate && pip install "tensorflow[and-cuda]" chess numpy zstandard`
3. Check: `python -c "import tensorflow as tf; print(tf.config.list_physical_devices('GPU'))"` should list the GPU.
4. Train from your Windows folder:
   `cd "/mnt/c/Users/prais/Desktop/ChessBot Claude" && python engine/train_tf.py --size large --batch 4096`

The exported files work the same on Windows. Data reading from `/mnt/c` is slower than from the
Linux home folder; copy `engine/data` into WSL if the GPU sits idle.

## What was tested and what wasn't

Tested here, with short checks only and no real training:
- Every size builds, trains on tiny synthetic data, exports, and gives the same outputs in NumPy.
- Java evaluates exported models (and gym checkpoints) identically to Python.
- The search's move generator matches the standard perft counts, and it finds mates and free pieces.
- The whole data → model → game path works.

Not tested: real Lichess downloads (blocked from my environment), real training runs, GPU training, and
games against Stockfish. How strong the trained models play is for you to measure in step 5.
