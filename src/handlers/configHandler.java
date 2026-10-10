package handlers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import main.mainWindow;

/**
 * Reads/writes src/main/config.json through the small Json utility.
 * Reads prefer the file on disk (so saved settings take effect immediately) and fall back to the
 * classpath copy next to mainWindow, as before.
 */
public class configHandler {
    private static final String CONFIG_PATH = "config.json";

    private static Map<String, Object> cache;
    private static long cacheStamp = -1;

    private static synchronized Map<String, Object> load() {
        try {
            Path file = paths.CONFIG;
            if (Files.isRegularFile(file)) {
                long stamp = Files.getLastModifiedTime(file).toMillis();
                if (cache == null || stamp != cacheStamp) {
                    cache = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
                    cacheStamp = stamp;
                }
                return cache;
            }
            if (cache != null) return cache;
            try (InputStream input = mainWindow.class.getResourceAsStream(CONFIG_PATH)) {
                if (input == null) {
                    System.err.println(CONFIG_PATH + " not found");
                    return null;
                }
                cache = Json.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                return cache;
            }
        } catch (Exception e) {
            System.err.println("Could not read config: " + e.getMessage());
            return cache;
        }
    }

    /** "a.b" walks nested objects; a bare key is looked up at the top level, then in any nested object. */
    private static Object lookup(Map<String, Object> root, String keyPath) {
        if (root == null) return null;
        if (keyPath.contains(".")) {
            Object cur = root;
            for (String k : keyPath.split("\\.")) {
                if (!(cur instanceof Map<?, ?> m)) return null;
                cur = m.get(k);
            }
            return cur;
        }
        return find(root, keyPath);
    }

    private static Object find(Map<?, ?> m, String key) {
        if (m.containsKey(key)) return m.get(key);
        for (Object v : m.values()) {
            if (v instanceof Map<?, ?> child) {
                Object r = find(child, key);
                if (r != null) return r;
            }
        }
        return null;
    }

    public static String loadVersion() {
        Object v = lookup(load(), "version");
        return v == null ? "v?" : "v" + v;
    }

    public static boolean getConfigBoolean(String keyPath) {
        Object v = lookup(load(), keyPath);
        return v instanceof Boolean b && b;
    }

    public static String getConfigString(String keyPath, String def) {
        Object v = lookup(load(), keyPath);
        return v == null ? def : v.toString();
    }

    public static boolean setConfigString(String keyPath, String newValue) { return setConfigValue(keyPath, newValue); }

    public static boolean setConfigBoolean(String keyPath, boolean newValue) { return setConfigValue(keyPath, newValue); }

    @SuppressWarnings("unchecked")
    private static synchronized boolean setConfigValue(String keyPath, Object newValue) {
        Path configPath = paths.CONFIG;
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(configPath, StandardCharsets.UTF_8));
            String[] parts = keyPath.split("\\.");
            Map<String, Object> cur = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = cur.get(parts[i]);
                if (next == null) { next = new java.util.LinkedHashMap<String, Object>(); cur.put(parts[i], next); } // new settings group
                if (!(next instanceof Map)) {
                    System.err.println("Nested key path not found: " + keyPath);
                    return false;
                }
                cur = (Map<String, Object>) next;
            }
            String last = parts[parts.length - 1];
            cur.put(last, newValue);
            Path tmp = configPath.resolveSibling("config.json.tmp");
            Files.writeString(tmp, Json.pretty(root) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            cache = null;
            return true;
        } catch (Exception e) {
            System.err.println("Failed to read/write config: " + configPath);
            e.printStackTrace();
            return false;
        }
    }
}
