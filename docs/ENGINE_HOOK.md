# Engine hook

The Python training gym learns chess evaluators from self-play and retains its best ten models. Java discovers the saved survivor manifest and starts one Python inference process per checkpoint. See [TRAINING_GYM.md](TRAINING_GYM.md) for commands, training controls and checkpoint details.

## Plugging an engine in (one line)

Survivors are loaded in `src/ai/engineBootstrap.java` from `engine/models/gym/champions.json`. The equivalent registration for a single checkpoint is:

```java
engineRegistry.useEngine(new externalProcessProvider("model-000012", "engine/venv/Scripts/python.exe", "engine/bridge.py", "--checkpoint", "path/to/model-000012.npz"));
```

`useEngine` registers the engine to play both colours. If the engine also implements `analysisProvider`, it is used for hints and review as well (`externalProcessProvider` implements both). The command runs from the project root. `bridge.py` uses python-chess for full legal move generation and `ai_models.py` for alpha-beta search with the learned neural evaluator. Search interruption always restores the board, and the bridge can cancel requests while searching.

Install the minimal runtime with `engine/venv/Scripts/python.exe -m pip install -r engine/requirements-gym.txt` on Windows. Run `engine/train_gym.py` yourself to create models. `devRandomEngine.java` remains a Java test fixture for simulated engine failures.

## The Java side

| Interface | Methods |
|---|---|
| `moveProvider` | `name()`, `isAvailable()`, `CompletableFuture<Move> chooseMove(Position, List<Move> history, searchLimits)`, `cancel()` |
| `analysisProvider` | `isAvailable()`, `CompletableFuture<List<Suggestion>> analyze(Position, int maxLines)` |

- Calls are asynchronous and never block Swing. While `isAvailable()` is false (for example, the model is still loading), the game shows "Waiting for … to load".
- Each request has a time limit of `searchLimits.maxTimeMs` plus 5 s. If the engine times out, throws an exception or returns an illegal move, the user sees Retry / Take over this move. The game never crashes.
- `searchLimits.forSkill(n)` maps the 1–10 difficulty to `maxDepth = n + 1` and `maxTimeMs = 1000 · n`.
- `Suggestion` scores are **from the side to move's point of view** (like UCI). Exactly one of `centipawns` or `mateIn` is set. `mateIn` > 0 means the side to move delivers mate.

## Wire contract (JSON, one object per line, UTF-8, over the process's stdin/stdout)

**Board form** (matches `engine/chessEngine.py`): an 8×8 array where **row 0 = rank 8** (Black's back rank), row 7 = rank 1, and col 0 = file a. Pieces are coded 1 pawn, 2 knight, 3 bishop, 4 rook, 5 queen, 6 king. White pieces are positive and Black pieces negative, as in `Chess.simple_board()`. Players are coded 1 = White and −1 = Black. The bridge uses the supplied FEN, so it also receives castling and en-passant state.

**Move form:** `[[fromRow, fromCol], [toRow, toCol]]`, plus an optional third element for promotion (piece code 2–5). For example, e2e4 is `[[6,4],[4,4]]` and e7e8=Q is `[[1,4],[0,4],5]`. The helpers are `Move.toEngineForm()` / `fromEngineForm()` and `Position.toEngineForm()` / `fromEngineForm()`.

**Position object** sent to the engine: `{"board": [[...8 ints...] ×8], "player": 1, "castling": "KQkq", "enPassant": [2,3] | null, "halfmove": 0, "fullmove": 1, "fen": "..."}`

| Direction | Message |
|---|---|
| engine → app | `{"type":"ready","name":"ChessBot TF"}`: send once the model is loaded. Until then `isAvailable()` is false. |
| app → engine | `{"type":"hello","protocol":1}` |
| app → engine | `{"type":"move","id":7,"position":{…},"history":[[[6,4],[4,4]],…],"limits":{"skillLevel":5,"maxDepth":6,"maxTimeMs":5000}}` |
| engine → app | `{"type":"move","id":7,"move":[[1,4],[3,4]]}` or `{"type":"error","id":7,"message":"…"}` |
| app → engine | `{"type":"analyze","id":8,"position":{…},"maxLines":3}` |
| engine → app | `{"type":"analysis","id":8,"lines":[{"move":[[1,4],[3,4]],"centipawns":-20,"mateIn":null,"depth":8,"line":[[[1,4],[3,4]],[[7,6],[5,5]]],"comment":""}]}` |
| app → engine | `{"type":"cancel","id":7}` and `{"type":"quit"}` |

Replies are matched to requests by `id`, so the engine can answer them in any order. Lines on stdout that aren't JSON (for example the `print("Execution time…")` debug output) are ignored, and stderr is echoed to the console. `test/fixtures/fakeEngine.java` is a short working example of the engine side.

## Legacy Python prototype

`engine/chessEngine.py` remains as the original prototype. The playable models use `python-chess` in `bridge.py`, which reads the full FEN and therefore handles castling, en passant, checks, stalemate and promotions. The Java rules layer still validates every returned move.
