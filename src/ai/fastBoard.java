package ai;

import java.util.Random;

import rules.Move;
import rules.Position;

/**
 * Mutable board for engine search: make/unmake instead of copying, Zobrist hashing and int-encoded moves.
 * Same square layout and piece codes as rules.Position (index = row * 8 + col, row 0 = rank 8;
 * +1..+6 White pawn..king, negative for Black). rules.Position stays the authority for the game itself.
 */
public final class fastBoard {
    public static final int WHITE = 1, BLACK = -1;
    public static final int PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
    static final int WK = 1, WQ = 2, BK = 4, BQ = 8;
    // move encoding: from | to << 6 | promotion << 12 | flags
    static final int CAPTURE = 1 << 16, EN_PASSANT = 1 << 17, CASTLE = 1 << 18, DOUBLE = 1 << 19;
    static final int MAX_HISTORY = 2048;

    final int[] b = new int[64];
    int side = WHITE, castling, ep = -1, halfmove;
    long hash;
    final int[] king = new int[2]; // [0] White, [1] Black

    private final int[] undoCaptured = new int[MAX_HISTORY], undoCastling = new int[MAX_HISTORY], undoEp = new int[MAX_HISTORY],
            undoHalfmove = new int[MAX_HISTORY];
    private final long[] hashes = new long[MAX_HISTORY]; // hash after each position, for repetition checks
    private int length; // number of positions in hashes (current one included)

    // ---------- static tables ----------

    static final long[][] PIECE_KEYS = new long[13][64];
    static final long SIDE_KEY;
    static final long[] CASTLE_KEYS = new long[16], EP_KEYS = new long[8];
    static final int[][] KNIGHT_TO = new int[64][], KING_TO = new int[64][];
    static final int[][][] RAYS = new int[64][8][]; // 0-3 orthogonal, 4-7 diagonal
    static final int[] CASTLE_MASK = new int[64];
    private static final int[][] DIRS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

