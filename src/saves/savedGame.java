package saves;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import handlers.Json;
import rules.Move;
import rules.Position;
import rules.gameState;

/**
 * One saved game (src/assets/saves/<gameId>.json). Loading tolerates missing and unknown fields;
 * unknown top-level fields are kept and written back unchanged.
 */
public final class savedGame {
    public static final int FORMAT_VERSION = 1;
    public static final String LOCAL = "LOCAL", AI = "AI", MULTIPLAYER = "MULTIPLAYER";
    public static final String IN_PROGRESS = "in_progress", FINISHED = "finished";

    /** One move as stored in the file. */
    public static final class moveRecord {
        public int ply;
        public String from, to, promotion, san, hash;
        /** Multiplayer only: sent but not yet acknowledged by the opponent. */
        public boolean pending;

        public Move toMove() {
            String uci = from + to + (promotion == null ? "" : promotion);
            return Move.fromUci(uci);
        }
    }

    public int formatVersion = FORMAT_VERSION;
    public String gameId = UUID.randomUUID().toString();
    public String mode = LOCAL;
    public String created = Instant.now().toString();
    public String updated = created;
    public String status = IN_PROGRESS;
    public String result = "*";
    /** gameState.Status name, e.g. CHECKMATE or RESIGNATION. */
    public String termination = gameState.Status.ONGOING.name();
    public String whiteName = "White", whiteId = "", blackName = "Black", blackId = "";
    public String startFen = Position.START_FEN;
    public List<moveRecord> moves = new ArrayList<>();
    public String headHash;
    /** Per-ply cached suggestions: key = ply index as text ("0" = start position), value = list of suggestion objects. */
    public Map<String, Object> analysis = new LinkedHashMap<>();
    /** Mode-specific settings (AI colour/skill, AI-vs-AI providers). */
    public Map<String, Object> settings = new LinkedHashMap<>();
    /** Multiplayer data: gameKey, localColor, localPlayerId, role. Null for other modes. */
    public Map<String, Object> multiplayer;
    private Map<String, Object> unknown = new LinkedHashMap<>();

    public static savedGame create(String mode, String startFen) {
        savedGame s = new savedGame();
        s.mode = mode;
        s.startFen = Position.fromFen(startFen).toFen();
        s.headHash = hashChain.hash0(s.gameId, s.startFen);
        return s;
    }

    public int plyCount() { return moves.size(); }
    public boolean isFinished() { return FINISHED.equals(status); }

    public List<Move> moveList() {
        List<Move> l = new ArrayList<>();
        for (moveRecord r : moves) l.add(r.toMove());
        return l;
    }

    public List<String> hashes() {
        List<String> l = new ArrayList<>();
        for (moveRecord r : moves) l.add(r.hash);
        return l;
    }

    public boolean hasPending() {
        for (moveRecord r : moves) if (r.pending) return true;
        return false;
    }

    /** Rebuilds the game (throws IllegalArgumentException if the stored moves are illegal). */
    public gameState toGameState() {
        gameState g = gameState.replay(startFen, moveList());
        if (isFinished() && !g.isOver()) {
            try {
                gameState.Status st = gameState.Status.valueOf(termination);
                int winner = result.equals("1-0") ? 1 : result.equals("0-1") ? -1 : 0;
                g.forceResult(st, winner);
            } catch (IllegalArgumentException e) {
                g.forceResult(gameState.Status.DRAW_AGREED, 0);
            }
        }
        return g;
    }

    /** Copies moves, hashes and result from g. Pending flags survive for moves that did not change. */
    public void syncFrom(gameState g) {
        List<moveRecord> old = moves;
        List<moveRecord> out = new ArrayList<>();
        List<String> chain = hashChain.chain(gameId, startFen, g.moves());
        for (int i = 0; i < g.plyCount(); i++) {
            Move m = g.move(i);
            moveRecord r = new moveRecord();
            r.ply = i + 1;
            r.from = Position.squareName(m.from());
            r.to = Position.squareName(m.to());
            r.promotion = m.promotion() == 0 ? null : String.valueOf(Character.toLowerCase(Position.pieceLetter(m.promotion())));
            r.san = g.san(i);
            r.hash = chain.get(i);
            r.pending = i < old.size() && r.hash.equals(old.get(i).hash) && old.get(i).pending;
            out.add(r);
        }
        moves = out;
        headHash = chain.isEmpty() ? hashChain.hash0(gameId, startFen) : chain.get(chain.size() - 1);
        status = g.isOver() ? FINISHED : IN_PROGRESS;
        result = g.result();
        termination = g.status().name();
        touch();
    }

    public void touch() { updated = Instant.now().toString(); }

