package ai;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import rules.Position;

/** The AI that suggests / evaluates. Used for hints and game review. */
public interface analysisProvider {
    boolean isAvailable();

    /** Up to maxLines suggestions for the side to move, best first. */
    CompletableFuture<List<Suggestion>> analyze(Position pos, int maxLines);
}
