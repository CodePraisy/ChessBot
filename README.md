# Praise's Chess

A Java chess game you can play against a computer, a friend, or watch AI play AI. The opponents
are neural networks trained on millions of Stockfish-scored Lichess positions. They think ahead
with a fast search that runs inside the game, so no Python is needed to play.

## Play

Needs JDK 25. From the project folder:

```
run.bat        # Windows
./run.sh       # macOS / Linux
```

This compiles the game and starts it. Press **F11** for fullscreen. Multiplayer is described in
[docs/MULTIPLAYER.md](docs/MULTIPLAYER.md).

## Opponents

| Opponent | What it is |
|---|---|
| **Neural small / medium / large...** | TensorFlow networks you train yourself (appear after training) |
| **Neural gym champion** | The winner of the model gym (appears after you run it) |
| **Classic engine** | Hand-written evaluation, no training needed |
| **Gym survivors** | The ten models from the original self-play gym |

All of them play at full strength, about 2 seconds a move.

## Train your own

```
pip install -r engine/requirements-tf.txt
python engine/lichess_data.py evals --positions 2000000     # download scored positions
python engine/train_tf.py --size small                      # train a model
python engine/gym_tf.py start && python engine/gym_tf.py refine   # optional: variants fight each other
python engine/train_cycle.py --epochs 8 --generations 5     # or everything: all sizes, then a gym per size
java -cp "bin;src" ai.strengthTest --league --games 10      # measure who is strongest
```

Nothing trains unless you run these. The full guide, with model sizes, GPU setup (WSL2) and
Stockfish tests, is in [docs/TRAINING_TF.md](docs/TRAINING_TF.md).

## How it fits together

- **Python + TensorFlow** trains a model and saves it as a file of numbers.
- **Java** loads that file, searches many moves ahead, and uses the model to judge the positions.

The older self-play gym is described in [docs/TRAINING_GYM.md](docs/TRAINING_GYM.md).

## Project layout

```
src/        the game and the Java engine (src/ai)
engine/     Python training code and saved models
docs/       guides
test/       tests
```
