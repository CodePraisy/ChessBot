package ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import handlers.Json;

/**
 * Connects the engines at startup. All of them run natively in Java (searchEngine):
 *  1. TensorFlow models trained on Lichess data (engine/models/tf/<size>/), best-trained first;
 *  2. "Classic engine", a hand-written evaluation that needs no training;
 *  3. Stockfish at several fixed ratings (engine/stockfish/, downloaded automatically on first start);
 *  4. the ten survivors of the original self-play gym (engine/models/gym/champions.json).
 * The first one is the default opponent. -Dchessbot.pythonGym=true runs the gym models through the
 * old Python bridge instead.
 */
public final class engineBootstrap {
    private engineBootstrap() {}

    public static void install(String[] args) {
        Path models = Path.of(System.getProperty("chessbot.models", "engine/models/tf"));
        Path gym = Path.of(System.getProperty("chessbot.gym", "engine/models/gym"));
        for (moveProvider p : trainedModels(models)) register(p);
        register(classic());
        Path stockfish = uciEngine.find();
        if (stockfish != null) installStockfish(stockfish);
        else if (!"false".equals(System.getProperty("chessbot.stockfishDownload"))) {
            Thread t = new Thread(() -> { Path p = stockfishDownload.fetch(); if (p != null) installStockfish(p); }, "stockfish-download");
            t.setDaemon(true);
            t.start(); // first start only: the Stockfish opponents appear in the menus once the download finishes
        }
        if (Boolean.getBoolean("chessbot.pythonGym")) installPythonFrom(gym);
        else installFrom(gym);
    }

    /** Stockfish 1320 ... full strength as opponents; full strength also analyses games in Review. */
    public static void installStockfish(Path exe) {
        for (uciEngine e : uciEngine.levels(exe)) {
            engineRegistry.addEngine(e);
            if (e.elo() == null) engineRegistry.registerAnalysis(e); // best judge of moves for Review and ratings
        }
    }

    private static void register(moveProvider p) {
        if (!engineRegistry.hasMoveProvider()) engineRegistry.useEngine(p);
        else engineRegistry.addEngine(p);
    }

    public static nativeEngine classic() {
        return new nativeEngine("Classic engine", "hand-written evaluation, no training", neuralNet::classic);
    }

    /** Every exported TensorFlow model under root, most trained first. */
    public static List<nativeEngine> trainedModels(Path root) {
        List<Map<String, Object>> found = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.toList()) {
                Path meta = dir.resolve("model.json");
                if (!Files.isRegularFile(meta) || !Files.isRegularFile(dir.resolve("weights.npz"))) continue;
                try {
                    Map<String, Object> m = Json.parseObject(Files.readString(meta));
                    m.put("_dir", dir.toString());
                    found.add(m);
                } catch (Exception e) {
                    System.err.println("Skipping model " + dir + ": " + e.getMessage());
                }
            }
        } catch (Exception noModelsYet) {
            return List.of();
        }
        found.sort(Comparator.<Map<String, Object>>comparingLong(m -> -Json.num(m, "trained_positions", 0))
                .thenComparingLong(m -> -Json.num(m, "parameters", 0)));
        List<nativeEngine> out = new ArrayList<>();
        for (Map<String, Object> m : found) {
            Path dir = Path.of((String) m.get("_dir"));
            String name = Json.str(m, "name", "Neural " + dir.getFileName());
            long positions = Json.num(m, "trained_positions", 0);
            String about = String.format("%s, %,d parameters, trained on %,d positions", Json.str(m, "size", "?"),
                    Json.num(m, "parameters", 0), positions);
            out.add(new nativeEngine(name, about, () -> neuralNet.mlp(dir)));
        }
        return out;
    }

    /** Loads the gym's ten survivors as native engines. A custom gym directory is useful for tests. */
    public static void installFrom(Path gym) {
        for (String[] model : gymModels(gym)) {
            Path checkpoint = Path.of(model[1]);
            register(new nativeEngine(model[0], "self-play gym survivor", () -> neuralNet.gym(checkpoint)));
        }
    }

    /** The original behaviour: each gym survivor runs in its own Python process (engine/bridge.py). */
    public static void installPythonFrom(Path gym) {
        String python = pythonExecutable();
        for (String[] model : gymModels(gym))
            register(new externalProcessProvider(model[0], python, "engine/bridge.py", "--checkpoint", model[1]));
    }

    /** [id, checkpoint] pairs from champions.json; empty when there is no (valid) gym. */
    private static List<String[]> gymModels(Path gym) {
        Path root = gym.toAbsolutePath().normalize();
        Path manifest = root.resolve("champions.json");
        List<String[]> out = new ArrayList<>();
        if (!Files.isRegularFile(manifest)) return out;
        try {
            Map<String, Object> state = Json.parseObject(Files.readString(manifest));
            var entries = Json.list(state, "models");
            if (entries.size() != 10) throw new IllegalArgumentException("Gym manifest must contain ten survivors");
            // Validate every path first so a partial manifest is refused as a whole.
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> model)) throw new IllegalArgumentException("Invalid gym model entry");
                if (!(model.get("checkpoint") instanceof String filename)) throw new IllegalArgumentException("Missing checkpoint path");
                Path checkpoint = root.resolve(filename).normalize();
                if (!checkpoint.startsWith(root) || !Files.isRegularFile(checkpoint))
                    throw new IllegalArgumentException("Missing or invalid model checkpoint");
                if (!(model.get("id") instanceof String id)) throw new IllegalArgumentException("Missing model id");
                out.add(new String[]{id, checkpoint.toString()});
            }
        } catch (Exception error) {
            System.err.println("Could not load training gym: " + error.getMessage());
            out.clear();
        }
        return out;
    }

    public static String pythonExecutable() {
        String configured = System.getProperty("chessbot.python");
        if (configured != null && !configured.isBlank()) return configured;
        for (String path : new String[]{"engine/venv/Scripts/python.exe", "engine/venv/bin/python"})
            if (Files.isRegularFile(Path.of(path))) return path;
        return "python";
    }
}
