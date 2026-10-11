package ai;

import java.util.Map;

import handlers.Json;

/** How hard the engine should think. skillLevel 1-10; maxDepth in plies; maxTimeMs wall-clock budget. */
public record searchLimits(int skillLevel, int maxDepth, long maxTimeMs) {

    public searchLimits {
        skillLevel = Math.max(1, Math.min(10, skillLevel));
    }

    /** The Player vs AI difficulty slider maps 1-10 to these limits. */
    public static searchLimits forSkill(int skill) {
        int s = Math.max(1, Math.min(10, skill));
        return new searchLimits(s, 1 + (s - 1) / 3, 200L * s);
    }

    public Map<String, Object> toJson() { return Json.map("skillLevel", (long) skillLevel, "maxDepth", (long) maxDepth, "maxTimeMs", maxTimeMs); }
}
