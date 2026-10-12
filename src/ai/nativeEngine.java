package ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rules.Move;
import rules.Position;

/** An engine that runs inside the Java app: searchEngine + an evaluator (trained network or hand-written). */
public final class nativeEngine implements moveProvider, analysisProvider {
    public interface loader { neuralNet load() throws Exception; }

    private final String name, description;
    private final loader source;
    private final ExecutorService worker;
    private volatile searchEngine search;
    private volatile String problem;

    public nativeEngine(String name, String description, loader source) {
        this.name = name;
        this.description = description;
        this.source = source;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "engine-" + name);
            t.setDaemon(true);
            return t;
        });
        worker.submit(this::engine); // load weights in the background
    }

    private searchEngine engine() {
        if (search == null && problem == null) {
            try { search = new searchEngine(source.load()); }
            catch (Exception e) { problem = e.getMessage(); System.err.println("Could not load " + name + ": " + problem); }
        }
        return search;
    }

    /** A separate search with its own memory, so several games can run on different threads at once. */
    public searchEngine fresh() throws Exception { return new searchEngine(source.load()); }

    @Override public String name() { return name; }
    public String description() { return description; }
    @Override public boolean isAvailable() { return search != null; }

    /** Board with the game so far, so the search knows about repetitions. */
    public static fastBoard boardFor(Position pos, List<Move> history) {
        if (history != null && !history.isEmpty()) {
            fastBoard f = fastBoard.from(Position.start());
            boolean ok = true;
            for (Move m : history) if (!f.play(m)) { ok = false; break; }
            if (ok && f.hash() == fastBoard.from(pos).hash()) return f;
        }
        return fastBoard.from(pos);
    }

    @Override
    public CompletableFuture<Move> chooseMove(Position pos, List<Move> history, searchLimits limits) {
        return CompletableFuture.supplyAsync(() -> {
            searchEngine s = engine();
            if (s == null) throw new IllegalStateException(name + " could not be loaded: " + problem);
            searchEngine.result r = s.think(boardFor(pos, history), limits.maxTimeMs(), limits.maxDepth());
            if (r.move() == 0) throw new IllegalStateException("no legal move");
            return fastBoard.toMove(r.move());
        }, worker);
    }

    /** Synchronous search with full details (strength tests). */
    public searchEngine.result think(fastBoard board, long timeMs, int maxDepth) {
        searchEngine s = engine();
        if (s == null) throw new IllegalStateException(name + " could not be loaded: " + problem);
        return s.think(board, timeMs, maxDepth);
    }

    @Override
    public void cancel() {
        searchEngine s = search;
        if (s != null) s.stop();
    }

    @Override
    public CompletableFuture<List<Suggestion>> analyze(Position pos, int maxLines) {
        return CompletableFuture.supplyAsync(() -> {
            searchEngine s = engine();
            if (s == null) throw new IllegalStateException(name + " could not be loaded: " + problem);
            List<Suggestion> out = new ArrayList<>();
            for (searchEngine.result r : s.analyze(fastBoard.from(pos), maxLines, 1500)) {
                List<Move> line = new ArrayList<>();
                for (int m : r.pv()) line.add(fastBoard.toMove(m));
                Integer mate = null, cp = null;
                if (Math.abs(r.score()) >= searchEngine.MATE - 500) {
                    int plies = searchEngine.MATE - Math.abs(r.score());
                    mate = Math.max(1, (plies + 1) / 2) * (r.score() > 0 ? 1 : -1);
                } else cp = r.score();
                out.add(new Suggestion(fastBoard.toMove(r.move()), cp, mate, r.depth(), line, name));
            }
            return out;
        }, worker);
    }
}
