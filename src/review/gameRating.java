package review;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import handlers.Json;
import handlers.paths;

/**
 * Rates how well each side played one game, from how much each move lost compared with the engine's best move.
 *
 *  - Accuracy (0-100%): Lichess's formula. Each move's loss is measured in winning chances, so dropping
 *    50 centipawns in an equal position costs more than in a position that is already won.
 *  - Game rating: an Elo estimate from the average centipawn loss (ACPL): a + b * ln(ACPL + 10), with a and b
 *    fitted to games between Stockfish's fixed-Elo levels (ai.ratingCalibration). A single game is noisy;
 *    the overall rating averages many. It assumes Stockfish does the analysis, as in calibration.
 *  - Overall rating: the average game rating of a player's (or engine's) last 20 analysed games,
 *    kept in src/assets/saves/ratings.json. Each game counts once, however often it is analysed.
 */
public final class gameRating {
    private gameRating() {}

    public static final int RECENT = 20, MIN_MOVES = 5;
    public static final double ACPL_OFFSET = 10;
    /** Default fit; src/assets/rating-calibration.json (written by ai.ratingCalibration) overrides it. */
    static final double DEFAULT_A = 3500, DEFAULT_B = -400; // PLACEHOLDER until fitted
    private static double[] fit;

    /** Elo estimate for one game's average centipawn loss. */
    public static int ratingFor(double acpl) {
        double[] ab = coefficients();
        return (int) Math.round(Math.max(100, Math.min(3400, ab[0] + ab[1] * Math.log(acpl + ACPL_OFFSET))));
    }

    private static synchronized double[] coefficients() {
        if (fit == null) {
            fit = new double[]{DEFAULT_A, DEFAULT_B};
            Path file = Path.of(paths.ASSETS, "rating-calibration.json");
            try {
                if (Files.isRegularFile(file)) {
                    Map<String, Object> m = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
                    if (m.get("a") instanceof Number a && m.get("b") instanceof Number b) fit = new double[]{a.doubleValue(), b.doubleValue()};
                }
            } catch (Exception e) {
                System.err.println("Ignoring " + file + ": " + e.getMessage());
            }
        }
        return fit;
    }

    /** One side's result for one game. */
    public record side(int moves, double accuracy, double acpl, int rating) {}

    /** Collects per-move losses for one side. Scores are centipawns or Suggestion mate scores, mover's view. */
    public static final class tally {
        private final List<Double> accuracies = new ArrayList<>();
        private double lossSum;

        public void add(int bestBefore, int after, boolean playedBest) {
            int best = moveClassifier.clip(bestBefore), got = playedBest ? best : moveClassifier.clip(after);
            lossSum += Math.max(0, best - got);
            double drop = Math.max(0, winPercent(best) - winPercent(got));
            accuracies.add(Math.max(0, Math.min(100, 103.1668 * Math.exp(-0.04354 * drop) - 3.1669)));
        }

        public side result() {
            int n = accuracies.size();
            if (n == 0) return new side(0, 0, 0, 0);
            double mean = 0, harmonic = 0;
            for (double a : accuracies) { mean += a; harmonic += 1 / Math.max(a, 1); }
            mean /= n;
            harmonic = n / harmonic;
            double acpl = lossSum / n;
            int rating = ratingFor(acpl);
            return new side(n, (mean + harmonic) / 2, acpl, rating);
        }
    }

    /** Lichess: winning chances (0-100) for the side to move at this centipawn score. */
    static double winPercent(int cp) {
        return 50 + 50 * (2 / (1 + Math.exp(-0.00368208 * cp)) - 1);
    }

    // ---------------------------------------------------------------- stored ratings

    private static Path file() { return paths.savesDir().resolve("ratings.json"); }

    /** True for names that identify a real player or an engine (not "White", "Black", "?" or "Opponent"). */
    public static boolean rateable(String name) {
        return name != null && !name.isBlank() && !List.of("White", "Black", "?", "Opponent").contains(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try {
            if (Files.isRegularFile(file())) return Json.parseObject(Files.readString(file(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("Could not read ratings: " + e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    /** Records (or replaces) this game's rating for a player. */
    @SuppressWarnings("unchecked")
    public static synchronized void save(String player, String gameId, side s) {
        if (!rateable(player) || s.moves() < MIN_MOVES) return;
        Map<String, Object> all = load();
        List<Object> games = new ArrayList<>(all.get(player) instanceof List<?> l ? (List<Object>) l : List.of());
        games.removeIf(o -> o instanceof Map<?, ?> m && gameId.equals(m.get("game")));
        games.add(Json.map("game", gameId, "rating", (long) s.rating(), "accuracy", Math.round(s.accuracy() * 10) / 10.0,
                "moves", (long) s.moves()));
        all.put(player, games);
        try {
            Files.createDirectories(file().getParent());
            Path tmp = file().resolveSibling("ratings.json.tmp");
            Files.writeString(tmp, Json.pretty(all) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("Could not save ratings: " + e.getMessage());
        }
    }

    /** Average of the last RECENT game ratings; null before the first analysed game. */
    public static synchronized Integer overall(String player) { return overallOf(load().get(player)); }

    public static synchronized int games(String player) {
        return load().get(player) instanceof List<?> l ? l.size() : 0;
    }

    private static Integer overallOf(Object entry) {
        if (!(entry instanceof List<?> games) || games.isEmpty()) return null;
        double sum = 0;
        int n = 0;
        for (int i = Math.max(0, games.size() - RECENT); i < games.size(); i++)
            if (games.get(i) instanceof Map<?, ?> m && m.get("rating") instanceof Number r) { sum += r.doubleValue(); n++; }
        return n == 0 ? null : (int) Math.round(sum / n);
    }

    /** "Neural small-0000001  ~1480" for menus; the name alone if it has no rating yet. */
    public static String label(String player) {
        Integer r = overall(player);
        return r == null ? player : player + "  ~" + r;
    }
}
