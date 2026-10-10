package ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import handlers.Json;
import rules.Move;

/**
 * One engine line. Scores are from the point of view of the side to move (positive = good for the mover),
 * like UCI engines. Exactly one of centipawns / mateIn is normally set (mateIn > 0: mover mates, < 0: mover is mated).
 */
public record Suggestion(Move move, Integer centipawns, Integer mateIn, int depth, List<Move> line, String comment) {

    public static final int MATE_SCORE = 100_000;

    /** Score as centipawns from the mover's view, with mates mapped to +/- (MATE_SCORE - distance). */
    public int moverScore() {
        if (mateIn != null) return mateIn > 0 ? MATE_SCORE - mateIn : -MATE_SCORE - mateIn;
        return centipawns == null ? 0 : centipawns;
    }

    /** Score from White's point of view, given who was to move. */
    public int whiteScore(int sideToMove) { return moverScore() * sideToMove; }

    /** "+0.35", "-1.20", "M3", "-M2" from the mover's view. */
    public String scoreText() {
        if (mateIn != null) return (mateIn < 0 ? "-M" : "M") + Math.abs(mateIn);
        if (centipawns == null) return "?";
        return String.format("%+.2f", centipawns / 100.0);
    }

    public Map<String, Object> toJson() {
        List<Object> l = new ArrayList<>();
        if (line != null) for (Move m : line) l.add(m.uci());
        return Json.map("move", move.uci(), "centipawns", centipawns == null ? null : (long) centipawns,
                "mateIn", mateIn == null ? null : (long) mateIn, "depth", (long) depth, "line", l, "comment", comment);
    }

    /** Tolerant: returns null when the move is missing or malformed. Accepts UCI or engine-form moves. */
    public static Suggestion fromJson(Map<String, Object> m) {
        if (m == null) return null;
        Move mv = Move.fromEngineForm(m.get("move"));
        if (mv == null) return null;
        Object cp = m.get("centipawns"), mate = m.get("mateIn");
        List<Move> line = new ArrayList<>();
        for (Object o : Json.list(m, "line")) {
            Move x = Move.fromEngineForm(o);
            if (x == null) break;
            line.add(x);
        }
        return new Suggestion(mv, cp instanceof Number n ? n.intValue() : null, mate instanceof Number n ? n.intValue() : null,
                (int) Json.num(m, "depth", 0), line, Json.str(m, "comment", null));
    }
}
