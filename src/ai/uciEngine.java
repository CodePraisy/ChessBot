package ai;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rules.Move;
import rules.Position;

/**
 * A UCI engine (Stockfish) as an opponent in the game. The process starts the first time it is asked
 * for a move. With an Elo it plays at that strength (UCI_LimitStrength); Stockfish's lowest setting is 1320.
 * At full strength it also analyses games in Review, which makes game ratings far more reliable.
 * Download Stockfish with: python engine/get_stockfish.py
 */
public final class uciEngine implements moveProvider, analysisProvider {
    private final String name;
    private final Path exe;
    private final Integer elo;
    private final ExecutorService worker;
    private Process process;
    private BufferedReader in;
    private BufferedWriter out;

    public uciEngine(String name, Path exe, Integer elo) {
        this.name = name;
        this.exe = exe;
        this.elo = elo;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "uci-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    /** The Stockfish binary: -Dchessbot.stockfish, else engine/stockfish/stockfish(.exe). Null if missing. */
    public static Path find() {
        String configured = System.getProperty("chessbot.stockfish");
        if (configured != null && !configured.isBlank()) return Files.isRegularFile(Path.of(configured)) ? Path.of(configured) : null;
        for (String p : new String[]{"engine/stockfish/stockfish.exe", "engine/stockfish/stockfish"})
            if (Files.isRegularFile(Path.of(p))) return Path.of(p);
        return null;
    }

    /** Fixed-strength levels plus full strength. */
    public static List<uciEngine> levels(Path exe) {
        return List.of(new uciEngine("Stockfish 1320", exe, 1320), new uciEngine("Stockfish 1600", exe, 1600),
                new uciEngine("Stockfish 2000", exe, 2000), new uciEngine("Stockfish 2500", exe, 2500),
                new uciEngine("Stockfish full strength", exe, null));
    }

    /** The Elo this engine is set to, or null at full strength. */
    public Integer elo() { return elo; }

    @Override public String name() { return name; }
    @Override public boolean isAvailable() { return Files.isRegularFile(exe); }

    private void send(String s) throws IOException { out.write(s); out.write('\n'); out.flush(); }

    private String waitFor(String prefix) throws IOException {
        for (String line; (line = in.readLine()) != null; ) if (line.startsWith(prefix)) return line;
        throw new IOException(name + " exited");
    }

    private void start() throws IOException {
        if (process != null && process.isAlive()) return;
        process = new ProcessBuilder(exe.toAbsolutePath().toString()).redirectErrorStream(true).start();
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
    }

    @Override
    public CompletableFuture<Move> chooseMove(Position pos, List<Move> history, searchLimits limits) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                start();
                StringBuilder s = new StringBuilder();
                if (history != null && !history.isEmpty()) {
                    s.append("position startpos moves");
                    for (Move m : history) s.append(' ').append(m.uci());
                } else s.append("position fen ").append(pos.toFen());
                send(s.toString());
                send("go movetime " + limits.maxTimeMs());
                String[] parts = waitFor("bestmove").split("\\s+");
                if (parts.length < 2 || parts[1].equals("(none)")) throw new IllegalStateException("no legal move");
                return Move.fromUci(parts[1]);
            } catch (IOException e) {
                throw new IllegalStateException(name + ": " + e.getMessage(), e);
            }
        }, worker);
    }

    /** Review analysis: Stockfish's top lines (MultiPV) after about 1.5 seconds. */
    @Override
    public CompletableFuture<List<Suggestion>> analyze(Position pos, int maxLines) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                start();
                send("setoption name MultiPV value " + Math.max(1, maxLines));
                send("position fen " + pos.toFen());
                send("go movetime 1500");
                java.util.Map<Integer, Suggestion> best = new java.util.TreeMap<>();
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.startsWith("bestmove")) break;
                    if (!line.startsWith("info") || !line.contains(" pv ") || !line.contains(" score ")) continue;
                    String[] t = line.split("\\s+");
                    int multipv = 1, depth = 0;
                    Integer cp = null, mate = null;
                    List<Move> pv = new java.util.ArrayList<>();
                    for (int i = 1; i < t.length; i++) {
                        switch (t[i]) {
                            case "depth" -> depth = Integer.parseInt(t[++i]);
                            case "multipv" -> multipv = Integer.parseInt(t[++i]);
                            case "score" -> {
                                if (t[i + 1].equals("cp")) cp = Integer.valueOf(t[i + 2]); else mate = Integer.valueOf(t[i + 2]);
                                i += 2;
                            }
                            case "pv" -> { for (i++; i < t.length; i++) pv.add(Move.fromUci(t[i])); }
                            default -> { }
                        }
                    }
                    if (!pv.isEmpty()) best.put(multipv, new Suggestion(pv.get(0), cp, mate, depth, pv, name));
                }
                send("setoption name MultiPV value 1");
                return new java.util.ArrayList<>(best.values());
            } catch (IOException e) {
                throw new IllegalStateException(name + ": " + e.getMessage(), e);
            }
        }, worker);
    }

    @Override
    public void cancel() {
        try { if (process != null && process.isAlive()) send("stop"); } catch (IOException ignored) { }
    }
}
