package ai;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import handlers.Json;
import review.gameRating;
import rules.Move;
import rules.Position;

/**
 * Calibrates the game rating formula against Stockfish's fixed-Elo levels.
 * Stockfish plays itself at known ratings; every game is analysed exactly the way Review analyses a game
 * (full-strength Stockfish, top three lines, 1.5 s a position); then the formula
 *     rating = a + b * ln(ACPL + 10)
 * is fitted so those games come out at the right Elo. The result goes to src/assets/rating-calibration.json,
 * which the game uses from then on.
 *
 *   java -cp "bin;src" ai.ratingCalibration --games 40 --threads 4
 *   java -cp "bin;src" ai.ratingCalibration --fit-only            (refit from the saved games)
 *
 * Options: --levels 1320,1500,... --move-ms 500 (thinking time while playing) --analysis-ms 1500
 * --stockfish path. Each game appends a line to engine/data/rating-calibration.csv, so Ctrl+C keeps what is done
 * and running again adds more games.
 */
public final class ratingCalibration {
    static final String[] OPENINGS = {"e2e4 e7e5", "e2e4 c7c5", "d2d4 d7d5", "d2d4 g8f6", "c2c4 e7e5", "g1f3 d7d5",
            "e2e4 e7e6", "e2e4 c7c6", "d2d4 g8f6 c2c4 e7e6", "e2e4 e7e5 g1f3 b8c6", "d2d4 d7d5 c2c4 c7c6", "e2e4 d7d6"};

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new java.util.LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i].replaceFirst("^--", "");
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) opt.put(key, args[++i]); else opt.put(key, "true");
        }
        Path csv = Path.of(opt.getOrDefault("out", "engine/data/rating-calibration.csv"));
        if (!opt.containsKey("fit-only")) play(opt, csv);
        fit(csv, Path.of(opt.getOrDefault("save", "src/assets/rating-calibration.json")));
    }

    static void play(Map<String, String> opt, Path csv) throws Exception {
        Path exe = opt.containsKey("stockfish") ? Path.of(opt.get("stockfish")) : uciEngine.find();
        if (exe == null || !Files.isRegularFile(exe)) throw new IllegalArgumentException("No Stockfish: run python engine/get_stockfish.py or pass --stockfish");
        int games = Integer.parseInt(opt.getOrDefault("games", "40"));
        int threads = Integer.parseInt(opt.getOrDefault("threads", String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors() / 2))));
        long moveMs = Long.parseLong(opt.getOrDefault("move-ms", "500")), analysisMs = Long.parseLong(opt.getOrDefault("analysis-ms", "1500"));
        int[] levels = java.util.Arrays.stream(opt.getOrDefault("levels", "1320,1500,1700,1900,2100,2300,2500,2700,2900").split(","))
                .mapToInt(s -> Integer.parseInt(s.trim())).toArray();
        Random rnd = new Random(Long.parseLong(opt.getOrDefault("seed", String.valueOf(System.nanoTime()))));
        if (csv.toAbsolutePath().getParent() != null) Files.createDirectories(csv.toAbsolutePath().getParent());
        boolean fresh = !Files.exists(csv);
        System.out.printf("Calibration: %d games, levels %s, %d thread(s), %d ms a move, %d ms analysis -> %s%n",
                games, java.util.Arrays.toString(levels), threads, moveMs, analysisMs, csv);
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (BufferedWriter w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (fresh) { w.write("elo,opponent,colour,moves,acpl,accuracy,result\n"); w.flush(); }
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < games; i++) {
                // spread the games evenly over the levels; opponents are within two levels, so games stay competitive
                int a = i % levels.length, b = Math.max(0, Math.min(levels.length - 1, a + rnd.nextInt(5) - 2));
                int white = levels[a], black = levels[b];
                if (rnd.nextBoolean()) { white = levels[b]; black = levels[a]; }
                String opening = OPENINGS[rnd.nextInt(OPENINGS.length)];
                int whiteElo = white, blackElo = black;
                jobs.add(pool.submit(() -> {
                    String[] rows = game(exe, whiteElo, blackElo, opening, moveMs, analysisMs);
                    synchronized (w) { w.write(rows[0]); w.write(rows[1]); w.flush(); }
                    System.out.printf("  game %d: %s", done.incrementAndGet(), rows[2]);
                    return null;
                }));
            }
            for (Future<?> f : jobs) f.get();
        } finally {
            pool.shutdownNow();
        }
    }

    /** Plays and analyses one game; returns the two CSV rows and a progress line. */
    static String[] game(Path exe, int whiteElo, int blackElo, String opening, long moveMs, long analysisMs) throws Exception {
        uciEngine white = new uciEngine("w", exe, whiteElo), black = new uciEngine("b", exe, blackElo);
        uciEngine judge = new uciEngine("judge", exe, null, analysisMs);
        try {
            Position pos = Position.start();
            List<Move> moves = new ArrayList<>();
            List<Position> seen = new ArrayList<>(List.of(pos));
            for (String u : opening.split(" ")) { Move m = Move.fromUci(u); moves.add(m); pos = pos.play(m); seen.add(pos); }
            searchLimits limits = new searchLimits(10, 64, moveMs);
            java.util.Map<String, Integer> repeats = new java.util.HashMap<>();
            String result = "1/2-1/2";
            while (moves.size() < 300) {
                if (pos.legalMoves().isEmpty()) { result = pos.inCheck() ? (pos.sideToMove() == Position.WHITE ? "0-1" : "1-0") : "1/2-1/2"; break; }
                String key = pos.toFen().replaceAll(" \\d+ \\d+$", "");
                if (repeats.merge(key, 1, Integer::sum) >= 3 || pos.toFen().matches(".* (1\\d\\d|[2-9]\\d\\d) \\d+$")) break;
                Move m = (pos.sideToMove() == Position.WHITE ? white : black).chooseMove(pos, moves, limits).get();
                if (!pos.isLegal(m)) throw new IllegalStateException("Stockfish played an illegal move " + m.uci());
                moves.add(m);
                pos = pos.play(m);
                seen.add(pos);
            }
            // analyse like Review: best line before each move against the position after it
            List<List<Suggestion>> lines = new ArrayList<>();
            for (Position p : seen) lines.add(p.legalMoves().isEmpty() ? null : judge.analyze(p, 3).get());
            gameRating.tally[] tally = {new gameRating.tally(), new gameRating.tally()};
            int first = opening.split(" ").length; // book moves don't count
            for (int i = first; i < moves.size(); i++) {
                Suggestion best = lines.get(i).get(0);
                Position next = seen.get(i + 1);
                int after = lines.get(i + 1) == null ? (next.inCheck() ? Suggestion.MATE_SCORE : 0) : -lines.get(i + 1).get(0).moverScore();
                tally[seen.get(i).sideToMove() == Position.WHITE ? 0 : 1].add(best.moverScore(), after, best.move().equals(moves.get(i)));
            }
            gameRating.side w = tally[0].result(), b = tally[1].result();
            return new String[]{
                    String.format(java.util.Locale.ROOT, "%d,%d,white,%d,%.2f,%.2f,%s%n", whiteElo, blackElo, w.moves(), w.acpl(), w.accuracy(), result),
                    String.format(java.util.Locale.ROOT, "%d,%d,black,%d,%.2f,%.2f,%s%n", blackElo, whiteElo, b.moves(), b.acpl(), b.accuracy(), result),
                    String.format(java.util.Locale.ROOT, "%d vs %d %s, %d plies | ACPL %.0f / %.0f%n", whiteElo, blackElo, result, moves.size(), w.acpl(), b.acpl())};
        } finally {
            white.close(); black.close(); judge.close();
        }
    }

    /**
     * Fits elo = a + b * ln(ACPL + 10) to each level's average, not to single games: one game is so noisy that a
     * per-game fit squashes every rating towards the middle. The overall rating averages many games, so it is
     * the average that should land on the right Elo.
     */
    static void fit(Path csv, Path save) throws Exception {
        java.util.TreeMap<Integer, List<Double>> byLevel = new java.util.TreeMap<>();
        int sides = 0;
        for (String line : Files.readAllLines(csv)) {
            String[] f = line.split(",");
            if (f.length < 6 || f[0].equals("elo") || Integer.parseInt(f[3]) < gameRating.MIN_MOVES) continue;
            byLevel.computeIfAbsent(Integer.valueOf(f[0]), k -> new ArrayList<>()).add(Math.log(Double.parseDouble(f[4]) + gameRating.ACPL_OFFSET));
            sides++;
        }
        if (byLevel.size() < 3 || sides < 20) { System.out.println("Only " + sides + " rated sides over " + byLevel.size() + " levels; play more games."); return; }
        List<double[]> pts = new ArrayList<>();
        for (var e : byLevel.entrySet()) pts.add(new double[]{e.getValue().stream().mapToDouble(d -> d).average().orElse(0), e.getKey()});
        double mx = 0, my = 0;
        for (double[] p : pts) { mx += p[0]; my += p[1]; }
        mx /= pts.size(); my /= pts.size();
        double sxy = 0, sxx = 0, syy = 0;
        for (double[] p : pts) { sxy += (p[0] - mx) * (p[1] - my); sxx += (p[0] - mx) * (p[0] - mx); syy += (p[1] - my) * (p[1] - my); }
        double b = sxy / sxx, a = my - b * mx, r2 = sxy * sxy / (sxx * syy);
        System.out.printf(java.util.Locale.ROOT, "Fit over %d game sides, %d levels: rating = %.1f %+.1f * ln(ACPL + %.0f) | R^2 %.3f%n",
                sides, pts.size(), a, b, gameRating.ACPL_OFFSET, r2);
        for (var e : byLevel.entrySet()) {
            double avg = e.getValue().stream().mapToDouble(x -> a + b * x).average().orElse(0);
            System.out.printf("  Stockfish %d -> average game rating %.0f over %d games%n", e.getKey(), avg, e.getValue().size());
        }
        Files.createDirectories(save.toAbsolutePath().getParent());
        Files.writeString(save, Json.pretty(Json.map("a", a, "b", b, "offset", gameRating.ACPL_OFFSET, "samples", (long) sides,
                "levels", (long) pts.size(), "r2", r2, "source", csv.toString())) + "\n");
        System.out.println("Saved " + save + " (the game uses it from the next start)");
    }
}
