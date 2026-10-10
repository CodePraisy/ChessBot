package saves;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import handlers.Json;
import handlers.paths;

/** Local player identity for multiplayer: a random playerId plus a display name, kept in saves/profile.json. */
public final class profile {
    public final String playerId;
    public final String name;

    private profile(String playerId, String name) { this.playerId = playerId; this.name = name; }

    /** Returns the stored profile, or null if none exists yet. */
    public static profile load() {
        try {
            if (!Files.isRegularFile(paths.profileFile())) return null;
            Map<String, Object> m = Json.parseObject(Files.readString(paths.profileFile(), StandardCharsets.UTF_8));
            String id = Json.str(m, "playerId", null), name = Json.str(m, "name", null);
            return id == null || name == null ? null : new profile(id, name);
        } catch (Exception e) {
            System.err.println("profile.json unreadable: " + e.getMessage());
            return null;
        }
    }

    /** Creates (or renames) the profile, keeping an existing playerId. */
    public static profile save(String name) throws java.io.IOException {
        profile old = load();
        profile p = new profile(old == null ? UUID.randomUUID().toString() : old.playerId, name.trim());
        Files.createDirectories(paths.savesDir());
        saveManager.writeAtomic(paths.profileFile(), Json.pretty(Json.map("playerId", p.playerId, "name", p.name)) + "\n");
        return p;
    }

    /** For tests and in-process peers. */
    public static profile of(String playerId, String name) { return new profile(playerId, name); }
}
