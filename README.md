# ChessBot

A Java chess game with its own engine. Opponents are TensorFlow value networks trained on
Lichess positions scored by Stockfish (five sizes, from super-small to larger), a hand-written
"Classic engine", and the ten survivors of the original self-play gym. Every engine runs in a
fast alpha-beta search inside the Java app, with quiescence search, a transposition table and
pruning. Engines always play at full strength for about 2 seconds a move.

**Train the TensorFlow models:** see [docs/TRAINING_TF.md](docs/TRAINING_TF.md). In short:

```powershell
.\engine\venv\Scripts\python.exe -m pip install -r engine/requirements-tf.txt
.\engine\venv\Scripts\python.exe engine/lichess_data.py evals --positions 2000000
.\engine\venv\Scripts\python.exe engine/train_tf.py --size small
java -cp "bin;src" ai.strengthTest --league --games 10
```

Nothing trains unless you run these commands. Run `run.bat` once first so `bin` is compiled.

## The original self-play gym

From the project folder in PowerShell:

```powershell
python -m venv engine/venv
.\engine\venv\Scripts\python.exe -m pip install -r engine/requirements-gym.txt
.\engine\venv\Scripts\python.exe engine/train_gym.py --generations 100 --workers 4
```

If the environment is already installed, just run the final command. Training
resumes the last completed generation. The terminal estimates remaining runtime
after the first completed generation and updates the estimate each generation.

Use `--status` to inspect survivors or `--verify` for isolated checks. The short
speed benchmark is `engine/benchmark_gym.py`; it uses disposable models. See the
[training gym guide](docs/TRAINING_GYM.md) for all controls and checkpoint details.

With a JDK installed (the Windows launcher expects JDK 25), run `run.bat` to compile
and launch the game. Restart the game after training publishes a new generation.
Java reads the ten models listed in `engine/models/gym/champions.json`; dropping
unlisted model files into a folder does not register them.

## Implementation roadmap

**Status (2026-10-11):** the plan changed to supervised training on Lichess data; see
[TRAINING_TF.md](docs/TRAINING_TF.md). Done so far:
- Measurement: `ai.strengthTest` covers matches, a league, Stockfish at a set Elo, and rated puzzles.
- Learning data: Lichess Stockfish evaluations with a fixed validation split, plus self-play data scored by search.
- Faster evaluation: networks run in Java from sparse inputs (only the occupied squares are added up).
- Lookahead: a real alpha-beta search for play and for self-play data.
- Direct Java inference: no Python process is needed to play.
The original phase notes below are kept for reference.

**These phases are proposals, not implemented features.** Research and local
inspection were performed on October 10, 2026. The development machine has a
Ryzen 5 5500 (6 cores / 12 threads), about 48 GiB system RAM, and an NVIDIA RTX 3050
with 6 GiB VRAM reported by `nvidia-smi`.

Current training uses a 782-input, 64-hidden-unit NumPy value network, outcome
regression with SGD, one-ply self-play, and up to four parallel game processes.
Later generations train ten challengers while protecting ten previous survivors.
The default tournament gives each candidate four games. Java uses the Python
bridge's deeper alpha-beta search, so training and actual play use different search
budgets. Checkpoint format is currently version 1.

During inspection, the last 1,000 logged games included 337 self-play games and
663 tournament games. Their draw rates were approximately 68% and 73.5%; most
draws were repetitions. Draws alone do not prove poor play, but these figures and
the small evaluation sample justify measuring learning quality before scaling up.
Pool ratings are relative to the evolving opponents, not calibrated chess Elo.

Recommended order: phases 1 and 2, then 3 and 4. Consider phases 5 through 7 only
after their prerequisites are measured. More games per second and stronger chess
are separate goals; evaluate both.

### Rules for every implementation phase

- Preserve existing gym checkpoints and the rule that exactly ten models are active.
- Use temporary fixtures and short benchmarks during development. Leave sustained
  training to the user; do not train or overwrite the user's saved models.
- Keep the Java game able to load survivors. Version new architectures and provide
  an explicit migration or separate output directory when compatibility changes.
- Report measured results, hardware, settings and tradeoffs. Do not promise an Elo
  gain from lower loss, more generations or higher throughput alone.

### Phase 1: Measure speed and strength reliably

Add reproducible reports for self-play, fitting, tournament and checkpoint saving;
include positions/second, generation wall time, memory, game lengths, draw reasons,
and cold versus warmed worker timings. Compare 1, 4 and 6 workers on identical
games before selecting a default. Profile process serialization as well as chess
rules and neural evaluation.

