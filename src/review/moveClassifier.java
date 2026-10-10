package review;

import java.awt.Color;

import ai.Suggestion;

/** Labels a move from the evaluation swing it caused. Pure function; thresholds live here only. */
public final class moveClassifier {
    private moveClassifier() {}

    public enum Quality { GOOD, INACCURACY, MISTAKE, BLUNDER }

    /** Centipawn loss (from the mover's point of view) at which each label starts. */
    public static final int INACCURACY_CP = 50, MISTAKE_CP = 100, BLUNDER_CP = 300;
    /** Mate scores are clipped to this many centipawns so one mate line does not dominate. */
    public static final int MATE_CLIP_CP = 1000;

    /**
     * @param bestBefore evaluation of the best move in the position before the move, mover's point of view (centipawns or Suggestion mate score)
     * @param after      evaluation of the position after the move, mover's point of view
     */
    public static Quality classify(int bestBefore, int after) {
        int loss = clip(bestBefore) - clip(after);
        if (loss >= BLUNDER_CP) return Quality.BLUNDER;
        if (loss >= MISTAKE_CP) return Quality.MISTAKE;
        if (loss >= INACCURACY_CP) return Quality.INACCURACY;
        return Quality.GOOD;
    }

    static int clip(int score) {
        if (score >= Suggestion.MATE_SCORE / 2) return MATE_CLIP_CP;
        if (score <= -Suggestion.MATE_SCORE / 2) return -MATE_CLIP_CP;
        return Math.max(-MATE_CLIP_CP, Math.min(MATE_CLIP_CP, score));
    }

    public static String symbol(Quality q) {
        return switch (q) { case INACCURACY -> "?!"; case MISTAKE -> "?"; case BLUNDER -> "??"; default -> ""; };
    }

    public static Color color(Quality q) {
        return switch (q) {
            case INACCURACY -> new Color(176, 132, 0);
            case MISTAKE -> new Color(214, 100, 20);
            case BLUNDER -> new Color(190, 20, 20);
            default -> new Color(56, 53, 44);
        };
    }
}
