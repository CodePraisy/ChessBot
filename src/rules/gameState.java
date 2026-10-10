package rules;

import java.util.ArrayList;
import java.util.List;

/**
 * A game as a list of positions and moves with end-of-game detection.
 * Pure rules: no Swing, no AI, no I/O.
 */
public final class gameState {
    public enum Status { ONGOING, CHECKMATE, STALEMATE, INSUFFICIENT_MATERIAL, THREEFOLD_REPETITION, FIFTY_MOVE_RULE, RESIGNATION, DRAW_AGREED }

    private final String startFen;
    private final List<Position> positions = new ArrayList<>();
    private final List<Move> moves = new ArrayList<>();
    private final List<String> sans = new ArrayList<>();
    private final List<Integer> captured = new ArrayList<>();
    private Status status = Status.ONGOING;
    private int winner; // 1 white, -1 black, 0 none/draw

    public gameState() { this(Position.START_FEN); }

    public gameState(String fen) {
        Position p = Position.fromFen(fen);
        this.startFen = p.toFen();
        positions.add(p);
        updateStatus();
    }

    /** Replays a list of moves; throws IllegalArgumentException on the first illegal one. */
    public static gameState replay(String fen, List<Move> moves) {
        gameState g = new gameState(fen);
        for (int i = 0; i < moves.size(); i++)
            if (!g.play(moves.get(i))) throw new IllegalArgumentException("Illegal move at ply " + (i + 1) + ": " + moves.get(i));
        return g;
    }

    public String startFen() { return startFen; }
    public Position current() { return positions.get(positions.size() - 1); }
    public Position positionAt(int ply) { return positions.get(ply); }
    public int plyCount() { return moves.size(); }
    public Move move(int i) { return moves.get(i); }
    public List<Move> moves() { return List.copyOf(moves); }
    public String san(int i) { return sans.get(i); }
    public List<String> sans() { return List.copyOf(sans); }
    /** Signed code of the piece captured on ply i (0-based), 0 if none. */
    public int capturedAt(int i) { return captured.get(i); }
    public Status status() { return status; }
    public boolean isOver() { return status != Status.ONGOING; }
    public int winner() { return winner; }
    public Move lastMove() { return moves.isEmpty() ? null : moves.get(moves.size() - 1); }

    /** Plays m if the game is ongoing and m is legal. */
    public boolean play(Move m) {
        Position p = current();
        if (isOver() || !p.isLegal(m)) return false;
        sans.add(notation.toSan(p, m));
        captured.add(p.capturedBy(m));
        moves.add(m);
        positions.add(p.play(m));
        updateStatus();
        return true;
    }

    public void resign(int color) {
        if (isOver()) return;
        status = Status.RESIGNATION;
        winner = -color;
    }

    public void agreeDraw() {
        if (isOver()) return;
        status = Status.DRAW_AGREED;
        winner = 0;
    }

    /** Restores a finished state read from a save (resignation / agreed draw are not derivable from moves). */
    public void forceResult(Status s, int winner) { this.status = s; this.winner = winner; }

    private void updateStatus() {
        Position p = current();
        winner = 0;
        if (p.legalMoves().isEmpty()) {
            if (p.inCheck()) { status = Status.CHECKMATE; winner = -p.sideToMove(); }
            else status = Status.STALEMATE;
        } else if (p.insufficientMaterial()) status = Status.INSUFFICIENT_MATERIAL;
        else if (p.halfmoveClock() >= 100) status = Status.FIFTY_MOVE_RULE;
        else if (repetitions() >= 3) status = Status.THREEFOLD_REPETITION;
        else status = Status.ONGOING;
    }

    private int repetitions() {
        String key = current().repetitionKey();
        int n = 0;
        // Only positions since the last irreversible move can repeat.
        for (int i = positions.size() - 1; i >= 0; i--) {
            if (positions.get(i).repetitionKey().equals(key)) n++;
            if (positions.get(i).halfmoveClock() == 0) break;
        }
        return n;
    }

    /** "1-0", "0-1", "1/2-1/2" or "*". */
    public String result() {
        if (!isOver()) return "*";
        return winner > 0 ? "1-0" : winner < 0 ? "0-1" : "1/2-1/2";
    }

    public String terminationText() {
        String side = winner > 0 ? "White" : "Black";
        return switch (status) {
            case ONGOING -> "Game in progress";
            case CHECKMATE -> "Checkmate - " + side + " wins";
            case STALEMATE -> "Draw by stalemate";
            case INSUFFICIENT_MATERIAL -> "Draw by insufficient material";
            case THREEFOLD_REPETITION -> "Draw by threefold repetition";
            case FIFTY_MOVE_RULE -> "Draw by the 50-move rule";
            case RESIGNATION -> (winner > 0 ? "Black" : "White") + " resigned - " + side + " wins";
            case DRAW_AGREED -> "Draw by agreement";
        };
    }
}