Add a separate evaluation command using fixed openings with colours swapped,
frozen baseline checkpoints and a small tactical suite. Keep evaluation positions
out of training. Compare candidates at identical search budgets and include
uncertainty on match results. The earlier 16-game throughput benchmark is useful
for speed, but does not establish playing strength.

Completion check: repeatable speed reports and baseline strength results, with no
production training or changes to the active champions manifest.

Reusable prompt:

> Implement phase 1 of the ChessBot README roadmap: reproducible profiling and
> independent strength evaluation. Preserve existing survivors and checkpoint
> compatibility. Use only short isolated checks and leave sustained training to me.

### Phase 2: Improve learning data and survivor selection

Distinguish genuine draws from games stopped by a ply limit. Preserve real chess
draw rules. For truncated games, compare excluding their outcome targets with a
documented bootstrap method; do not automatically treat an unknown result as a
proven draw. Add game-level validation splits, sample provenance and replay metrics
to avoid evaluating on positions from games used for fitting.

Test more varied legal openings, controlled exploration schedules and tactical or
endgame starting positions. Balance data by sampling rather than inventing game
results. Compare changes to the original training regime in controlled experiments.

Make survivor selection less dependent on four games: add configurable larger,
balanced evaluations, historical frozen opponents and uncertainty reporting. Use
cheap screening followed by a stronger final evaluation if runtime allows. Keep
exactly ten active survivors, and publish only after evaluation finishes. A larger
evaluation budget is a quality/runtime tradeoff, not a speed optimization.

