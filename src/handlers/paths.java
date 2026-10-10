package handlers;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/** One place for every on-disk location. All paths are relative to the project root (the launch directory). */
public final class paths {
    private paths() {}

    public static final String ASSETS = "src/assets";
    public static final Path CONFIG = Paths.get("src/main/config.json");
    public static final String DEFAULT_PIECES = ASSETS + "/chessPieces/256px";
    public static final String SOUND_EFFECTS = ASSETS + "/audio/soundEffects";

    /** Overridable so tests can point saves at a temp folder. */
    private static Path savesDir = Paths.get(ASSETS, "saves");

    public static Path savesDir() { return savesDir; }
    public static void setSavesDir(Path p) { savesDir = p; }

    public static Path saveFile(String gameId) { return savesDir.resolve(gameId + ".json"); }
    public static Path profileFile() { return savesDir.resolve("profile.json"); }

    /** Piece/texture folder from config ("chessPieces"), falling back to the bundled 256px set. */
    public static String piecesDir() {
        String p = configHandler.getConfigString("chessPieces", DEFAULT_PIECES);
        return new File(p).isDirectory() ? p : DEFAULT_PIECES;
    }

    public static File asset(String relative) { return new File(ASSETS, relative); }
}
