package ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import rules.Move;
import rules.Position;

/**
 * A small, self-contained chess engine used by the desktop app.  Each style uses the
 * same legal-move search, but values positions differently so opponents feel distinct.
 */
public final class builtInEngine implements moveProvider, analysisProvider {
    public enum style {
        BALANCED("Atlas", "Balanced"),
        ATTACKING("Blitz", "Attacking"),
        POSITIONAL("Bastion", "Positional");

        final String modelName, personality;
        style(String modelName, String personality) { this.modelName = modelName; this.personality = personality; }
    }

    private static final int MATE = Suggestion.MATE_SCORE;
    private static final int[] VALUE = {0, 100, 320, 330, 500, 900, 0};

    private final style personality;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public builtInEngine(style style) { this.personality = style; }

    @Override public String name() { return personality.modelName + " — " + personality.personality; }
    @Override public boolean isAvailable() { return true; }

    @Override
    public CompletableFuture<Move> chooseMove(Position pos, List<Move> history, searchLimits limits) {
        cancelled.set(false);
        return CompletableFuture.supplyAsync(() -> {
            List<Move> legal = pos.legalMoves();
            if (legal.isEmpty()) return null;
            long deadline = System.nanoTime() + Math.max(250, limits.maxTimeMs() - 100) * 1_000_000L;
            Move best = legal.get(0);
            // Iterative deepening means a useful legal move is always ready if the time budget expires.
            for (int depth = 1; depth <= depthFor(limits.skillLevel()); depth++) {
                try { best = root(pos, depth, deadline, 1).get(0).move; }
                catch (searchStopped ignored) { break; }
            }
            return best;
        });
    }

    @Override public void cancel() { cancelled.set(true); }

    @Override
    public CompletableFuture<List<Suggestion>> analyze(Position pos, int maxLines) {
        return CompletableFuture.supplyAsync(() -> {
            cancelled.set(false);
            List<Suggestion> out = new ArrayList<>();
            try {
                long deadline = System.nanoTime() + 2_000_000_000L;
                for (scoredMove choice : root(pos, 3, deadline, Math.max(1, maxLines))) {
                    Position next = pos.play(choice.move);
                    List<Move> line = new ArrayList<>();
                    line.add(choice.move);
                    List<scoredMove> reply = root(next, 2, deadline, 1);
                    if (!reply.isEmpty()) line.add(reply.get(0).move);
                    Integer mate = Math.abs(choice.score) >= MATE - 1000 ? (choice.score > 0 ? 1 : -1) : null;
                    Integer cp = mate == null ? choice.score : null;
                    out.add(new Suggestion(choice.move, cp, mate, 3, line, personality.personality + " model"));
                }
            } catch (searchStopped ignored) {
                // Analysis is optional; returning any completed lines is more useful than an error.
            }
            return out;
        });
    }

    private List<scoredMove> root(Position pos, int depth, long deadline, int wanted) {
        List<Move> moves = new ArrayList<>(pos.legalMoves());
        order(pos, moves);
        List<scoredMove> scored = new ArrayList<>();
        for (Move move : moves) {
            checkStopped(deadline);
            int score = -negamax(pos.play(move), depth - 1, -MATE, MATE, deadline, 1);
            scored.add(new scoredMove(move, score));
        }
        scored.sort(Comparator.comparingInt((scoredMove m) -> m.score).reversed().thenComparing(m -> m.move.uci()));
        return scored.subList(0, Math.min(wanted, scored.size()));
    }

    private int negamax(Position pos, int depth, int alpha, int beta, long deadline, int ply) {
        checkStopped(deadline);
        List<Move> moves = new ArrayList<>(pos.legalMoves());
        if (moves.isEmpty()) return pos.inCheck() ? -MATE + ply : 0;
        if (pos.halfmoveClock() >= 100 || pos.insufficientMaterial()) return 0;
        if (depth == 0) return evaluate(pos);
        order(pos, moves);
        int best = -MATE;
        for (Move move : moves) {
            int score = -negamax(pos.play(move), depth - 1, -beta, -alpha, deadline, ply + 1);
            best = Math.max(best, score);
            alpha = Math.max(alpha, score);
            if (alpha >= beta) break;
        }
        return best;
    }

    /** Evaluation from the point of view of the side to move. */
    private int evaluate(Position pos) {
        int white = 0;
        int[] board = pos.boardCopy();
        for (int sq = 0; sq < 64; sq++) {
            int piece = board[sq];
            if (piece == 0) continue;
            int sign = Integer.signum(piece);
            int type = Math.abs(piece);
            int row = sq / 8, col = sq % 8;
            int score = VALUE[type] + centrality(row, col, type);
            if (type == Position.PAWN) score += (sign == Position.WHITE ? 6 - row : row - 1) * pawnAdvance();
            if (type == Position.KING) score += kingSafety(board, sq, sign);
            white += sign * score;
        }
        return white * pos.sideToMove();
    }

    private int centrality(int row, int col, int type) {
        int distance = Math.abs(row - 3) + Math.abs(col - 3);
        int bonus = Math.max(0, 4 - distance) * (type == Position.KNIGHT ? 9 : type == Position.BISHOP ? 6 : 3);
        return personality == style.ATTACKING ? bonus * 2 : bonus;
    }

    private int pawnAdvance() { return personality == style.ATTACKING ? 9 : personality == style.POSITIONAL ? 5 : 7; }

    private int kingSafety(int[] board, int king, int side) {
        int row = king / 8, col = king % 8;
        int score = 0;
        boolean castled = (side == Position.WHITE && row == 7 && (col == 2 || col == 6))
                || (side == Position.BLACK && row == 0 && (col == 2 || col == 6));
        if (castled) score += personality == style.POSITIONAL ? 42 : 24;
        int pawnRow = row + (side == Position.WHITE ? -1 : 1);
        if (pawnRow >= 0 && pawnRow < 8) {
            for (int dc = -1; dc <= 1; dc++) {
                int c = col + dc;
                if (c >= 0 && c < 8 && board[pawnRow * 8 + c] == side * Position.PAWN) score += personality == style.POSITIONAL ? 12 : 6;
            }
        }
        return score;
    }

    private void order(Position pos, List<Move> moves) {
        moves.sort(Comparator.comparingInt((Move m) -> moveOrder(pos, m)).reversed().thenComparing(Move::uci));
    }

    private int moveOrder(Position pos, Move move) {
        int moving = Math.abs(pos.pieceAt(move.from()));
        int captured = Math.abs(pos.capturedBy(move));
        int score = captured * 20 - moving + move.promotion() * 30;
        Position next = pos.play(move);
        if (next.inCheck()) score += personality == style.ATTACKING ? 180 : 80;
        if (personality == style.ATTACKING && (moving == Position.PAWN || moving == Position.KNIGHT)) score += 12;
        if (personality == style.POSITIONAL && pos.isCastle(move)) score += 100;
        return score;
    }

    private static int depthFor(int skill) {
        if (skill <= 2) return 1;
        if (skill <= 5) return 2;
        if (skill <= 8) return 3;
        return 4;
    }

    private void checkStopped(long deadline) {
        if (cancelled.get() || System.nanoTime() >= deadline) throw new searchStopped();
    }

    private record scoredMove(Move move, int score) { }
    private static final class searchStopped extends RuntimeException { }
}