Leela's training notes discuss sampling ratios, learning rates and train/test
separation: [Neural Net Training](https://lczero.org/dev/wiki/neural-net-training/).

Completion check: correct draw/truncation handling, no validation leakage between
games, balanced match counts and ten published survivors. Establish that reported
improvement holds against frozen opponents, not just the current pool.

Reusable prompt:

> Implement phase 2 of the ChessBot README roadmap: better outcome labeling,
> replay validation and reliable top-ten selection. Preserve legal draws and
> existing checkpoints. Add configurable evaluation budgets, and use isolated
> verification without running sustained training or replacing my survivors.

### Phase 3: Make CPU evaluation and data movement cheaper

Prototype sparse or incremental first-layer evaluation. Board features are mostly
zero, and a move changes relatively few features. Update the pre-tanh accumulator
and material prior instead of multiplying every child board by the entire first
layer. Handle captures, castling, promotions, en passant, turn and clock features;
rebuild accumulators whenever weights change.

This is inspired by efficiently updatable evaluation, not a claim that the current
network becomes Stockfish's NNUE architecture. Stockfish describes the CPU-oriented
idea in [Introducing NNUE Evaluation](https://stockfishchess.org/blog/2020/introducing-nnue-evaluation/).

Consider Numba for numeric kernels after profiling. Python `chess.Board` objects
would need an array/bitboard interface before a complete chess loop could run in
Numba's native mode. It is not a decorator-only fix. See
[Numba performance tips](https://numba.readthedocs.io/en/stable/user/performance-tips.html).
If move generation remains dominant, evaluate an optional compiled backend, such
as [Disservin's C++ chess library](https://github.com/Disservin/chess-library), behind
the existing interface. Its published perft numbers are not a speedup prediction
for this trainer; Windows packaging and rules parity must be verified locally.

Measure caching model weights in workers per generation, sharing larger buffers,
preallocating feature batches and reducing replay copies. Optimize checkpoint
compression or retention only if measurements show meaningful cost. Keep atomic
publication and recovery behavior.

Completion check: prediction parity within a documented tolerance, rules parity,
and better end-to-end throughput. Floating-point ordering can change close move
ties, so compare both numerical outputs and playing results.

Reusable prompt:

> Implement phase 3 of the ChessBot README roadmap. Profile first, then optimize
> sparse or incremental evaluation and worker data transfer where justified.
> Keep the current checkpoint format and a reference evaluator. Verify special
> moves and prediction parity, and run only short disposable benchmarks.

### Phase 4: Give self-play useful lookahead

Add an optional, bounded search mode for training: start with shallow alpha-beta
and quiescence for tactical captures, with hard node/time limits. Reuse the learned
value network and efficient draw checks. Evaluate transposition caching and better
move ordering, with repetition history and draw counters handled correctly.

Measure this against one-ply self-play using equal wall-clock training budgets.
Lookahead makes individual games slower; the hoped-for benefit is more useful
training data and stronger survivors. Compare one-ply and search-based evaluation
so training improvements carry into the Java game.

Completion check: legal moves, correct mate/draw scores, restored boards on
cancellation, bounded runtime, and held-out tactical results. Do not infer a gain
from search depth alone.

Reusable prompt:

> Implement phase 4 of the ChessBot README roadmap: optional bounded alpha-beta
> and quiescence self-play using the learned evaluator. Keep the fast one-ply mode
> and Java compatibility. Test cancellation, mate and draw handling, and benchmark
> short games without starting a sustained training run.

### Phase 5: Optional PyTorch and GPU training

Introduce an optional PyTorch backend, preserving NumPy as a reference and CPU
fallback. First port the existing architecture and validate imported weights and
predictions; then compare optimizers, batch sizes and learning-rate schedules.
Persist optimizer state when exact training resumption requires it.

The RTX 3050 can be considered for CUDA training using the supported Windows
installation path: [PyTorch installation guide](https://pytorch.org/get-started/locally/).
Select compatible package/driver versions during implementation rather than
copying a fixed CUDA install command from this roadmap.

Our inference batches are currently small, so GPU launch and transfer overhead
could outweigh computation savings. That is a project-specific hypothesis to
benchmark. Measure CPU versus GPU including transfers, worker communication and
complete generation time. Consider a shared GPU inference service that groups
requests by model, rather than putting a CUDA copy in every worker. Bound queues
and VRAM use; retain responsive cancellation.

Use full precision first. Try mixed precision or compilation only after parity and
profiling, and keep them optional if they do not improve end-to-end performance.

Completion check: safe CPU fallback, old checkpoint import, resumable optimizer
state, measured GPU benefit where enabled, and memory use within the detected VRAM.

Reusable prompt:

> Implement phase 5 of the ChessBot README roadmap: an optional PyTorch/CUDA backend
> with NumPy parity, CPU fallback and resumable optimizer state. Benchmark CPU and
> GPU end to end on my RTX 3050 before enabling GPU defaults. Preserve old models
> and use only disposable smoke checks, not sustained training.

### Phase 6: Optional policy/value network and MCTS

This is a separate architecture experiment after the earlier evaluation and
infrastructure phases. Add a small policy/value model: predict legal move
probabilities as well as position value. Use bounded PUCT/MCTS self-play to create
policy targets from visit counts and value targets from correctly labeled outcomes.
Start with a modest simulation budget and batch requests when beneficial.

Retain the top-ten population requirement, balanced evaluation and reproducible
settings. Version move encoding, feature encoding and checkpoints. Keep the current
value-only trainer available; do not silently load incompatible networks into it.
Provide a compatible Java bridge for this architecture before registering its
survivors in the game.

Google DeepMind's [OpenSpiel AlphaZero documentation](https://github.com/google-deepmind/open_spiel/blob/master/docs/alpha_zero.md)
describes policy/value networks, PUCT, actors and learners. It is a reference for
the design, not a promise that this implementation or one desktop GPU will reach
AlphaZero-level chess strength.

Completion check: legal move masks, stable policy targets, correct value signs,
bounded simulations and improvement against frozen baselines per hour of training.

Reusable prompt:

> Implement phase 6 of the ChessBot README roadmap as an optional versioned
> policy/value and PUCT/MCTS experiment. Keep the existing trainer and top-ten
> survivors workflow. Include Java bridge support and bounded isolated tests;
> leave real training to me and preserve my current gym.

### Phase 7: Optional direct Java inference

Export compatible trained networks to ONNX and compare Java inference with the
Python reference. [ONNX Runtime's Java API](https://onnxruntime.ai/docs/get-started/with-java.html)
supports running ONNX models on the JVM, including Windows CPU builds.

Direct inference does not provide chess search by itself. Either keep the Python
search bridge or port the necessary search and board encoding into Java before
replacing it. Version export metadata and preserve the ten-model manifest workflow.
Measure startup, memory and move latency; retain the bridge as a fallback.

Completion check: cross-language feature/prediction parity, legal search behavior,
all ten survivors selectable, and a measured usability or performance benefit.

Reusable prompt:

> Implement phase 7 of the ChessBot README roadmap: optional ONNX export and Java
> inference. Preserve the Python bridge as a fallback, verify feature/prediction
> parity and keep all ten surviving models selectable. Do not remove working
> search until a compatible replacement is verified. Run isolated checks only.
