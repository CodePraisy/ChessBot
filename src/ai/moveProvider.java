package ai;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import rules.Move;
import rules.Position;

/** The AI that plays. Calls are asynchronous; the UI never blocks on them. */
public interface moveProvider {
    String name();

    /** False while the engine is starting or its model is still loading. */
    boolean isAvailable();

    /** Completes with the chosen move (or exceptionally). The UI validates the move with the rules layer. */
    CompletableFuture<Move> chooseMove(Position pos, List<Move> history, searchLimits limits);

    /** Abandon the current request, if any. */
    void cancel();
}
