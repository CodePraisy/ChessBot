package ai;

/**
 * THE place where engines are connected. Called once from chessUI.main before the window opens.
 */
public final class engineBootstrap {
    private engineBootstrap() {}

    public static void install(String[] args) {
        builtInEngine atlas = new builtInEngine(builtInEngine.style.BALANCED);
        engineRegistry.useEngine(atlas);
        engineRegistry.addEngine(new builtInEngine(builtInEngine.style.ATTACKING));
        engineRegistry.addEngine(new builtInEngine(builtInEngine.style.POSITIONAL));

        // ================= PLUG THE REAL ENGINE IN HERE (one line) =================
        // Uncomment and point it at your Python/TensorFlow bridge script (JSON lines over stdin/stdout,
        // contract in docs/ENGINE_HOOK.md). Windows venv example:
        //
        // engineRegistry.useEngine(new externalProcessProvider("ChessBot TF", "engine/venv/Scripts/python.exe", "engine/bridge.py"));
        // ===========================================================================

        // The three built-in models above work without downloads or a Python environment.
        // devRandomEngine remains available to tests that exercise engine error handling.
    }
}
