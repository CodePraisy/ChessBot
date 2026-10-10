# Engine hook

The UI is finished without an engine. An engine plugs in through two interfaces in `src/ai/`, and the rules layer (`src/rules/`) checks every move it returns.

## Plugging an engine in (one line)

Open `src/ai/engineBootstrap.java` and uncomment the marked line:

```java
engineRegistry.useEngine(new externalProcessProvider("ChessBot TF", "engine/venv/Scripts/python.exe", "engine/bridge.py"));
```

`useEngine` registers the engine to play both colours. If the engine also implements `analysisProvider`, it is used for hints and review as well (`externalProcessProvider` implements both). The command runs from the project root. `engine/bridge.py` doesn't exist yet: it's the small script you'll write that speaks the contract below.

Until then, launch with `--dev-engine` (random legal moves and fake suggestions) or `--dev-engine=flaky` (sometimes returns an illegal move, crashes or times out). Delete `devRandomEngine.java` and its line in `engineBootstrap` when you no longer need them.

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

**Board form** (matches `engine/chessEngine.py`): an 8×8 array where **row 0 = rank 8** (Black's back rank), row 7 = rank 1, and col 0 = file a. Pieces are coded 1 pawn, 2 knight, 3 bishop, 4 rook, 5 queen, 6 king. White pieces are positive and Black pieces negative, as in `Chess.simple_board()`. Players are coded 1 = White and −1 = Black.

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

## Known gap in the current Python engine

`engine/chessEngine.py` only reports success or failure for each move. It has no castling, en-passant or stalemate state, and its check test only looks at the piece that just moved, so it misses discovered checks. For now the **Java rules layer is the authority**: the app validates every engine move against `Position.isLegal` and sends the full position, including castling rights, the en-passant square and the FEN, so the Python side doesn't need to track game state itself.
