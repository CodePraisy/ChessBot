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

    /** Full strength with a fixed thinking time (about two seconds a move). */
    public static searchLimits standard() { return new searchLimits(10, 64, THINK_MS); }

    public Map<String, Object> toJson() { return Json.map("skillLevel", (long) skillLevel, "maxDepth", (long) maxDepth, "maxTimeMs", maxTimeMs); }
}
