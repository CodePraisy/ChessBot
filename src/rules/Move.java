package rules;

import java.util.ArrayList;
import java.util.List;

/**
 * A move between two squares. Squares are 0..63 with index = row * 8 + col,
 * row 0 = rank 8 (black's back rank), col 0 = file a (same layout as engine/chessEngine.py).
 * promotion is 0 or a piece type 2..5 (knight, bishop, rook, queen).
 */
public record Move(int from, int to, int promotion) {

    public Move(int from, int to) { this(from, to, 0); }

    public int fromRow() { return from / 8; }
    public int fromCol() { return from % 8; }
    public int toRow() { return to / 8; }
    public int toCol() { return to % 8; }

    /** UCI-style text, e.g. "e2e4", "e7e8q". */
    public String uci() {
        String s = Position.squareName(from) + Position.squareName(to);
        if (promotion != 0) s += Character.toLowerCase(Position.pieceLetter(promotion));
        return s;
    }

    @Override public String toString() { return uci(); }

    /** Parses "e2e4" / "e7e8q". Returns null when malformed. */
    public static Move fromUci(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() < 4 || s.length() > 5) return null;
        int f = Position.squareIndex(s.substring(0, 2));
        int t = Position.squareIndex(s.substring(2, 4));
        if (f < 0 || t < 0) return null;
        int promo = 0;
        if (s.length() == 5) {
            promo = "nbrq".indexOf(Character.toLowerCase(s.charAt(4))) + 2;
            if (promo < 2) return null;
        }
        return new Move(f, t, promo);
    }

    /** Engine wire form: [[fromRow, fromCol], [toRow, toCol]] plus an optional promotion piece code (2..5). */
    public List<Object> toEngineForm() {
        List<Object> l = new ArrayList<>();
        l.add(List.of((long) fromRow(), (long) fromCol()));
        l.add(List.of((long) toRow(), (long) toCol()));
        if (promotion != 0) l.add((long) promotion);
        return l;
    }

    /** Inverse of {@link #toEngineForm()}; also accepts a UCI string. Returns null when malformed. */
    public static Move fromEngineForm(Object o) {
        try {
            if (o instanceof String s) return fromUci(s);
            List<?> l = (List<?>) o;
            List<?> a = (List<?>) l.get(0), b = (List<?>) l.get(1);
            int fr = ((Number) a.get(0)).intValue(), fc = ((Number) a.get(1)).intValue();
            int tr = ((Number) b.get(0)).intValue(), tc = ((Number) b.get(1)).intValue();
            if (fr < 0 || fr > 7 || fc < 0 || fc > 7 || tr < 0 || tr > 7 || tc < 0 || tc > 7) return null;
            int promo = l.size() > 2 && l.get(2) != null ? Math.abs(((Number) l.get(2)).intValue()) : 0;
            if (promo != 0 && (promo < 2 || promo > 5)) return null;
            return new Move(fr * 8 + fc, tr * 8 + tc, promo);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