    static {
        Random r = new Random(20261011L);
        for (long[] row : PIECE_KEYS) for (int i = 0; i < 64; i++) row[i] = r.nextLong();
        SIDE_KEY = r.nextLong();
        for (int i = 0; i < 16; i++) CASTLE_KEYS[i] = r.nextLong();
        for (int i = 0; i < 8; i++) EP_KEYS[i] = r.nextLong();
        int[][] knight = {{-2, -1}, {-2, 1}, {-1, -2}, {-1, 2}, {1, -2}, {1, 2}, {2, -1}, {2, 1}};
        for (int sq = 0; sq < 64; sq++) {
            KNIGHT_TO[sq] = targets(sq, knight);
            KING_TO[sq] = targets(sq, DIRS);
            for (int d = 0; d < 8; d++) {
                int[] tmp = new int[7];
                int n = 0, rr = sq / 8 + DIRS[d][0], cc = sq % 8 + DIRS[d][1];
                while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) { tmp[n++] = rr * 8 + cc; rr += DIRS[d][0]; cc += DIRS[d][1]; }
                RAYS[sq][d] = java.util.Arrays.copyOf(tmp, n);
            }
            CASTLE_MASK[sq] = 15;
        }
        CASTLE_MASK[60] = 15 & ~(WK | WQ);
        CASTLE_MASK[63] = 15 & ~WK;
        CASTLE_MASK[56] = 15 & ~WQ;
        CASTLE_MASK[4] = 15 & ~(BK | BQ);
        CASTLE_MASK[7] = 15 & ~BK;
        CASTLE_MASK[0] = 15 & ~BQ;
    }

    private static int[] targets(int sq, int[][] ds) {
        int[] tmp = new int[8];
        int n = 0;
        for (int[] d : ds) {
            int rr = sq / 8 + d[0], cc = sq % 8 + d[1];
            if (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) tmp[n++] = rr * 8 + cc;
        }
        return java.util.Arrays.copyOf(tmp, n);
    }

    static long pieceKey(int piece, int sq) { return PIECE_KEYS[piece + 6][sq]; }

    // ---------- setup ----------

    public static fastBoard fromFen(String fen) {
        fastBoard f = new fastBoard();
        String[] parts = fen.trim().split("\\s+");
        String[] rows = parts[0].split("/");
        for (int r = 0; r < 8; r++) {
            int c = 0;
            for (char ch : rows[r].toCharArray()) {
                if (Character.isDigit(ch)) { c += ch - '0'; continue; }
                int t = "pnbrqk".indexOf(Character.toLowerCase(ch)) + 1;
                f.b[r * 8 + c++] = Character.isUpperCase(ch) ? t : -t;
            }
        }
        f.side = parts.length > 1 && parts[1].equals("b") ? BLACK : WHITE;
        String rights = parts.length > 2 ? parts[2] : "-";
        if (rights.contains("K") && f.b[60] == KING && f.b[63] == ROOK) f.castling |= WK;
        if (rights.contains("Q") && f.b[60] == KING && f.b[56] == ROOK) f.castling |= WQ;
        if (rights.contains("k") && f.b[4] == -KING && f.b[7] == -ROOK) f.castling |= BK;
        if (rights.contains("q") && f.b[4] == -KING && f.b[0] == -ROOK) f.castling |= BQ;
        f.ep = parts.length > 3 && !parts[3].equals("-") ? (8 - (parts[3].charAt(1) - '0')) * 8 + (parts[3].charAt(0) - 'a') : -1;
        f.halfmove = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        for (int sq = 0; sq < 64; sq++) {
            if (f.b[sq] == KING) f.king[0] = sq;
            if (f.b[sq] == -KING) f.king[1] = sq;
        }
        f.hash = f.computeHash();
        f.hashes[0] = f.hash;
        f.length = 1;
        return f;
    }

    public static fastBoard from(Position p) { return fromFen(p.toFen()); }

    long computeHash() {
        long h = 0;
        for (int sq = 0; sq < 64; sq++) if (b[sq] != 0) h ^= pieceKey(b[sq], sq);
        if (side == BLACK) h ^= SIDE_KEY;
        h ^= CASTLE_KEYS[castling];
        if (ep >= 0) h ^= EP_KEYS[ep % 8];
        return h;
    }

    // ---------- moves ----------

    static int from(int m) { return m & 63; }
    static int to(int m) { return (m >> 6) & 63; }
    static int promotion(int m) { return (m >> 12) & 15; }
    static boolean isCapture(int m) { return (m & (CAPTURE | EN_PASSANT)) != 0; }

    public static Move toMove(int m) { return new Move(from(m), to(m), promotion(m)); }

    /** The encoded legal move matching m, or 0. */
    public int find(Move m) {
        int[] list = new int[256];
        int n = generate(list, false);
        for (int i = 0; i < n; i++) {
            int x = list[i];
            if (from(x) == m.from() && to(x) == m.to() && promotion(x) == m.promotion() && legal(x)) return x;
        }
        return 0;
    }

    /** Pseudo-legal moves (captures and promotions only when tacticalOnly). Returns the count. */
    public int generate(int[] out, boolean tacticalOnly) {
        int n = 0, us = side;
        for (int sq = 0; sq < 64; sq++) {
            int p = b[sq];
            if (p == 0 || (p > 0 ? WHITE : BLACK) != us) continue;
            switch (p * us) {
                case PAWN -> n = pawnMoves(out, n, sq, tacticalOnly);
                case KNIGHT -> n = steps(out, n, sq, KNIGHT_TO[sq], tacticalOnly);
                case BISHOP -> n = slides(out, n, sq, 4, 8, tacticalOnly);
                case ROOK -> n = slides(out, n, sq, 0, 4, tacticalOnly);
                case QUEEN -> n = slides(out, n, sq, 0, 8, tacticalOnly);
                case KING -> {
                    n = steps(out, n, sq, KING_TO[sq], tacticalOnly);
                    if (!tacticalOnly) n = castles(out, n, sq);
                }
                default -> { }
            }
        }
        return n;
    }

    private int pawnMoves(int[] out, int n, int sq, boolean tacticalOnly) {
        int dir = side == WHITE ? -8 : 8, row = sq / 8, col = sq % 8;
        int one = sq + dir;
        boolean promo = side == WHITE ? row == 1 : row == 6;
        if (b[one] == 0) {
            if (promo) n = promotions(out, n, sq, one, 0, tacticalOnly);
            else if (!tacticalOnly) {
                out[n++] = sq | one << 6;
                if ((side == WHITE ? row == 6 : row == 1) && b[one + dir] == 0) out[n++] = sq | (one + dir) << 6 | DOUBLE;
            }
        }
        for (int dc = -1; dc <= 1; dc += 2) {
            int c = col + dc;
            if (c < 0 || c > 7) continue;
            int t = one + dc;
            if (b[t] != 0 && (b[t] > 0 ? WHITE : BLACK) != side) {
                if (promo) n = promotions(out, n, sq, t, CAPTURE, tacticalOnly);
                else out[n++] = sq | t << 6 | CAPTURE;
            } else if (t == ep && b[t] == 0) out[n++] = sq | t << 6 | EN_PASSANT;
        }
        return n;
    }

    private static int promotions(int[] out, int n, int from, int to, int flags, boolean queenOnly) {
        out[n++] = from | to << 6 | QUEEN << 12 | flags;
        if (!queenOnly) for (int t = ROOK; t >= KNIGHT; t--) out[n++] = from | to << 6 | t << 12 | flags;
        return n;
    }

    private int steps(int[] out, int n, int sq, int[] targets, boolean tacticalOnly) {
        for (int t : targets) {
            int p = b[t];
            if (p == 0) { if (!tacticalOnly) out[n++] = sq | t << 6; }
            else if ((p > 0 ? WHITE : BLACK) != side) out[n++] = sq | t << 6 | CAPTURE;
        }
        return n;
    }

    private int slides(int[] out, int n, int sq, int d0, int d1, boolean tacticalOnly) {
        for (int d = d0; d < d1; d++) {
            for (int t : RAYS[sq][d]) {
                int p = b[t];
                if (p == 0) { if (!tacticalOnly) out[n++] = sq | t << 6; continue; }
                if ((p > 0 ? WHITE : BLACK) != side) out[n++] = sq | t << 6 | CAPTURE;
                break;
            }
        }
        return n;
    }

    private int castles(int[] out, int n, int sq) {
        int home = side == WHITE ? 60 : 4;
        if (sq != home) return n;
        int k = side == WHITE ? WK : BK, q = side == WHITE ? WQ : BQ;
        if ((castling & (k | q)) == 0 || attacked(home, -side)) return n;
        if ((castling & k) != 0 && b[home + 1] == 0 && b[home + 2] == 0 && !attacked(home + 1, -side) && !attacked(home + 2, -side))
            out[n++] = home | (home + 2) << 6 | CASTLE;
        if ((castling & q) != 0 && b[home - 1] == 0 && b[home - 2] == 0 && b[home - 3] == 0 && !attacked(home - 1, -side) && !attacked(home - 2, -side))
            out[n++] = home | (home - 2) << 6 | CASTLE;
        return n;
    }

    /** Is sq attacked by colour `by`? */
    public boolean attacked(int sq, int by) {
        int row = sq / 8, col = sq % 8;
        int pr = by == WHITE ? row + 1 : row - 1; // the row an attacking pawn would stand on
        if (pr >= 0 && pr < 8) {
            if (col > 0 && b[pr * 8 + col - 1] == by * PAWN) return true;
            if (col < 7 && b[pr * 8 + col + 1] == by * PAWN) return true;
        }
        for (int t : KNIGHT_TO[sq]) if (b[t] == by * KNIGHT) return true;
        for (int t : KING_TO[sq]) if (b[t] == by * KING) return true;
        for (int d = 0; d < 8; d++) {
            int straight = d < 4 ? ROOK : BISHOP;
            for (int t : RAYS[sq][d]) {
                int p = b[t];
                if (p == 0) continue;
                if (p == by * straight || p == by * QUEEN) return true;
                break;
            }
        }
        return false;
    }

    public boolean inCheck() { return attacked(king[side == WHITE ? 0 : 1], -side); }

    /** Would m leave the mover's own king safe? (makes and unmakes it) */
    public boolean legal(int m) {
        boolean ok = make(m);
        unmake(m);
        return ok;
    }

    /** Plays m; returns false (move still made, call unmake) if it leaves the mover's king in check. */
    public boolean make(int m) {
        int from = from(m), to = to(m), piece = b[from], us = side, i = length - 1;
        undoCastling[i] = castling;
        undoEp[i] = ep;
        undoHalfmove[i] = halfmove;
        int captured = 0;
        long h = hash ^ pieceKey(piece, from);
        if ((m & EN_PASSANT) != 0) {
            int cap = to - us * -8; // the pawn that just double-pushed sits behind the target square
            captured = b[cap];
            b[cap] = 0;
            h ^= pieceKey(captured, cap);
        } else if (b[to] != 0) {
            captured = b[to];
            h ^= pieceKey(captured, to);
        }
        undoCaptured[i] = captured;
        int promo = promotion(m);
        int placed = promo != 0 ? us * promo : piece;
        b[from] = 0;
        b[to] = placed;
        h ^= pieceKey(placed, to);
        if (piece * us == KING) {
            king[us == WHITE ? 0 : 1] = to;
            if ((m & CASTLE) != 0) {
                int rf = to > from ? from + 3 : from - 4, rt = to > from ? from + 1 : from - 1;
                b[rt] = b[rf];
                b[rf] = 0;
                h ^= pieceKey(us * ROOK, rf) ^ pieceKey(us * ROOK, rt);
            }
        }
        if (ep >= 0) h ^= EP_KEYS[ep % 8];
        ep = (m & DOUBLE) != 0 ? (from + to) / 2 : -1;
        if (ep >= 0) h ^= EP_KEYS[ep % 8];
        h ^= CASTLE_KEYS[castling];
        castling &= CASTLE_MASK[from] & CASTLE_MASK[to];
        h ^= CASTLE_KEYS[castling];
        halfmove = piece * us == PAWN || captured != 0 ? 0 : halfmove + 1;
        side = -us;
        hash = h ^ SIDE_KEY;
        hashes[length++] = hash;
        return !attacked(king[us == WHITE ? 0 : 1], -us);
    }

    public void unmake(int m) {
        length--;
        int i = length - 1;
        int us = -side, from = from(m), to = to(m);
        side = us;
        int placed = b[to];
        int piece = promotion(m) != 0 ? us * PAWN : placed;
        b[from] = piece;
        b[to] = 0;
        int captured = undoCaptured[i];
        if ((m & EN_PASSANT) != 0) b[to - us * -8] = captured;
        else b[to] = captured;
        if (piece * us == KING) {
            king[us == WHITE ? 0 : 1] = from;
            if ((m & CASTLE) != 0) {
                int rf = to > from ? from + 3 : from - 4, rt = to > from ? from + 1 : from - 1;
                b[rf] = b[rt];
                b[rt] = 0;
            }
        }
        castling = undoCastling[i];
        ep = undoEp[i];
        halfmove = undoHalfmove[i];
        hash = hashes[length - 1];
    }

    /** Passing move for null-move pruning. */
    public void makeNull() {
        int i = length - 1;
        undoEp[i] = ep;
        undoHalfmove[i] = halfmove;
        long h = hash;
        if (ep >= 0) h ^= EP_KEYS[ep % 8];
        ep = -1;
        halfmove++;
        side = -side;
        hash = h ^ SIDE_KEY;
        hashes[length++] = hash;
    }

    public void unmakeNull() {
        length--;
        int i = length - 1;
        side = -side;
        ep = undoEp[i];
        halfmove = undoHalfmove[i];
        hash = hashes[length - 1];
    }

    /** Has this position occurred before since the last irreversible move? (one repeat counts as a draw in search) */
    public boolean repeated() {
        int stop = Math.max(0, length - 1 - halfmove);
        for (int i = length - 3; i >= stop; i -= 2) if (hashes[i] == hash) return true;
        return false;
    }

    /** Has this position occurred twice before (a real threefold repetition)? */
    public boolean threefold() {
        int stop = Math.max(0, length - 1 - halfmove), seen = 0;
        for (int i = length - 3; i >= stop; i -= 2) if (hashes[i] == hash && ++seen >= 2) return true;
        return false;
    }

    public boolean insufficientMaterial() {
        int minors = 0;
        for (int p : b) {
            int t = Math.abs(p);
            if (t == PAWN || t == ROOK || t == QUEEN) return false;
            if (t == KNIGHT || t == BISHOP) minors++;
        }
        return minors <= 1;
    }

    public boolean hasPieces(int color) {
        for (int p : b) if (p * color > PAWN && p * color < KING) return true;
        return false;
    }

    /** Plays a move from the rules layer (must be legal). */
    public boolean play(Move m) {
        int x = find(m);
        if (x == 0) return false;
        make(x);
        return true;
    }

    public long perft(int depth) {
        if (depth == 0) return 1;
        int[] list = new int[256];
        int n = generate(list, false);
        long total = 0;
        for (int i = 0; i < n; i++) {
            if (make(list[i])) total += perft(depth - 1);
            unmake(list[i]);
        }
        return total;
    }

    public int pieceAt(int sq) { return b[sq]; }
    public int sideToMove() { return side; }
    public int castlingRights() { return castling; }
    public int epSquare() { return ep; }
    public int halfmoveClock() { return halfmove; }
    public long hash() { return hash; }
}
