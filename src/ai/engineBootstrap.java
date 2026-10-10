package ai;

import java.util.Arrays;

/**
 * THE place where engines are connected. Called once from chessUI.main before the window opens.
 */
public final class engineBootstrap {
    private engineBootstrap() {}

    public static void install(String[] args) {
        // ================= PLUG THE REAL ENGINE IN HERE (one line) =================
        // Uncomment and point it at your Python/TensorFlow bridge script (JSON lines over stdin/stdout,
        // contract in docs/ENGINE_HOOK.md). Windows venv example:
        //
        // engineRegistry.useEngine(new externalProcessProvider("ChessBot TF", "engine/venv/Scripts/python.exe", "engine/bridge.py"));
        // ===========================================================================

        // DEV ONLY placeholder: random legal moves + fake suggestions, enabled with --dev-engine
        // (or --dev-engine=flaky to also produce illegal moves, errors and timeouts).
        // Delete this line and devRandomEngine.java when the real engine exists.
        if (Arrays.stream(args).anyMatch(a -> a.startsWith("--dev-engine"))) engineRegistry.useEngine(new devRandomEngine(Arrays.asList(args).contains("--dev-engine=flaky")));
    }
}
