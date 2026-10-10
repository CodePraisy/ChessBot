package ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import handlers.Json;

/** Loads the gym's ten survivors. All learning and model weights live in Python. */
public final class engineBootstrap {
    private engineBootstrap() {}

    public static void install(String[] args) {
        Path gym = Path.of(System.getProperty("chessbot.gym", "engine/models/gym"));
        installFrom(gym);
    }

    /** A custom gym directory is also useful for isolated integration tests. */
    public static void installFrom(Path gym) {
        Path root = gym.toAbsolutePath().normalize();
        Path manifest = root.resolve("champions.json");
        if (!Files.isRegularFile(manifest)) return;
        try {
            Map<String, Object> state = Json.parseObject(Files.readString(manifest));
            var entries = Json.list(state, "models");
            if (entries.size() != 10) throw new IllegalArgumentException("Gym manifest must contain ten survivors");
            // Validate every path before starting a process so partial manifests are refused.
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> model)) throw new IllegalArgumentException("Invalid gym model entry");
                if (!(model.get("checkpoint") instanceof String filename)) throw new IllegalArgumentException("Missing checkpoint path");
                Path checkpoint = root.resolve(filename).normalize();
                if (!checkpoint.startsWith(root) || !Files.isRegularFile(checkpoint))
                    throw new IllegalArgumentException("Missing or invalid model checkpoint");
                if (!(model.get("id") instanceof String)) throw new IllegalArgumentException("Missing model id");
            }
            String python = pythonExecutable();
            for (Object entry : entries) {
                Map<?, ?> model = (Map<?, ?>) entry;
                String name = (String) model.get("id");
                String checkpoint = root.resolve((String) model.get("checkpoint")).toString();
                externalProcessProvider provider = new externalProcessProvider(name, python, "engine/bridge.py", "--checkpoint", checkpoint);
                if (!engineRegistry.hasMoveProvider()) engineRegistry.useEngine(provider);
                else engineRegistry.addEngine(provider);
            }
        } catch (Exception error) {
            System.err.println("Could not load training gym: " + error.getMessage());
        }
    }

    public static String pythonExecutable() {
        String configured = System.getProperty("chessbot.python");
        if (configured != null && !configured.isBlank()) return configured;
        for (String path : new String[]{"engine/venv/Scripts/python.exe", "engine/venv/bin/python"})
            if (Files.isRegularFile(Path.of(path))) return path;
        return "python";
    }
}
