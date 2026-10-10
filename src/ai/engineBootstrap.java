package ai;

import java.io.File;

/**
 * THE place where engines are connected. Called once from chessUI.main before the window opens.
 */
public final class engineBootstrap {
    private engineBootstrap() {}

    public static void install(String[] args) {
        String python = new File("engine/venv/Scripts/python.exe").isFile() ? "engine/venv/Scripts/python.exe" : "python";
        externalProcessProvider atlas = pythonModel("Atlas — Balanced", python, "atlas");
        engineRegistry.useEngine(atlas);
        engineRegistry.addEngine(pythonModel("Blitz — Attacking", python, "blitz"));
        engineRegistry.addEngine(pythonModel("Bastion — Positional", python, "bastion"));
    }

    /** One Python process per model keeps concurrent AI-vs-AI games independent. */
    private static externalProcessProvider pythonModel(String displayName, String python, String model) {
        return new externalProcessProvider(displayName, python, "engine/bridge.py", "--model", model);
    }
}
