package ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Alpha-beta search used by every native engine: iterative deepening, principal-variation search,
 * transposition table, quiescence search (captures and promotions, so it never stops mid-trade),
 * check extensions, null-move pruning, late-move reductions, killer and history move ordering.
 * The evaluator (a trained network or the hand-written one) only scores quiet positions at the leaves.
 * Not thread-safe: one search at a time per instance; stop() may be called from any thread.
 */
public final class searchEngine {
    public static final int MATE = 30_000, INF = 32_000, MAX_PLY = 96;
    private static final int[] VALUE = {0, 100, 320, 330, 500, 900, 20000};

    public record result(int move, int score, int depth, long nodes, int[] pv) {}

    private final neuralNet eval;
    private fastBoard board;
    private volatile boolean stop;
    private long deadline, nodes;

    // transposition table
    private static final int TT_BITS = 20;
    private long[] ttKey;   // allocated on first use (about 18 MB), so idle engines cost little memory
    private int[] ttMove, ttScore;
    private byte[] ttDepth, ttFlag;
    private static final byte EXACT = 1, LOWER = 2, UPPER = 3;

    private final int[][] killers = new int[MAX_PLY + 1][2];
    private final int[][] history = new int[64][64];
    private final int[][] moves = new int[MAX_PLY + 1][256], scores = new int[MAX_PLY + 1][256];

    public searchEngine(neuralNet eval) { this.eval = eval; }

    public void stop() { stop = true; }

    /** Searches until maxDepth or timeMs; returns the best move of the deepest finished iteration. */
    public result think(fastBoard position, long timeMs, int maxDepth) {
        board = position;
        stop = false;
        if (ttKey == null) {
            ttKey = new long[1 << TT_BITS];
            ttMove = new int[1 << TT_BITS];
            ttScore = new int[1 << TT_BITS];
            ttDepth = new byte[1 << TT_BITS];
            ttFlag = new byte[1 << TT_BITS];
        }
        nodes = 0;
        long start = System.currentTimeMillis();
        deadline = start + Math.max(10, timeMs);
        for (int[] k : killers) { k[0] = 0; k[1] = 0; }
        for (int[] h : history) java.util.Arrays.fill(h, 0);

        int[] root = new int[256];
        int count = legalMoves(root);
        if (count == 0) return new result(0, board.inCheck() ? -MATE : 0, 0, 0, new int[0]);
        result best = new result(root[0], 0, 0, 0, new int[]{root[0]});
        if (count == 1) return best;
        int depthLimit = Math.max(1, Math.min(maxDepth, MAX_PLY - 8));
        int previous = 0;
        for (int depth = 1; depth <= depthLimit; depth++) {
            int alpha = -INF, beta = INF, window = 40;
            if (depth >= 4 && Math.abs(previous) < MATE - 500) { alpha = previous - window; beta = previous + window; }
            int score;
            while (true) {
                score = search(depth, alpha, beta, 0, true);
                if (stop) break;
                if (score <= alpha) { alpha = Math.max(-INF, alpha - window * 4); window *= 4; }
                else if (score >= beta) { beta = Math.min(INF, beta + window * 4); window *= 4; }
                else break;
            }
            if (stop) break;
            int move = probeMove();
            if (move == 0) break;
            previous = score;
            best = new result(move, score, depth, nodes, principalVariation(depth));
            if (Math.abs(score) >= MATE - depth) break; // forced mate found
            if (System.currentTimeMillis() - start > (deadline - start) / 2) break; // next depth would not finish
        }
        return new result(best.move(), best.score(), best.depth(), nodes, best.pv());
    }

    /** Exact scores for the best root moves (for hints and review). */
    public List<result> analyze(fastBoard position, int lines, long timeMs) {
        result main = think(position, timeMs * 2 / 3, 64);
        List<result> out = new ArrayList<>();
        if (main.move() == 0) return out;
        out.add(main);
        int depth = Math.max(1, main.depth() - 1);
        int[] root = new int[256];
        int count = legalMoves(root);
        deadline = System.currentTimeMillis() + Math.max(50, timeMs / 3);
        stop = false;
        for (int i = 0; i < count && !stop; i++) {
            if (root[i] == main.move()) continue;
            board.make(root[i]);
            int s = -search(depth - 1, -INF, INF, 1, true);
            int[] pv = principalVariation(depth - 1);
            board.unmake(root[i]);
            if (!stop) {
                int[] line = new int[pv.length + 1];
                line[0] = root[i];
                System.arraycopy(pv, 0, line, 1, pv.length);
                out.add(new result(root[i], s, depth, nodes, line));
            }
        }
        out.sort((a, b) -> b.score() - a.score());
        if (out.get(0).move() != main.move()) { out.remove(main); out.add(0, main); }
        return out.subList(0, Math.min(lines, out.size()));
    }

