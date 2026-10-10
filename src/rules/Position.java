package rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable chess position. Board layout matches engine/chessEngine.py: index = row * 8 + col,
 * row 0 = rank 8, col 0 = file a. Piece codes 1 pawn .. 6 king, white positive, black negative.
 */
public final class Position {
    public static final int WHITE = 1, BLACK = -1;
    public static final int PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    static final int WK = 1, WQ = 2, BK = 4, BQ = 8;

    private final int[] b;
    private final int side, castling, ep, halfmove, fullmove;

    private Position(int[] b, int side, int castling, int ep, int halfmove, int fullmove) {
        this.b = b; this.side = side; this.castling = castling; this.ep = ep; this.halfmove = halfmove; this.fullmove = fullmove;
    }

    public static Position start() { return fromFen(START_FEN); }

    // ---------- accessors ----------

    public int pieceAt(int sq) { return b[sq]; }
    public int sideToMove() { return side; }
    public int epSquare() { return ep; }
    public int halfmoveClock() { return halfmove; }
    public int fullmoveNumber() { return fullmove; }
    public int[] boardCopy() { return b.clone(); }

    public static String squareName(int sq) { return "" + (char) ('a' + sq % 8) + (8 - sq / 8); }

    public static int squareIndex(String name) {
        if (name == null || name.length() != 2) return -1;
        int c = name.charAt(0) - 'a', r = 8 - (name.charAt(1) - '0');
        return c < 0 || c > 7 || r < 0 || r > 7 ? -1 : r * 8 + c;
    }

    public static char pieceLetter(int type) { return " PNBRQK".charAt(Math.abs(type)); }

    // ---------- FEN ----------

    public static Position fromFen(String fen) {
        String[] f = fen.trim().split("\\s+");
        if (f.length < 2) throw new IllegalArgumentException("FEN needs at least board and side: " + fen);
        int[] b = new int[64];
        String[] rows = f[0].split("/");
        if (rows.length != 8) throw new IllegalArgumentException("FEN board must have 8 ranks");
        for (int r = 0; r < 8; r++) {
            int c = 0;
            for (char ch : rows[r].toCharArray()) {
                if (Character.isDigit(ch)) { c += ch - '0'; continue; }
                int t = "pnbrqk".indexOf(Character.toLowerCase(ch)) + 1;
                if (t == 0 || c > 7) throw new IllegalArgumentException("Bad FEN piece '" + ch + "'");
                b[r * 8 + c++] = Character.isUpperCase(ch) ? t : -t;
            }
            if (c != 8) throw new IllegalArgumentException("FEN rank " + (8 - r) + " does not have 8 files");
        }
        int side = f[1].equals("b") ? BLACK : WHITE;
        int cast = 0;
        if (f.length > 2) {
            if (f[2].contains("K")) cast |= WK;
            if (f[2].contains("Q")) cast |= WQ;
            if (f[2].contains("k")) cast |= BK;
            if (f[2].contains("q")) cast |= BQ;
        }
        int ep = f.length > 3 && !f[3].equals("-") ? squareIndex(f[3]) : -1;
        int hm = f.length > 4 ? Integer.parseInt(f[4]) : 0;
        int fm = f.length > 5 ? Integer.parseInt(f[5]) : 1;
        int kings = 0;
        for (int p : b) if (Math.abs(p) == KING) kings += p > 0 ? 1 : 10;
        if (kings != 11) throw new IllegalArgumentException("FEN must have exactly one king per side");
        // Drop castling rights that the board cannot support.
        if (b[60] != KING) cast &= ~(WK | WQ);
        if (b[63] != ROOK) cast &= ~WK;
        if (b[56] != ROOK) cast &= ~WQ;
        if (b[4] != -KING) cast &= ~(BK | BQ);
        if (b[7] != -ROOK) cast &= ~BK;
        if (b[0] != -ROOK) cast &= ~BQ;
        return new Position(b, side, cast, ep, hm, Math.max(1, fm));
    }

    public String toFen() { return boardFen() + " " + (side == WHITE ? "w" : "b") + " " + castlingFen() + " " + (ep < 0 ? "-" : squareName(ep)) + " " + halfmove + " " + fullmove; }

