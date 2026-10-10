package main;

import rules.Position;
import saves.savedGame;

/**
 * What kind of game to open. kind is LOCAL, AI, AIVAI or MULTIPLAYER.
 * resume is a saved game to continue (null for a new game). session is the live multiplayer connection.
 */
public record gameSetup(String kind, int humanColor, int skill, String aiEngine, String whiteEngine, String blackEngine,
                        savedGame resume, net.netSession session) {
    public static final String LOCAL = "LOCAL", AI = "AI", AIVAI = "AIVAI", MULTIPLAYER = "MULTIPLAYER";

    public static gameSetup local() { return new gameSetup(LOCAL, 0, 0, null, null, null, null, null); }

    public static gameSetup vsAi(int humanColor, int skill) { return vsAi(humanColor, skill, null); }

    public static gameSetup vsAi(int humanColor, int skill, String aiEngine) {
        return new gameSetup(AI, humanColor, skill, aiEngine, null, null, null, null);
    }

    public static gameSetup aiVsAi(String whiteEngine, String blackEngine, int skill) { return new gameSetup(AIVAI, 0, skill, null, whiteEngine, blackEngine, null, null); }

    public static gameSetup multiplayer(net.netSession session) { return new gameSetup(MULTIPLAYER, 0, 0, null, null, null, null, session); }

    /** Rebuilds the setup a saved game was played with. */
    public static gameSetup resume(savedGame s) {
        if (savedGame.AI.equals(s.mode)) {
            if (s.settingStr("whiteEngine", null) != null)
                return new gameSetup(AIVAI, 0, (int) s.settingNum("skill", 5), null, s.settingStr("whiteEngine", null), s.settingStr("blackEngine", null), s, null);
            int hc = "black".equals(s.settingStr("humanColor", "white")) ? Position.BLACK : Position.WHITE;
            return new gameSetup(AI, hc, (int) s.settingNum("skill", 5), s.settingStr("aiEngine", null), null, null, s, null);
        }
        return new gameSetup(LOCAL, 0, 0, null, null, null, s, null);
    }

    /** Same kind of game, starting fresh (used by New Game / Rematch). */
    public gameSetup fresh() { return new gameSetup(kind, humanColor, skill, aiEngine, whiteEngine, blackEngine, null, session); }

    public String title() {
        return switch (kind) {
            case AI -> "Player vs AI";
            case AIVAI -> "AI vs AI";
            case MULTIPLAYER -> "Multiplayer";
            default -> "Pass & Play";
        };
    }
}