    private int legalMoves(int[] out) {
        int[] all = new int[256];
        int n = board.generate(all, false), k = 0;
        for (int i = 0; i < n; i++) if (board.legal(all[i])) out[k++] = all[i];
        return k;
    }

    private int probeMove() {
        int i = (int) (board.hash & ((1 << TT_BITS) - 1));
        return ttKey[i] == board.hash ? ttMove[i] : 0;
    }

    private int[] principalVariation(int depth) {
        List<Integer> line = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        while (line.size() < depth && seen.add(board.hash)) {
            int m = probeMove();
            if (m == 0 || !board.legal(m)) break;
            board.make(m);
            line.add(m);
        }
        for (int i = line.size() - 1; i >= 0; i--) board.unmake(line.get(i));
        return line.stream().mapToInt(Integer::intValue).toArray();
    }

    private void checkTime() {
        if (System.currentTimeMillis() >= deadline) stop = true;
    }

    private int search(int depth, int alpha, int beta, int ply, boolean allowNull) {
        if ((++nodes & 1023) == 0) checkTime();
        if (stop) return 0;
        if (ply > 0) {
            if (board.halfmove >= 100 || board.repeated() || board.insufficientMaterial()) return 0;
            alpha = Math.max(alpha, -MATE + ply);
            beta = Math.min(beta, MATE - ply - 1);
            if (alpha >= beta) return alpha;
        }
        boolean inCheck = board.inCheck();
        if (inCheck) depth++;
        if (depth <= 0 || ply >= MAX_PLY) return quiesce(alpha, beta, ply);

        int slot = (int) (board.hash & ((1 << TT_BITS) - 1));
        int ttBest = 0;
        if (ttKey[slot] == board.hash) {
            ttBest = ttMove[slot];
            if (ply > 0 && ttDepth[slot] >= depth) {
                int s = fromTable(ttScore[slot], ply);
                byte f = ttFlag[slot];
                if (f == EXACT || (f == LOWER && s >= beta) || (f == UPPER && s <= alpha)) return s;
            }
        }
        boolean pvNode = beta - alpha > 1;
        if (!inCheck && !pvNode) {
            int standing = eval.evaluate(board);
            if (depth <= 3 && standing - 120 * depth >= beta && Math.abs(beta) < MATE - 500) return standing;
            if (allowNull && depth >= 3 && standing >= beta && board.hasPieces(board.side)) {
                board.makeNull();
                int s = -search(depth - 1 - (depth >= 6 ? 3 : 2), -beta, -beta + 1, ply + 1, false);
                board.unmakeNull();
                if (stop) return 0;
                if (s >= beta) return Math.abs(s) >= MATE - 500 ? beta : s;
            }
        }

        int[] list = moves[ply], score = scores[ply];
        int n = board.generate(list, false);
        for (int i = 0; i < n; i++) score[i] = order(list[i], ttBest, ply);
        int best = -INF, bestMove = 0, legal = 0, startAlpha = alpha;
        for (int i = 0; i < n; i++) {
            pickNext(list, score, i, n);
            int m = list[i];
            if (!board.make(m)) { board.unmake(m); continue; }
            legal++;
            boolean quiet = !fastBoard.isCapture(m) && fastBoard.promotion(m) == 0;
            int s;
            if (legal == 1) s = -search(depth - 1, -beta, -alpha, ply + 1, true);
            else {
                int r = 0;
                if (depth >= 3 && legal > 3 && quiet && !inCheck && !board.inCheck()) r = legal > 10 ? 2 : 1;
                s = -search(depth - 1 - r, -alpha - 1, -alpha, ply + 1, true);
                if (s > alpha && r > 0) s = -search(depth - 1, -alpha - 1, -alpha, ply + 1, true);
                if (s > alpha && s < beta) s = -search(depth - 1, -beta, -alpha, ply + 1, true);
            }
            board.unmake(m);
            if (stop) return 0;
            if (s > best) {
                best = s;
                bestMove = m;
                if (s > alpha) {
                    alpha = s;
                    if (alpha >= beta) {
                        if (quiet) {
                            if (killers[ply][0] != m) { killers[ply][1] = killers[ply][0]; killers[ply][0] = m; }
                            history[fastBoard.from(m)][fastBoard.to(m)] += depth * depth;
                        }
                        break;
                    }
                }
            }
        }
        if (legal == 0) return inCheck ? -MATE + ply : 0;
        byte flag = best >= beta ? LOWER : best > startAlpha ? EXACT : UPPER;
        if (ttKey[slot] != board.hash || depth >= ttDepth[slot] || flag == EXACT) {
            ttKey[slot] = board.hash;
            ttMove[slot] = bestMove;
            ttScore[slot] = toTable(best, ply);
            ttDepth[slot] = (byte) Math.min(depth, 127);
            ttFlag[slot] = flag;
        }
        return best;
    }

