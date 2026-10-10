package saves;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import handlers.Json;
import handlers.paths;

/**
 * Reads and writes saved games. Writes are atomic (temp file + rename). A file that cannot be parsed,
 * or whose moves are illegal, is renamed to .corrupt and reported via {@link #takeProblems()}; it never crashes the app.
 */
public final class saveManager {
    private saveManager() {}

    private static final List<String> problems = new ArrayList<>();

    /** Messages about corrupt files found since the last call. */
    public static synchronized List<String> takeProblems() {
        List<String> l = new ArrayList<>(problems);
        problems.clear();
        return l;
    }

    public static void save(savedGame g) throws IOException { save(paths.savesDir(), g); }

    /** Saves into a specific folder (multiplayer tests run two peers with separate folders). */
    public static synchronized void save(Path dir, savedGame g) throws IOException {
        Files.createDirectories(dir);
        writeAtomic(dir.resolve(g.gameId + ".json"), Json.pretty(g.toJson()) + "\n");
    }

    public static boolean trySave(savedGame g) { return trySave(paths.savesDir(), g); }

    /** Save that reports failures on stderr instead of throwing (for autosaves). */
    public static boolean trySave(Path dir, savedGame g) {
        try {
            save(dir, g);
            return true;
        } catch (IOException e) {
            System.err.println("Autosave failed for " + g.gameId + ": " + e.getMessage());
            return false;
        }
    }

    public static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static boolean exists(String gameId) { return Files.isRegularFile(paths.saveFile(gameId)); }

    /** Loads a game; null if it does not exist or is corrupt (corrupt files are renamed and reported). */
    public static savedGame load(String gameId) { return load(paths.savesDir(), gameId); }

    public static synchronized savedGame load(Path dir, String gameId) {
        Path f = dir.resolve(gameId + ".json");
        return Files.isRegularFile(f) ? read(f) : null;
    }

    private static savedGame read(Path f) {
        try {
            savedGame g = savedGame.fromJson(Json.parseObject(Files.readString(f, StandardCharsets.UTF_8)));
            g.toGameState(); // validates every move
            return g;
        } catch (Exception e) {
            markCorrupt(f, e.getMessage());
            return null;
        }
    }

    private static void markCorrupt(Path f, String why) {
        String name = f.getFileName().toString().replaceAll("\\.json$", "");
        Path target = f.resolveSibling(name + ".corrupt");
        if (Files.exists(target)) target = f.resolveSibling(name + "." + System.currentTimeMillis() + ".corrupt");
        try {
            Files.move(f, target);
            problems.add(f.getFileName() + " could not be read (" + why + ") and was renamed to " + target.getFileName());
        } catch (IOException e) {
            problems.add(f.getFileName() + " could not be read (" + why + ")");
        }
    }

    /** All readable games, newest first. Skips profile.json, backups and temp files. */
    public static synchronized List<savedGame> list() {
        List<savedGame> out = new ArrayList<>();
        Path dir = paths.savesDir();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : ds) {
                String n = f.getFileName().toString();
                if (n.equals("profile.json") || n.contains(".bak.")) continue;
                savedGame g = read(f);
                if (g != null) out.add(g);
            }
        } catch (IOException e) {
            problems.add("Could not list saves: " + e.getMessage());
        }
        out.sort(Comparator.comparing((savedGame g) -> g.updated).reversed());
        return out;
    }

    public static savedGame mostRecentUnfinished(Set<String> modes) {
        for (savedGame g : list()) if (!g.isFinished() && modes.contains(g.mode)) return g;
        return null;
    }

    public static synchronized boolean delete(String gameId) {
        try {
            return Files.deleteIfExists(paths.saveFile(gameId));
        } catch (IOException e) {
            problems.add("Could not delete " + gameId + ": " + e.getMessage());
            return false;
        }
    }

    /** Copies the current file to <gameId>.bak.<timestamp>.json before it is overwritten by an agreed state. */
    public static Path backup(String gameId) throws IOException { return backup(paths.savesDir(), gameId); }

    public static synchronized Path backup(Path dir, String gameId) throws IOException {
        Path f = dir.resolve(gameId + ".json");
        if (!Files.exists(f)) return null;
        String stamp = Instant.now().toString().replace(":", "-");
        Path b = f.resolveSibling(gameId + ".bak." + stamp + ".json");
        Files.copy(f, b, StandardCopyOption.REPLACE_EXISTING);
        return b;
    }
}
