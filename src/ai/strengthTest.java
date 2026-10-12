package ai;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import handlers.Json;
import rules.Move;
import rules.Position;
import rules.gameState;

/**
 * Measures playing strength. Read-only: no model is trained or changed. From the project root
 * (after compiling with run.bat / run.sh), on Windows use ; and on macOS/Linux : in -cp:
 *
 *   java -cp "bin;src" ai.strengthTest --list
 *   java -cp "bin;src" ai.strengthTest --engine "Neural small" --vs "Classic engine" --games 20
 *   java -cp "bin;src" ai.strengthTest --engine "Neural small" --vs stockfish --elo 1320   (uses engine/stockfish, or --stockfish path)
 *   java -cp "bin;src" ai.strengthTest --engine "Neural small" --puzzles engine/data/puzzles.csv --limit 400
 *   java -cp "bin;src" ai.strengthTest --league --games 4          (every trained model + Classic, round robin)
 *
 *   java -cp "bin;src" ai.strengthTest --league --models engine/models/tf-gym/population --threads 4 --json results.json
 *
 * Options: --time ms per move (default 1000), --games N (even; colours alternate on paired openings),
 * --models folder of trained models (default engine/models/tf), --no-classic, --threads N (league only).
 */
public final class strengthTest {
    private static final String[] OPENINGS = {
            "e2e4 e7e5 g1f3 b8c6", "e2e4 c7c5 g1f3 d7d6", "d2d4 d7d5 c2c4 e7e6", "d2d4 g8f6 c2c4 g7g6",
            "e2e4 e7e6 d2d4 d7d5", "e2e4 c7c6 d2d4 d7d5", "c2c4 e7e5 b1c3 g8f6", "g1f3 d7d5 g2g3 g8f6",
            "e2e4 e7e5 f1c4 g8f6", "d2d4 g8f6 c2c4 e7e6 b1c3 f8b4", "e2e4 d7d5 e4d5 d8d5", "d2d4 d7d5 g1f3 g8f6 c1f4"};
    private static final int MAX_PLIES = 300;

    interface player extends AutoCloseable {
        String name();
        Move move(gameState g, long timeMs) throws Exception;
        @Override default void close() { }
    }

    record nativePlayer(nativeEngine engine) implements player {
        public String name() { return engine.name(); }
        public Move move(gameState g, long timeMs) {
            searchEngine.result r = engine.think(nativeEngine.boardFor(g.current(), g.moves()), timeMs, 64);
            return r.move() == 0 ? null : fastBoard.toMove(r.move());
        }
    }

    /** A player with its own search, for games running in parallel. */
    record searchPlayer(String name, searchEngine search) implements player {
        public Move move(gameState g, long timeMs) {
            searchEngine.result r = search.think(nativeEngine.boardFor(g.current(), g.moves()), timeMs, 64);
            return r.move() == 0 ? null : fastBoard.toMove(r.move());
        }
    }

    /** Any UCI engine, e.g. Stockfish (optionally limited to an Elo rating). */
    static final class uciPlayer implements player {
        private final Process process;
        private final BufferedReader in;
        private final BufferedWriter out;
        private final String label;

        uciPlayer(String path, Integer elo) throws IOException {
            process = new ProcessBuilder(path).redirectErrorStream(true).start();
            in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            send("uci");
            waitFor("uciok");
            if (elo != null) {
                send("setoption name UCI_LimitStrength value true");
                send("setoption name UCI_Elo value " + elo);
            }
            send("isready");
            waitFor("readyok");
            label = "Stockfish" + (elo != null ? " (Elo " + elo + ")" : " (full strength)");
        }

        private void send(String s) throws IOException { out.write(s); out.write('\n'); out.flush(); }

        private String waitFor(String prefix) throws IOException {
            for (String line; (line = in.readLine()) != null; ) if (line.startsWith(prefix)) return line;
            throw new IOException("UCI engine exited");
        }

        public String name() { return label; }

