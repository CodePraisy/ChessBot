# Python training gym

Run from the project folder in PowerShell:

```powershell
.\engine\venv\Scripts\python.exe engine\train_gym.py --generations 100
```

For a fresh checkout, create an environment and install the small runtime first:

```powershell
python -m venv engine/venv
.\engine\venv\Scripts\python.exe -m pip install -r engine/requirements-gym.txt
```

On macOS/Linux, use `engine/venv/bin/python` instead of the Windows interpreter path.
The gym needs NumPy and python-chess; it runs on a CPU without TensorFlow or a GPU.

## What happens each generation

1. Start with 20 independently initialized neural networks on the first generation.
   Later generations retain the previous ten survivors and add ten mutated copies.
2. Play 20 exploratory self-play games. Record encoded positions, moves and results.
3. Train the challengers on game outcomes and the rolling replay buffer. Surviving
   networks stay unchanged during this phase, so challengers must beat existing models.
4. Run a tournament with no exploration or weight updates. Random pairings play twice
   with colours swapped and the same opening. Each model plays four evaluation games
   with the default two rounds. Rank by tournament score, then internal pool rating.
5. Save exactly the best ten active models and breed the next set of challengers.

The neural evaluator has 782 inputs, a 64-unit tanh hidden layer and one outcome
output from White's perspective. It learns a correction to a starting material prior
using gradient descent. The terminal gym uses fast one-ply neural evaluation; Java's
Python bridge combines the learned evaluator with deeper alpha-beta search.

Wins train toward +1 for White, losses toward -1 and draws toward 0. Games ending at
the configurable 160-ply limit count as draws. Short runs can produce mostly draws
and noisy rankings. Self-play and a lower training loss alone do not establish chess
strength; the pool rating is relative to these opponents, not a calibrated chess Elo.
Increase tournament rounds for more reliable survivor selection.

## Controls

```powershell
# Longer matches and more evaluation games per model
.\engine\venv\Scripts\python.exe engine\train_gym.py --generations 100 --max-plies 300 --tournament-rounds 5

# Show survivors without starting training
.\engine\venv\Scripts\python.exe engine\train_gym.py --status

# Isolated verification only; does not start a self-play training run
.\engine\venv\Scripts\python.exe engine\train_gym.py --verify

# List all population, learning, replay and output options
.\engine\venv\Scripts\python.exe engine\train_gym.py --help
```

`--generations` counts additional generations on each launch. Ctrl+C stops the gym;
the next invocation resumes the last completed generation, including the replay buffer
and random generator state. An unfinished generation is repeated. No production
checkpoints are created by `--verify` or `--status`: tests use temporary files, scripted
matches and one tiny synthetic gradient correctness check.

Only one trainer can write to a gym at once. A hard process kill can leave a
`training.lock` file; confirm the trainer is no longer running before removing that file.

## Saved models and Java

Everything is stored under `engine/models/gym/` and ignored by Git:

- `champions.json`: the active top ten, rank, pool rating and checkpoint paths.
- `generations/generation-NNNNNN/`: immutable survivor checkpoints, replay data and a
  recovery manifest for each completed generation. Earlier generations are kept as
  history; only the ten in the current manifest remain active.
- `games.jsonl`: self-play and tournament moves/results.

Restart the Java game after a completed generation. Player vs AI and AI vs AI list the
ten survivor IDs, such as `model-000012`. The first survivor is the default opponent and
hint/review evaluator. Before the first training generation finishes, Java shows a
message explaining how to create models. No named personalities are registered.

Java never trains. Each process loads an immutable `.npz` checkpoint once. A running
game keeps its loaded model even while the gym publishes newer survivors.
If a saved game's model has been eliminated, Player vs AI falls back to the current
top survivor. AI vs AI requires both saved model IDs to remain available.

For a different interpreter or gym directory, supply JVM properties before launching:
`-Dchessbot.python=/path/to/python` and `-Dchessbot.gym=/path/to/gym`.
