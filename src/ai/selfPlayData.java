package ai;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Random;

import handlers.Json;

/**
 * Self-play refinement data: an engine plays itself and every position is scored by its own deeper search.
 * Output uses the Lichess evaluation format, so the usual pipeline turns it into training data:
 *
 *   java -cp "bin;src" ai.selfPlayData --engine "Neural medium" --games 200 --depth 8
 *   python engine/lichess_data.py evals --source engine/data/selfplay.jsonl --out engine/data/selfplay --min-depth 1
 *   python engine/train_tf.py --size medium --resume --data engine/data/selfplay --lr 0.0002
 *
 * The search looks further ahead than the network, so its scores are better targets than the network's own
 * guesses. Appends to the output file; never touches any model.
 */
public final class selfPlayData {
    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        String name = opt.getOrDefault("engine", "Classic engine");
        int games = Integer.parseInt(opt.getOrDefault("games", "100"));
        int depth = Integer.parseInt(opt.getOrDefault("depth", "8"));
        Path out = Path.of(opt.getOrDefault("out", "engine/data/selfplay.jsonl"));
        long seed = Long.parseLong(opt.getOrDefault("seed", String.valueOf(System.currentTimeMillis())));
        nativeEngine engine = null;
        for (nativeEngine e : strengthTest.engines().values()) if (e.name().equalsIgnoreCase(name)) engine = e;
        if (engine == null) throw new IllegalArgumentException("No engine named \"" + name + "\" (java ai.strengthTest --list)");
        Files.createDirectories(out.toAbsolutePath().getParent());
        Random rnd = new Random(seed);
        int positions = 0;
        System.out.printf("%s self-play: %d games, depth %d -> %s%n", engine.name(), games, depth, out);
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (int game = 0; game < games; game++) {
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
                    searchEngine.result r = engine.think(b, 60_000, depth);
                    if (r.move() == 0) break;
                    String fen = strengthFen(b);
                    int white = r.score() * b.side;
                    Map<String, Object> pv = Math.abs(r.score()) >= searchEngine.MATE - 500
                            ? Json.map("mate", (long) (white > 0 ? 1 : -1) * Math.max(1, (searchEngine.MATE - Math.abs(r.score()) + 1) / 2),
                                    "line", fastBoard.toMove(r.move()).uci())
                            : Json.map("cp", (long) white, "line", fastBoard.toMove(r.move()).uci());
                    w.write(Json.write(Json.map("fen", fen, "evals", List.of(Json.map("pvs", List.of(pv), "depth", (long) depth)))));
                    w.write('\n');
                    positions++;
                    b.make(r.move());
                }
                w.flush();
                System.out.printf("  game %d done, %d positions so far%n", game + 1, positions);
            }
        }
        System.out.printf("Wrote %d scored positions to %s%n", positions, out);
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