        public Move move(gameState g, long timeMs) throws IOException {
            StringBuilder s = new StringBuilder("position fen ").append(g.startFen());
            if (g.plyCount() > 0) { s.append(" moves"); for (Move m : g.moves()) s.append(' ').append(m.uci()); }
            send(s.toString());
            send("go movetime " + timeMs);
            String[] parts = waitFor("bestmove").split("\\s+");
            return parts.length > 1 ? Move.fromUci(parts[1]) : null;
        }

        @Override public void close() {
            try { send("quit"); } catch (IOException ignored) { }
            process.destroy();
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = options(args);
        Map<String, nativeEngine> engines = opt.containsKey("models") ? engines(Path.of(opt.get("models")), !opt.containsKey("no-classic"), false) : engines();
        long time = Long.parseLong(opt.getOrDefault("time", "1000"));
        int games = Integer.parseInt(opt.getOrDefault("games", "20"));
        if (opt.containsKey("league")) {
            int threads = Integer.parseInt(opt.getOrDefault("threads", "1"));
            league(engines, games, time, opt.containsKey("with-gym"), threads, opt.containsKey("json") ? Path.of(opt.get("json")) : null);
            return;
        }
        if (opt.containsKey("list") || !opt.containsKey("engine")) {
            System.out.println("Engines (use the name with --engine / --vs):");
            engines.values().forEach(e -> System.out.println("  " + e.name() + "  -  " + e.description()));
            if (!opt.containsKey("list")) System.out.println("\nSee the comment at the top of src/ai/strengthTest.java for examples.");
            return;
        }
        nativeEngine engine = pick(engines, opt.get("engine"));
        if (opt.containsKey("puzzles")) {
            puzzles(engine, Path.of(opt.get("puzzles")), time, Integer.parseInt(opt.getOrDefault("limit", "1000000")));
            return;
        }
        String vs = opt.getOrDefault("vs", "Classic engine");
        try (player a = new nativePlayer(engine);
             player b = vs.equalsIgnoreCase("stockfish")
                     ? new uciPlayer(opt.containsKey("stockfish") || uciEngine.find() == null ? require(opt, "stockfish") : uciEngine.find().toString(), opt.containsKey("elo") ? Integer.valueOf(opt.get("elo")) : null)
                     : new nativePlayer(pick(engines, vs))) {
            match(a, b, games, time, true);
        }
    }