    private String boardFen() {
        StringBuilder s = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int empty = 0;
            for (int c = 0; c < 8; c++) {
                int p = b[r * 8 + c];
                if (p == 0) { empty++; continue; }
                if (empty > 0) { s.append(empty); empty = 0; }
                char l = pieceLetter(p);
                s.append(p > 0 ? l : Character.toLowerCase(l));
            }
            if (empty > 0) s.append(empty);
            if (r < 7) s.append('/');
        }
        return s.toString();
    }

    public String castlingFen() {
        String s = ((castling & WK) != 0 ? "K" : "") + ((castling & WQ) != 0 ? "Q" : "") + ((castling & BK) != 0 ? "k" : "") + ((castling & BQ) != 0 ? "q" : "");
        return s.isEmpty() ? "-" : s;
    }

    /** Key for repetition detection: placement, side, castling, and en passant only when a capture is actually possible. */
    public String repetitionKey() {
        boolean epMatters = false;
        if (ep >= 0) for (Move m : legalMoves()) if (m.to() == ep && Math.abs(b[m.from()]) == PAWN) { epMatters = true; break; }
        return boardFen() + " " + side + " " + castling + " " + (epMatters ? ep : -1);
    }

    // ---------- engine form (see docs/ENGINE_HOOK.md) ----------

    public Map<String, Object> toEngineForm() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> rows = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            List<Object> row = new ArrayList<>();
            for (int c = 0; c < 8; c++) row.add((long) b[r * 8 + c]);
            rows.add(row);
        }
        m.put("board", rows);
        m.put("player", (long) side);
        m.put("castling", castlingFen());
        m.put("enPassant", ep < 0 ? null : List.of((long) (ep / 8), (long) (ep % 8)));
        m.put("halfmove", (long) halfmove);
        m.put("fullmove", (long) fullmove);
        m.put("fen", toFen());
        return m;
    }

    /** Uses "fen" when present, otherwise "board" + "player" (no castling / en passant information). */
    public static Position fromEngineForm(Map<String, Object> m) {
        Object fen = m.get("fen");
        if (fen instanceof String s) return fromFen(s);
        int[] b = new int[64];
        List<?> rows = (List<?>) m.get("board");
        for (int r = 0; r < 8; r++) {
            List<?> row = (List<?>) rows.get(r);
            for (int c = 0; c < 8; c++) b[r * 8 + c] = ((Number) row.get(c)).intValue();
        }
        int side = ((Number) m.getOrDefault("player", 1L)).intValue() < 0 ? BLACK : WHITE;
        return new Position(b, side, 0, -1, 0, 1);
    }

    // ---------- attacks ----------

    private static final int[][] KNIGHT_D = {{-2, -1}, {-2, 1}, {-1, -2}, {-1, 2}, {1, -2}, {1, 2}, {2, -1}, {2, 1}};
    private static final int[][] KING_D = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};
    private static final int[][] DIAG = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
    private static final int[][] ORTHO = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    /** Is square sq attacked by any piece of color by? */
    public boolean isAttacked(int sq, int by) { return isAttacked(b, sq, by); }

    private static boolean isAttacked(int[] b, int sq, int by) {
        int r = sq / 8, c = sq % 8;
        // Pawns: a white pawn attacks upward (row - 1), so it sits one row below the target.
        int pr = r + (by == WHITE ? 1 : -1);
        if (pr >= 0 && pr < 8) {
            if (c > 0 && b[pr * 8 + c - 1] == by * PAWN) return true;
            if (c < 7 && b[pr * 8 + c + 1] == by * PAWN) return true;
        }
        for (int[] d : KNIGHT_D) {
            int rr = r + d[0], cc = c + d[1];
            if (rr >= 0 && rr < 8 && cc >= 0 && cc < 8 && b[rr * 8 + cc] == by * KNIGHT) return true;
        }
        for (int[] d : KING_D) {
            int rr = r + d[0], cc = c + d[1];
            if (rr >= 0 && rr < 8 && cc >= 0 && cc < 8 && b[rr * 8 + cc] == by * KING) return true;
        }
        if (slider(b, r, c, DIAG, by * BISHOP, by * QUEEN)) return true;
        return slider(b, r, c, ORTHO, by * ROOK, by * QUEEN);
    }

    private static boolean slider(int[] b, int r, int c, int[][] dirs, int p1, int p2) {
        for (int[] d : dirs) {
            int rr = r + d[0], cc = c + d[1];
            while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
                int p = b[rr * 8 + cc];
                if (p != 0) { if (p == p1 || p == p2) return true; break; }
                rr += d[0]; cc += d[1];
            }
        }
        return false;
    }

    public int kingSquare(int color) {
        for (int i = 0; i < 64; i++) if (b[i] == color * KING) return i;
        return -1;
    }

    public boolean inCheck() { return isAttacked(kingSquare(side), -side); }

    // ---------- move generation ----------

    private List<Move> legalCache;

    public List<Move> legalMoves() {
        if (legalCache == null) {
            List<Move> out = new ArrayList<>();
            for (Move m : pseudoMoves()) {
                Position n = play(m);
                if (!isAttacked(n.b, n.kingSquare(side), -side)) out.add(m);
            }
            legalCache = List.copyOf(out);
        }
        return legalCache;
    }

    public List<Move> legalMovesFrom(int sq) {
        List<Move> l = new ArrayList<>();
        for (Move m : legalMoves()) if (m.from() == sq) l.add(m);
        return l;
    }

    public boolean isLegal(Move m) { return m != null && legalMoves().contains(m); }

    private List<Move> pseudoMoves() {
        List<Move> out = new ArrayList<>();
        for (int sq = 0; sq < 64; sq++) {
            int p = b[sq];
            if (p == 0 || Integer.signum(p) != side) continue;
            int r = sq / 8, c = sq % 8;
            switch (Math.abs(p)) {
                case PAWN -> pawnMoves(out, sq, r, c);
                case KNIGHT -> steps(out, sq, r, c, KNIGHT_D);
                case BISHOP -> slides(out, sq, r, c, DIAG);
                case ROOK -> slides(out, sq, r, c, ORTHO);
                case QUEEN -> { slides(out, sq, r, c, DIAG); slides(out, sq, r, c, ORTHO); }
                case KING -> { steps(out, sq, r, c, KING_D); castles(out, sq); }
                default -> {}
            }
        }
        return out;
    }

    private void pawnMoves(List<Move> out, int sq, int r, int c) {
        int dir = side == WHITE ? -1 : 1;
        int startRow = side == WHITE ? 6 : 1, promoRow = side == WHITE ? 0 : 7;
        int r1 = r + dir;
        if (r1 < 0 || r1 > 7) return;
        if (b[r1 * 8 + c] == 0) {
            addPawn(out, sq, r1 * 8 + c, r1 == promoRow);
            int r2 = r + 2 * dir;
            if (r == startRow && b[r2 * 8 + c] == 0) out.add(new Move(sq, r2 * 8 + c));
        }
        for (int dc = -1; dc <= 1; dc += 2) {
            int cc = c + dc;
            if (cc < 0 || cc > 7) continue;
            int t = r1 * 8 + cc;
            if ((b[t] != 0 && Integer.signum(b[t]) == -side) || t == ep) addPawn(out, sq, t, r1 == promoRow);
        }
    }

    private static void addPawn(List<Move> out, int from, int to, boolean promo) {
        if (!promo) { out.add(new Move(from, to)); return; }
        for (int t = QUEEN; t >= KNIGHT; t--) out.add(new Move(from, to, t));
    }

    private void steps(List<Move> out, int sq, int r, int c, int[][] ds) {
        for (int[] d : ds) {
            int rr = r + d[0], cc = c + d[1];
            if (rr < 0 || rr > 7 || cc < 0 || cc > 7) continue;
            int t = rr * 8 + cc;
            if (b[t] == 0 || Integer.signum(b[t]) == -side) out.add(new Move(sq, t));
        }
    }

    private void slides(List<Move> out, int sq, int r, int c, int[][] ds) {
        for (int[] d : ds) {
            int rr = r + d[0], cc = c + d[1];
            while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
                int t = rr * 8 + cc;
                if (b[t] == 0) out.add(new Move(sq, t));
                else { if (Integer.signum(b[t]) == -side) out.add(new Move(sq, t)); break; }
                rr += d[0]; cc += d[1];
            }
        }
    }

    private void castles(List<Move> out, int sq) {
        int home = side == WHITE ? 60 : 4;
        if (sq != home || isAttacked(b, home, -side)) return;
        int k = side == WHITE ? WK : BK, q = side == WHITE ? WQ : BQ;
        if ((castling & k) != 0 && b[home + 1] == 0 && b[home + 2] == 0 && b[home + 3] == side * ROOK
                && !isAttacked(b, home + 1, -side) && !isAttacked(b, home + 2, -side))
            out.add(new Move(home, home + 2));
        if ((castling & q) != 0 && b[home - 1] == 0 && b[home - 2] == 0 && b[home - 3] == 0 && b[home - 4] == side * ROOK
                && !isAttacked(b, home - 1, -side) && !isAttacked(b, home - 2, -side))
            out.add(new Move(home, home - 2));
    }

    // ---------- making moves ----------

    /** Is this move a castle (king moving two files)? */
    public boolean isCastle(Move m) { return Math.abs(b[m.from()]) == KING && Math.abs(m.to() - m.from()) == 2; }

    public boolean isEnPassant(Move m) { return Math.abs(b[m.from()]) == PAWN && m.to() == ep && b[m.to()] == 0 && m.from() % 8 != m.to() % 8; }

    /** Piece captured by m (signed code), 0 if none. */
    public int capturedBy(Move m) {
        if (isEnPassant(m)) return -side * PAWN;
        return b[m.to()];
    }

    /** Applies m without legality checks (call isLegal first for untrusted input). */
    public Position play(Move m) {
        int[] n = b.clone();
        int p = n[m.from()];
        int type = Math.abs(p);
        boolean capture = n[m.to()] != 0;
        if (type == PAWN && m.to() == ep && n[m.to()] == 0 && m.from() % 8 != m.to() % 8) {
            n[m.to() - (side == WHITE ? -8 : 8)] = 0; // remove the pawn that just double-pushed
            capture = true;
        }
        n[m.to()] = m.promotion() != 0 && type == PAWN ? side * m.promotion() : p;
        n[m.from()] = 0;
        if (type == KING && Math.abs(m.to() - m.from()) == 2) {
            if (m.to() > m.from()) { n[m.from() + 1] = n[m.from() + 3]; n[m.from() + 3] = 0; }
            else { n[m.from() - 1] = n[m.from() - 4]; n[m.from() - 4] = 0; }
        }
        int cast = castling;
        if (p == KING) cast &= ~(WK | WQ);
        if (p == -KING) cast &= ~(BK | BQ);
        if (m.from() == 63 || m.to() == 63) cast &= ~WK;
        if (m.from() == 56 || m.to() == 56) cast &= ~WQ;
        if (m.from() == 7 || m.to() == 7) cast &= ~BK;
        if (m.from() == 0 || m.to() == 0) cast &= ~BQ;
        int newEp = type == PAWN && Math.abs(m.to() - m.from()) == 16 ? (m.from() + m.to()) / 2 : -1;
        int hm = type == PAWN || capture ? 0 : halfmove + 1;
        int fm = side == BLACK ? fullmove + 1 : fullmove;
        return new Position(n, -side, cast, newEp, hm, fm);
    }

    /** True when neither side can possibly checkmate (K v K, K+minor v K, bishops all on one colour). */
    public boolean insufficientMaterial() {
        int minors = 0, knights = 0;
        int bishopColors = 0; // bit 1 = light-square bishop, bit 2 = dark-square bishop
        for (int sq = 0; sq < 64; sq++) {
            int t = Math.abs(b[sq]);
            if (t == PAWN || t == ROOK || t == QUEEN) return false;
            if (t == KNIGHT) { minors++; knights++; }
            if (t == BISHOP) { minors++; bishopColors |= ((sq / 8 + sq % 8) % 2 == 0) ? 1 : 2; }
        }
        if (minors <= 1) return true;
        return knights == 0 && bishopColors != 3;
    }

    @Override public boolean equals(Object o) { return o instanceof Position p && p.toFen().equals(toFen()); }
    @Override public int hashCode() { return toFen().hashCode(); }
    @Override public String toString() { return toFen(); }
}