    private int quiesce(int alpha, int beta, int ply) {
        if ((++nodes & 1023) == 0) checkTime();
        if (stop) return 0;
        boolean inCheck = board.inCheck();
        int standing = -INF;
        if (!inCheck) {
            standing = eval.evaluate(board);
            if (standing >= beta || ply >= MAX_PLY) return standing;
            alpha = Math.max(alpha, standing);
        } else if (ply >= MAX_PLY) return eval.evaluate(board);
        int[] list = moves[ply], score = scores[ply];
        int n = board.generate(list, !inCheck); // in check: every evasion
        for (int i = 0; i < n; i++) score[i] = order(list[i], 0, ply);
        int best = standing, legal = 0;
        for (int i = 0; i < n; i++) {
            pickNext(list, score, i, n);
            int m = list[i];
            if (!inCheck && fastBoard.promotion(m) == 0) {
                int victim = (m & fastBoard.EN_PASSANT) != 0 ? fastBoard.PAWN : Math.abs(board.b[fastBoard.to(m)]);
                if (standing + VALUE[victim] + 200 < alpha) continue; // delta pruning: cannot raise alpha
            }
            if (!board.make(m)) { board.unmake(m); continue; }
            legal++;
            int s = -quiesce(-beta, -alpha, ply + 1);
            board.unmake(m);
            if (stop) return 0;
            if (s > best) {
                best = s;
                if (s > alpha) { alpha = s; if (alpha >= beta) break; }
            }
        }
        if (inCheck && legal == 0) return -MATE + ply;
        return best;
    }

    private int order(int m, int ttBest, int ply) {
        if (m == ttBest) return 1 << 30;
        int to = fastBoard.to(m), from = fastBoard.from(m);
        if (fastBoard.isCapture(m)) {
            int victim = (m & fastBoard.EN_PASSANT) != 0 ? fastBoard.PAWN : Math.abs(board.b[to]);
            return (1 << 28) + VALUE[victim] * 16 - Math.abs(board.b[from]);
        }
        if (fastBoard.promotion(m) == fastBoard.QUEEN) return (1 << 28) + 800;
        if (m == killers[ply][0]) return (1 << 27) + 2;
        if (m == killers[ply][1]) return (1 << 27) + 1;
        return Math.min(history[from][to], (1 << 26));
    }

    private static void pickNext(int[] list, int[] score, int i, int n) {
        int best = i;
        for (int j = i + 1; j < n; j++) if (score[j] > score[best]) best = j;
        if (best != i) {
            int t = list[i]; list[i] = list[best]; list[best] = t;
            t = score[i]; score[i] = score[best]; score[best] = t;
        }
    }

    private static int toTable(int s, int ply) { return s >= MATE - 500 ? s + ply : s <= -MATE + 500 ? s - ply : s; }
    private static int fromTable(int s, int ply) { return s >= MATE - 500 ? s - ply : s <= -MATE + 500 ? s + ply : s; }
}
