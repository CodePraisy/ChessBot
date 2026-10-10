package ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import rules.Move;
import rules.Position;

/**
 * =====================  DEVELOPMENT PLACEHOLDER — NOT AN ENGINE  =====================
 * Plays random legal moves and returns FAKE suggestions so the AI hook can be tested end to end.
 * Only registered with the --dev-engine launch flag (see engineBootstrap). Safe to delete.
 * "Flaky" mode (--dev-engine=flaky) sometimes returns an illegal move, throws, or hangs, to exercise error handling.
 */
public final class devRandomEngine implements moveProvider, analysisProvider {
    private final boolean flaky;
    private final long readyAt = System.currentTimeMillis() + 1500; // pretend the model takes 1.5 s to load
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Random rnd = new Random();

    public devRandomEngine(boolean flaky) { this.flaky = flaky; }

    @Override public String name() { return flaky ? "Dev Random Engine (flaky)" : "Dev Random Engine (placeholder)"; }

    @Override public boolean isAvailable() { return System.currentTimeMillis() >= readyAt; }

    @Override
    public CompletableFuture<Move> chooseMove(Position pos, List<Move> history, searchLimits limits) {
        cancelled.set(false);
        return CompletableFuture.supplyAsync(() -> {
            sleep(150 + rnd.nextInt(350));
            if (cancelled.get()) throw new CompletionException(new InterruptedException("cancelled"));
            if (flaky) {
                int roll = rnd.nextInt(10);
                if (roll == 0) return new Move(0, 63); // almost always illegal
                if (roll == 1) throw new IllegalStateException("devRandomEngine (flaky) simulated crash");
                if (roll == 2) { sleep(limits.maxTimeMs() + 10_000); }
            }
            List<Move> legal = pos.legalMoves();
            return legal.isEmpty() ? null : legal.get(rnd.nextInt(legal.size()));
        });
    }

    @Override public void cancel() { cancelled.set(true); }

    @Override
    public CompletableFuture<List<Suggestion>> analyze(Position pos, int maxLines) {
        return CompletableFuture.supplyAsync(() -> {
            sleep(60);
            List<Move> legal = new ArrayList<>(pos.legalMoves());
            Random r = new Random(pos.toFen().hashCode()); // same fake numbers for the same position
            Collections.shuffle(legal, r);
            List<Suggestion> out = new ArrayList<>();
            int base = r.nextInt(301) - 150;
            for (int i = 0; i < Math.min(maxLines, legal.size()); i++) {
                Move m = legal.get(i);
                List<Move> line = new ArrayList<>(List.of(m));
                List<Move> replies = pos.play(m).legalMoves();
                if (!replies.isEmpty()) line.add(replies.get(r.nextInt(replies.size())));
                out.add(new Suggestion(m, base - i * (20 + r.nextInt(40)), null, 1, line, "FAKE suggestion (devRandomEngine)"));
            }
            return out;
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