    public String terminationText() {
        try { return toGameState().terminationText(); } catch (RuntimeException e) { return termination; }
    }

    // ---------- JSON ----------

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("formatVersion", (long) formatVersion);
        m.put("gameId", gameId);
        m.put("mode", mode);
        m.put("created", created);
        m.put("updated", updated);
        m.put("status", status);
        m.put("result", result);
        m.put("termination", termination);
        m.put("players", Json.map(
                "white", Json.map("name", whiteName, "playerId", whiteId),
                "black", Json.map("name", blackName, "playerId", blackId)));
        m.put("startFen", startFen);
        List<Object> mv = new ArrayList<>();
        for (moveRecord r : moves) {
            Map<String, Object> o = Json.map("ply", (long) r.ply, "from", r.from, "to", r.to, "promotion", r.promotion, "san", r.san, "hash", r.hash);
            if (r.pending) o.put("pending", true);
            mv.add(o);
        }
        m.put("moves", mv);
        m.put("headHash", headHash);
        m.put("analysis", analysis);
        m.put("settings", settings);
        if (multiplayer != null) m.put("multiplayer", multiplayer);
        for (Map.Entry<String, Object> e : unknown.entrySet()) m.putIfAbsent(e.getKey(), e.getValue());
        return m;
    }

    private static final List<String> KNOWN = List.of("formatVersion", "gameId", "mode", "created", "updated", "status", "result",
            "termination", "players", "startFen", "moves", "headHash", "analysis", "settings", "multiplayer");

    public static savedGame fromJson(Map<String, Object> m) {
        savedGame s = new savedGame();
        s.formatVersion = (int) Json.num(m, "formatVersion", FORMAT_VERSION);
        s.gameId = Json.str(m, "gameId", s.gameId);
        s.mode = Json.str(m, "mode", LOCAL);
        s.created = Json.str(m, "created", s.created);
        s.updated = Json.str(m, "updated", s.created);
        s.status = Json.str(m, "status", IN_PROGRESS);
        s.result = Json.str(m, "result", "*");
        s.termination = Json.str(m, "termination", gameState.Status.ONGOING.name());
        Map<String, Object> players = Json.obj(m, "players");
        Map<String, Object> w = Json.obj(players, "white"), b = Json.obj(players, "black");
        s.whiteName = Json.str(w, "name", "White");
        s.whiteId = Json.str(w, "playerId", "");
        s.blackName = Json.str(b, "name", "Black");
        s.blackId = Json.str(b, "playerId", "");
        s.startFen = Json.str(m, "startFen", Position.START_FEN);
        for (Object o : Json.list(m, "moves")) {
            if (!(o instanceof Map)) throw new IllegalArgumentException("move entry is not an object");
            @SuppressWarnings("unchecked") Map<String, Object> mo = (Map<String, Object>) o;
            moveRecord r = new moveRecord();
            r.ply = (int) Json.num(mo, "ply", s.moves.size() + 1);
            r.from = Json.str(mo, "from", null);
            r.to = Json.str(mo, "to", null);
            r.promotion = Json.str(mo, "promotion", null);
            r.san = Json.str(mo, "san", "?");
            r.hash = Json.str(mo, "hash", null);
            r.pending = Json.bool(mo, "pending", false);
            if (r.toMove() == null) throw new IllegalArgumentException("bad move at ply " + r.ply);
            s.moves.add(r);
        }
        s.headHash = Json.str(m, "headHash", null);
        Map<String, Object> an = Json.obj(m, "analysis");
        if (an != null) s.analysis = an;
        Map<String, Object> st = Json.obj(m, "settings");
        if (st != null) s.settings = st;
        s.multiplayer = Json.obj(m, "multiplayer");
        for (Map.Entry<String, Object> e : m.entrySet()) if (!KNOWN.contains(e.getKey())) s.unknown.put(e.getKey(), e.getValue());
        // Recompute hashes if missing (older or hand-written files).
        List<String> chain = hashChain.chain(s.gameId, s.startFen, s.moveList());
        for (int i = 0; i < s.moves.size(); i++) if (s.moves.get(i).hash == null) s.moves.get(i).hash = chain.get(i);
        if (s.headHash == null) s.headHash = chain.isEmpty() ? hashChain.hash0(s.gameId, s.startFen) : chain.get(chain.size() - 1);
        return s;
    }

    public savedGame copy() { return fromJson(Json.parseObject(Json.write(toJson()))); }

    public String settingStr(String k, String def) { return Json.str(settings, k, def); }
    public long settingNum(String k, long def) { return Json.num(settings, k, def); }
    public String mpStr(String k, String def) { return Json.str(multiplayer, k, def); }
}
