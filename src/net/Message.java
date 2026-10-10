package net;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import handlers.Json;

/** One protocol message: a JSON object with a "type", sent as one UTF-8 line. */
public final class Message {
    // Message types (see docs/MULTIPLAYER.md for the flow)
    public static final String HELLO = "HELLO", AUTH = "AUTH", ERROR = "ERROR",
            GAME_OFFER = "GAME_OFFER", GAME_ACCEPT = "GAME_ACCEPT", GAME_DECLINE = "GAME_DECLINE", REMATCH_REQUEST = "REMATCH_REQUEST",
            MOVE = "MOVE", ACK = "ACK", REJECT = "REJECT", RESIGN = "RESIGN",
            DRAW_OFFER = "DRAW_OFFER", DRAW_ACCEPT = "DRAW_ACCEPT", DRAW_DECLINE = "DRAW_DECLINE",
            PING = "PING", PONG = "PONG", LEAVE = "LEAVE",
            RESUME = "RESUME", SYNC_MOVES = "SYNC_MOVES", SYNC_PROPOSE = "SYNC_PROPOSE", SYNC_ACCEPT = "SYNC_ACCEPT", SYNC_REJECT = "SYNC_REJECT";

    public final Map<String, Object> body;

    private Message(Map<String, Object> body) { this.body = body; }

    public static Message of(String type, Object... kv) {
        Map<String, Object> m = Json.map(kv);
        m.put("type", type);
        return new Message(m);
    }

    public static Message parse(String line) { return new Message(Json.parseObject(line)); }

    public String toLine() { return Json.write(body); }

    public String type() { return Json.str(body, "type", ""); }
    public String str(String k) { return Json.str(body, k, null); }
    public long num(String k, long def) { return Json.num(body, k, def); }
    public boolean bool(String k) { return Json.bool(body, k, false); }
    public Map<String, Object> obj(String k) { return Json.obj(body, k); }

    public List<String> strings(String k) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.list(body, k)) out.add(String.valueOf(o));
        return out;
    }

    @Override public String toString() { return toLine(); }
}
