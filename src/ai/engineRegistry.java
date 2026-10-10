package ai;

import java.util.ArrayList;
import java.util.List;

import rules.Position;

/** Holds one moveProvider per colour, one analysisProvider, and the list offered on the AI vs AI screen. */
public final class engineRegistry {
    private engineRegistry() {}

    private static moveProvider white, black;
    private static analysisProvider analysis;
    private static final List<moveProvider> all = new ArrayList<>();

    /** One-line registration: plays both colours, and analyses too if the engine implements analysisProvider. */
    public static synchronized void useEngine(moveProvider engine) {
        white = engine;
        black = engine;
        if (!all.contains(engine)) all.add(engine);
        if (engine instanceof analysisProvider a) analysis = a;
    }

    public static synchronized void register(int color, moveProvider p) {
        if (color == Position.WHITE) white = p; else black = p;
        if (p != null && !all.contains(p)) all.add(p);
    }

    public static synchronized void registerAnalysis(analysisProvider a) { analysis = a; }

    public static synchronized moveProvider forColor(int color) { return color == Position.WHITE ? white : black; }

    public static synchronized analysisProvider analysis() { return analysis; }

    public static synchronized List<moveProvider> providers() { return List.copyOf(all); }

    public static synchronized moveProvider byName(String name) {
        for (moveProvider p : all) if (p.name().equals(name)) return p;
        return null;
    }

    public static synchronized boolean hasMoveProvider() { return white != null || black != null || !all.isEmpty(); }

    /** Tests only. */
    public static synchronized void clear() { white = null; black = null; analysis = null; all.clear(); }
}
