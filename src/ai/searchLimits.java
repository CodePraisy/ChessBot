package ai;

import java.util.Map;

import handlers.Json;

/**
 * How long an engine may think. Engines always play their best move: strength comes from the model,
 * not a difficulty setting. skillLevel is kept only for the Python bridge's wire format.
 */
public record searchLimits(int skillLevel, int maxDepth, long maxTimeMs) {
    public static final long THINK_MS = 2000;

    public searchLimits {
        skillLevel = Math.max(1, Math.min(10, skillLevel));
    }

    /** Thinking-time choices offered in Settings, in milliseconds. */
    public static final long[] CHOICES = {250, 500, 1000, 2000, 5000};

    /** Full strength with the thinking time chosen in Settings (engine.thinkMs, default two seconds). */
    public static searchLimits standard() { return new searchLimits(10, 64, thinkMs()); }

    public static long thinkMs() {
        try {
            long ms = Long.parseLong(handlers.configHandler.getConfigString("engine.thinkMs", String.valueOf(THINK_MS)));
            return Math.max(50, Math.min(60_000, ms));
        } catch (RuntimeException e) {
            return THINK_MS;
        }
    }

    public Map<String, Object> toJson() { return Json.map("skillLevel", (long) skillLevel, "maxDepth", (long) maxDepth, "maxTimeMs", maxTimeMs); }
}
