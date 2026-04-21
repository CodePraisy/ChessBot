package handlers;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import main.mainWindow;

public class configHandler {
    private static final String CONFIG_PATH = "config.json";

    public static String loadVersion() {
        try (InputStream input = mainWindow.class.getResourceAsStream(CONFIG_PATH)) {
            if (input == null) {
                System.err.println(CONFIG_PATH + " not found");
                return "v?";
            }
            // Read the entire file content as a string
            Scanner scanner = new Scanner(input, StandardCharsets.UTF_8.name());
            String jsonText = scanner.useDelimiter("\\A").next();
            scanner.close();

            // Very simple extract of the "version" value using regex
            String version = jsonText.replaceAll("(?s).*\"version\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            return "v" + version;
        } catch (Exception e) {
            e.printStackTrace();
            return "v?";
        }
    }

    public static boolean getConfigBoolean(String keyPath) {
        try (InputStream input = mainWindow.class.getResourceAsStream(CONFIG_PATH)) {
            if (input == null) {
                System.err.println("config.json not found");
                return false;
            }

            String json;
            try (Scanner scanner = new Scanner(input, StandardCharsets.UTF_8.name())) {
                json = scanner.useDelimiter("\\A").next();
            }

            String pattern;

            if (keyPath.contains(".")) {
                // Handle nested keys like "audio.backgroundAudio"
                String[] keys = keyPath.split("\\.");
                pattern = String.format("\"%s\"\\s*:\\s*\\{[^}]*?\"%s\"\\s*:\\s*(true|false)", 
                                        Pattern.quote(keys[0]), Pattern.quote(keys[1]));
            } else {
                // Handle flat keys like "soundEffects"
                pattern = String.format("\"%s\"\\s*:\\s*(true|false)", Pattern.quote(keyPath));
            }

            Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(json);
            if (matcher.find()) {
                return Boolean.parseBoolean(matcher.group(1));
            }

            return false; // default if key not found
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean setConfigBoolean(String keyPath, boolean newValue) {
        Path configPath = Paths.get("src/main/config.json");

        try {
            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            String updatedJson = null;

            if (keyPath.contains(".")) {
                String[] parts = keyPath.split("\\.");
                if (parts.length != 2) {
                    System.err.println("Only supports one level of nesting: e.g., audio.backgroundAudio");
                    return false;
                }

                String parent = Pattern.quote(parts[0]);
                String child = Pattern.quote(parts[1]);

                // Match: "parent": { ... "child": true/false ... }
                String pattern = String.format("(\"%s\"\\s*:\\s*\\{[^}]*?)(\"%s\"\\s*:\\s*)(true|false)", parent, child);
                Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                Matcher matcher = regex.matcher(json);

                if (matcher.find()) {
                    updatedJson = matcher.replaceFirst("$1$2" + newValue);
                } else {
                    System.err.println("Nested key path not found: " + keyPath);
                    return false;
                }
            } else {
                // Match: "key": true/false
                String key = Pattern.quote(keyPath);
                String pattern = String.format("(?<=\"%s\"\\s*:\\s*)(true|false)", key);
                Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                Matcher matcher = regex.matcher(json);

                if (matcher.find()) {
                    updatedJson = matcher.replaceFirst(Boolean.toString(newValue));
                } else {
                    System.err.println("Key not found: " + keyPath);
                    return false;
                }
            }

            Files.writeString(configPath, updatedJson, StandardCharsets.UTF_8);
            return true;

        } catch (IOException e) {
            System.err.println("Failed to read/write config: " + configPath);
            e.printStackTrace();
            return false;
        }
    }
}
