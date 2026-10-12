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

/**
 * Self-play refinement data: an engine plays itself and every position is scored by its own deeper search.
 * Output uses the Lichess evaluation format, so the usual pipeline turns it into training data:
 *
 *   java -cp "bin;src" ai.selfPlayData --engine "Neural medium" --games 200 --depth 8
 *   java -cp "bin;src" ai.selfPlayData --model engine/models/tf/medium --games 200 --depth 8 --threads 4
 *   python engine/lichess_data.py evals --source engine/data/selfplay.jsonl --out engine/data/selfplay --min-depth 1
 *   python engine/train_tf.py --size medium --resume --data engine/data/selfplay --lr 0.0002
 *
 * The search looks further ahead than the network, so its scores are better targets than the network's own
 * guesses. Appends to the output file; never touches any model. engine/gym_tf.py runs this automatically.
 * Options: --time ms caps each move (default 60000), --threads N plays several games at once, --seed N.
 */
public final class selfPlayData {
    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        int games = Integer.parseInt(opt.getOrDefault("games", "100"));
        int depth = Integer.parseInt(opt.getOrDefault("depth", "8"));
        long time = Long.parseLong(opt.getOrDefault("time", "60000"));
        int threads = Math.max(1, Integer.parseInt(opt.getOrDefault("threads", "1")));
        Path out = Path.of(opt.getOrDefault("out", "engine/data/selfplay.jsonl"));
        long seed = Long.parseLong(opt.getOrDefault("seed", String.valueOf(System.currentTimeMillis())));
        nativeEngine engine = null;
        if (opt.containsKey("model")) {
            Path dir = Path.of(opt.get("model"));
            engine = new nativeEngine("model " + dir.getFileName(), "trained model", () -> neuralNet.mlp(dir));
        } else {
            String name = opt.getOrDefault("engine", "Classic engine");
            for (nativeEngine e : strengthTest.engines().values()) if (e.name().equalsIgnoreCase(name)) engine = e;
            if (engine == null) throw new IllegalArgumentException("No engine named \"" + name + "\" (java ai.strengthTest --list)");
        }
        if (out.toAbsolutePath().getParent() != null) Files.createDirectories(out.toAbsolutePath().getParent());
        System.out.printf("%s self-play: %d games, depth %d, %d thread(s) -> %s%n", engine.name(), games, depth, threads, out);
        AtomicInteger positions = new AtomicInteger(), finished = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            List<Future<?>> jobs = new ArrayList<>();
            ThreadLocal<searchEngine> searches = new ThreadLocal<>();
            nativeEngine source = engine;
            for (int game = 0; game < games; game++) {
                long gameSeed = seed * 1_000_003L + game;
                jobs.add(pool.submit(() -> {
                    if (searches.get() == null) searches.set(source.fresh());
                    List<String> lines = playGame(searches.get(), new Random(gameSeed), depth, time);
                    synchronized (w) {
                        for (String line : lines) { w.write(line); w.write('\n'); }
                        w.flush();
                    }
                    int total = positions.addAndGet(lines.size());
                    System.out.printf("  game %d done, %d positions so far%n", finished.incrementAndGet(), total);
                    return null;
                }));
            }
            for (Future<?> job : jobs) job.get();
        } finally {
            pool.shutdownNow();
        }
        System.out.printf("Wrote %d scored positions to %s%n", positions.get(), out);
    }

    /** One game from a random opening; returns a Lichess-format JSON line per position. */
    static List<String> playGame(searchEngine search, Random rnd, int depth, long time) {
        List<String> lines = new ArrayList<>();
        fastBoard b = fastBoard.fromFen(rules.Position.START_FEN);
        int[] list = new int[256];
        // varied openings: 4-9 random legal plies
        int randomPlies = 4 + rnd.nextInt(6);
        for (int ply = 0; ply < randomPlies; ply++) {
            int n = b.generate(list, false), pick = 0;
            for (int k = 0; k < n * 2 && pick == 0; k++) { int m = list[rnd.nextInt(n)]; if (b.legal(m)) pick = m; }
            if (pick == 0) break;
            b.make(pick);
        }
        for (int ply = 0; ply < 300; ply++) {
            if (b.halfmove >= 100 || b.threefold() || b.insufficientMaterial()) break;
            searchEngine.result r = search.think(b, time, depth);
            if (r.move() == 0) break;
            int white = r.score() * b.side;
            Map<String, Object> pv = Math.abs(r.score()) >= searchEngine.MATE - 500
                    ? Json.map("mate", (long) (white > 0 ? 1 : -1) * Math.max(1, (searchEngine.MATE - Math.abs(r.score()) + 1) / 2),
                            "line", fastBoard.toMove(r.move()).uci())
                    : Json.map("cp", (long) white, "line", fastBoard.toMove(r.move()).uci());
            lines.add(Json.write(Json.map("fen", strengthFen(b), "evals", List.of(Json.map("pvs", List.of(pv), "depth", (long) r.depth())))));
            b.make(r.move());
        }
        return lines;
    }

    /** FEN of the search board (move counters are not used by training). */
    static String strengthFen(fastBoard f) {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < 8; row++) {
            int empty = 0;
            for (int col = 0; col < 8; col++) {
                int p = f.b[row * 8 + col];
                if (p == 0) { empty++; continue; }
                if (empty > 0) { sb.append(empty); empty = 0; }
                char c = " pnbrqk".charAt(Math.abs(p));
                sb.append(p > 0 ? Character.toUpperCase(c) : c);
            }
            if (empty > 0) sb.append(empty);
            if (row < 7) sb.append('/');
        }
        int c = f.castling;
        String rights = ((c & fastBoard.WK) != 0 ? "K" : "") + ((c & fastBoard.WQ) != 0 ? "Q" : "")
                + ((c & fastBoard.BK) != 0 ? "k" : "") + ((c & fastBoard.BQ) != 0 ? "q" : "");
        String ep = f.ep < 0 ? "-" : rules.Position.squareName(f.ep);
        return sb + " " + (f.side == fastBoard.WHITE ? "w" : "b") + " " + (rights.isEmpty() ? "-" : rights) + " " + ep + " " + f.halfmove + " 1";
    }
}