    /** Round robin between the trained models and Classic (optionally the gym too); prints a points table. */
    /**
     * Round robin between the trained models and Classic (optionally the gym too); prints a points table.
     * Pairings run on several threads when asked, each game with its own searches. --json writes the results.
     */
    static void league(Map<String, nativeEngine> engines, int games, long time, boolean withGym, int threads, Path json) throws Exception {
        List<nativeEngine> field = new ArrayList<>();
        for (nativeEngine e : engines.values()) if (withGym || !e.description().startsWith("self-play")) field.add(e);
        Map<String, Double> points = new LinkedHashMap<>();
        field.forEach(e -> points.put(e.name(), 0.0));
        System.out.printf("League: %d engines, %d games per pairing, %d ms per move, %d thread(s)%n", field.size(), games, time, threads);
        List<Map<String, Object>> pairs = java.util.Collections.synchronizedList(new ArrayList<>());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, threads));
        List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
        for (int i = 0; i < field.size(); i++)
            for (int j = i + 1; j < field.size(); j++) {
                nativeEngine a = field.get(i), b = field.get(j);
                jobs.add(pool.submit(() -> {
                    double s = match(new searchPlayer(a.name(), a.fresh()), new searchPlayer(b.name(), b.fresh()), games, time, false);
                    synchronized (points) {
                        points.merge(a.name(), s * games, Double::sum);
                        points.merge(b.name(), (1 - s) * games, Double::sum);
                    }
                    pairs.add(Json.map("a", a.name(), "b", b.name(), "score", s, "games", (long) games));
                    return null;
                }));
            }
        try { for (var job : jobs) job.get(); }
        finally { pool.shutdownNow(); }
        System.out.println("\nStandings (points; each pairing has " + games + " games):");
        List<Map.Entry<String, Double>> table = new ArrayList<>(points.entrySet());
        table.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        table.forEach(e -> System.out.printf("  %-24s %5.1f%n", e.getKey(), e.getValue()));
        if (json != null) {
            List<Object> standings = new ArrayList<>();
            for (var e : table) standings.add(Json.map("name", e.getKey(), "points", e.getValue(), "games", (long) games * (field.size() - 1)));
            if (json.toAbsolutePath().getParent() != null) Files.createDirectories(json.toAbsolutePath().getParent());
            Files.writeString(json, Json.write(Json.map("standings", standings, "pairs", new ArrayList<Object>(pairs),
                    "games_per_pairing", (long) games, "ms_per_move", time)));
        } else System.out.println("The game picks the most-trained model as the default; choose any of them in the AI menus.");
    }

    static Map<String, nativeEngine> engines() {
        return engines(Path.of(System.getProperty("chessbot.models", "engine/models/tf")), true, true);
    }

    /** Trained models in one folder, optionally with Classic and the old gym survivors. */
    static Map<String, nativeEngine> engines(Path models, boolean withClassic, boolean withGym) {
        Map<String, nativeEngine> out = new LinkedHashMap<>();
        for (nativeEngine e : engineBootstrap.trainedModels(models)) out.put(e.name(), e);
        if (withClassic) { nativeEngine classic = engineBootstrap.classic(); out.put(classic.name(), classic); }
        if (!withGym) return out;
        engineRegistry.clear();
        engineBootstrap.installFrom(Path.of(System.getProperty("chessbot.gym", "engine/models/gym")));
        for (moveProvider p : engineRegistry.providers()) if (p instanceof nativeEngine n) out.put(n.name(), n);
        engineRegistry.clear();
        return out;
    }

    private static nativeEngine pick(Map<String, nativeEngine> engines, String name) {
        for (nativeEngine e : engines.values()) if (e.name().equalsIgnoreCase(name)) return e;
        throw new IllegalArgumentException("No engine named \"" + name + "\". Run with --list to see them.");
    }

    private static String require(Map<String, String> opt, String key) {
        String v = opt.get(key);
        if (v == null) throw new IllegalArgumentException("--" + key + " is required");
        return v;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Unexpected argument " + args[i]);
            String key = args[i].substring(2);
            boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
            m.put(key, flag ? "" : args[++i]);
        }
        return m;
    }

    /** Plays paired games (each opening once with each colour). Returns the engine's score fraction. */
    static double match(player engine, player opponent, int games, long time, boolean verbose) throws Exception {
        if (verbose) System.out.printf("%s vs %s: %d games, %d ms per move%n", engine.name(), opponent.name(), games, time);
        double[] scores = new double[games];
        int wins = 0, draws = 0, losses = 0;
        for (int i = 0; i < games; i++) {
            boolean engineWhite = i % 2 == 0;
            List<Move> opening = new ArrayList<>();
            for (String u : OPENINGS[(i / 2) % OPENINGS.length].split(" ")) opening.add(Move.fromUci(u));
            gameState g = gameState.replay(Position.START_FEN, opening);
            String end = null;
            while (!g.isOver() && g.plyCount() < MAX_PLIES) {
                boolean engineTurn = (g.current().sideToMove() == Position.WHITE) == engineWhite;
                player p = engineTurn ? engine : opponent;
                Move m = p.move(g, time);
                if (m == null || !g.current().isLegal(m)) { end = p.name() + " made an illegal move"; g.forceResult(gameState.Status.RESIGNATION, -g.current().sideToMove()); break; }
                g.play(m);
            }
            int me = engineWhite ? Position.WHITE : Position.BLACK;
            double s = !g.isOver() ? 0.5 : g.winner() == 0 ? 0.5 : g.winner() == me ? 1 : 0;
            if (s == 1) wins++; else if (s == 0) losses++; else draws++;
            scores[i] = s;
            if (end == null) end = g.isOver() ? g.terminationText() : "move limit (draw)";
            if (verbose) System.out.printf("  game %2d  %s as %s: %s  (%s, %d plies)%n", i + 1, engine.name(), engineWhite ? "White" : "Black",
                    s == 1 ? "win" : s == 0 ? "loss" : "draw", end, g.plyCount());
        }
        double mean = 0;
        for (double s : scores) mean += s;
        mean /= games;
        double var = 0;
        for (double s : scores) var += (s - mean) * (s - mean);
        double margin = games > 1 ? 1.96 * Math.sqrt(var / (games - 1) / games) : 0.5;
        if (!verbose) {
            System.out.printf("  %s vs %s: +%d =%d -%d%n", engine.name(), opponent.name(), wins, draws, losses);
            return mean;
        }
        System.out.printf("%nResult: +%d =%d -%d  score %.1f%% (95%% range %.1f%% to %.1f%%)%n", wins, draws, losses,
                100 * mean, 100 * Math.max(0, mean - margin), 100 * Math.min(1, mean + margin));
        System.out.printf("Rating difference: %s (range %s to %s)%n", elo(mean), elo(mean - margin), elo(mean + margin));
        System.out.println("With few games the range is wide; play more games before trusting small differences.");
        return mean;
    }

    static String elo(double s) {
        if (s <= 0.001) return "below -800";
        if (s >= 0.999) return "above +800";
        return String.format("%+.0f", -400 * Math.log10(1 / s - 1));
    }

    /** Lichess puzzles: the first move is the opponent's; the engine must find every solution move. */
    static void puzzles(nativeEngine engine, Path csv, long time, int limit) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        String[] header = lines.get(0).split(",");
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) col.put(header[i].trim(), i);
        Map<Integer, int[]> bands = new TreeMap<>();
        int done = 0;
        System.out.printf("%s on puzzles from %s, %d ms per move%n", engine.name(), csv, time);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank() || done >= limit) continue;
            String[] f = line.split(",");
            gameState g = new gameState(f[col.get("FEN")]);
            String[] moves = f[col.get("Moves")].trim().split(" ");
            int rating = Integer.parseInt(f[col.get("Rating")].trim());
            boolean solved = g.play(Move.fromUci(moves[0]));
            for (int i = 1; solved && i < moves.length; i += 2) {
                searchEngine.result r = engine.think(fastBoard.from(g.current()), time, 64);
                Move mine = r.move() == 0 ? null : fastBoard.toMove(r.move());
                Move wanted = Move.fromUci(moves[i]);
                boolean mates = mine != null && g.current().isLegal(mine) && i == moves.length - 1
                        && gameState.replay(g.current().toFen(), List.of(mine)).status() == gameState.Status.CHECKMATE;
                if (mine == null || (!mine.equals(wanted) && !mates)) { solved = false; break; }
                g.play(wanted);
                if (i + 1 < moves.length) g.play(Move.fromUci(moves[i + 1]));
            }
            int band = rating / 400 * 400;
            int[] t = bands.computeIfAbsent(band, k -> new int[2]);
            t[0] += solved ? 1 : 0;
            t[1]++;
            done++;
            if (done % 50 == 0) System.out.printf("  %d puzzles...%n", done);
        }
        int solved = 0;
        for (var e : bands.entrySet()) {
            System.out.printf("  rated %4d-%4d: %3d / %3d solved (%.0f%%)%n", e.getKey(), e.getKey() + 399, e.getValue()[0], e.getValue()[1],
                    100.0 * e.getValue()[0] / e.getValue()[1]);
            solved += e.getValue()[0];
        }
        System.out.printf("Total: %d / %d (%.1f%%)%n", solved, done, done == 0 ? 0 : 100.0 * solved / done);
    }
}
