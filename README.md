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
| **Neural small-0000001...** | TensorFlow networks you train yourself (appear after training) |
| **Neural gym-small-0000002...** | Each size's gym winner (appears after you run the gym) |
| **Classic engine** | Hand-written evaluation, no training needed |
| **Gym survivors** | The ten models from the original self-play gym |
| **Stockfish 1320 / 1600 / 2000 / 2500 / full** | The real Stockfish, to compare against. Downloaded automatically the first time the game starts (needs internet once) |

All of them play at full strength. Thinking time per move (default 2 s) is in **Settings > Engines**;
shorter is faster but plays weaker.

**Ratings:** after a game, open **Review** and press **Analyze game**. Each side gets an accuracy and a
game rating (an Elo-like estimate from how much each move lost against the best move). Your overall
rating and each engine's is the average of its last 20 analysed games, shown next to engine names in the
menus. The estimate is rough: it is most reliable with Stockfish installed, since full-strength Stockfish
then does the analysis.

## Train your own

```
pip install -r engine/requirements-tf.txt
python engine/lichess_data.py evals --positions 2000000     # download scored positions
python engine/train_tf.py --size small                      # train a model
python engine/gym_tf.py start && python engine/gym_tf.py refine   # optional: variants fight each other
python engine/train_cycle.py --epochs 8 --generations 5     # or everything: each size, then a gym per size where only the winner survives
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
