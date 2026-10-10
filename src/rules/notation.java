package rules;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SAN generation/parsing and PGN export/import. */
public final class notation {
    private notation() {}

    public static String toSan(Position pos, Move m) {
        String san;
        int p = pos.pieceAt(m.from());
        int type = Math.abs(p);
        if (pos.isCastle(m)) {
            san = m.to() > m.from() ? "O-O" : "O-O-O";
        } else {
            boolean capture = pos.capturedBy(m) != 0;
            StringBuilder s = new StringBuilder();
            if (type == Position.PAWN) {
                if (capture) s.append((char) ('a' + m.fromCol())).append('x');
                s.append(Position.squareName(m.to()));
                if (m.promotion() != 0) s.append('=').append(Position.pieceLetter(m.promotion()));
            } else {
                s.append(Position.pieceLetter(type));
                boolean clash = false, sameFile = false, sameRank = false;
                for (Move o : pos.legalMoves()) {
                    if (o.equals(m) || o.to() != m.to() || o.from() == m.from() || pos.pieceAt(o.from()) != p) continue;
                    clash = true;
                    if (o.fromCol() == m.fromCol()) sameFile = true;
                    if (o.fromRow() == m.fromRow()) sameRank = true;
                }
                if (clash) {
                    if (!sameFile) s.append((char) ('a' + m.fromCol()));
                    else if (!sameRank) s.append((char) ('8' - m.fromRow()));
                    else s.append(Position.squareName(m.from()));
                }
                if (capture) s.append('x');
                s.append(Position.squareName(m.to()));
            }
            san = s.toString();
        }
        Position next = pos.play(m);
        if (next.inCheck()) san += next.legalMoves().isEmpty() ? "#" : "+";
        return san;
    }

    /** Finds the legal move matching a SAN string (annotations like +, #, !, ? are ignored). Null if none. */
    public static Move fromSan(Position pos, String san) {
        String want = clean(san);
        if (want.equals("0-0")) want = "O-O";
        if (want.equals("0-0-0")) want = "O-O-O";
        for (Move m : pos.legalMoves()) if (clean(toSan(pos, m)).equals(want)) return m;
        // Tolerate missing "=" in promotions ("e8Q") and redundant disambiguation.
        for (Move m : pos.legalMoves()) {
            String s = clean(toSan(pos, m));
            if (s.replace("=", "").equals(want.replace("=", ""))) return m;
        }
        return null;
    }

    private static String clean(String s) { return s.trim().replaceAll("[+#!?]", ""); }

    // ---------- PGN ----------

    public static String toPgn(gameState g, Map<String, String> extraHeaders) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Event", "ChessBot game");
        h.put("Site", "ChessBot");
        h.put("Date", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd")));
        h.put("Round", "-");
        h.put("White", "White");
        h.put("Black", "Black");
        if (extraHeaders != null) h.putAll(extraHeaders);
        h.put("Result", g.result());
        if (!g.startFen().equals(Position.START_FEN)) { h.put("SetUp", "1"); h.put("FEN", g.startFen()); }
        if (g.status() != gameState.Status.ONGOING) h.put("Termination", g.terminationText());

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : h.entrySet())
            out.append('[').append(e.getKey()).append(" \"").append(e.getValue().replace("\"", "'")).append("\"]\n");
        out.append('\n');

        StringBuilder line = new StringBuilder();
        Position start = g.positionAt(0);
        int moveNo = start.fullmoveNumber();
        boolean white = start.sideToMove() == Position.WHITE;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < g.plyCount(); i++) {
            if (white) tokens.add(moveNo + ".");
            else if (i == 0) tokens.add(moveNo + "...");
            tokens.add(g.san(i));
            if (!white) moveNo++;
            white = !white;
        }
        tokens.add(g.result());
        for (String t : tokens) {
            if (line.length() + t.length() + 1 > 80) { out.append(line.toString().trim()).append('\n'); line.setLength(0); }
            line.append(t).append(' ');
        }
        out.append(line.toString().trim()).append("\n");
        return out.toString();
    }

    /** Result of parsing a PGN: headers plus the replayed game. */
    public record pgnGame(Map<String, String> headers, gameState game) {}

    /** Parses the first game in a PGN text. Throws IllegalArgumentException on an illegal/unknown move. */
    public static pgnGame parsePgn(String text) {
        Map<String, String> headers = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder();
        for (String raw : text.split("\\R")) {
            String l = raw.trim();
            if (l.startsWith("[") && l.endsWith("]")) {
                int sp = l.indexOf(' ');
                if (sp > 1) headers.put(l.substring(1, sp), l.substring(sp + 1, l.length() - 1).trim().replaceAll("^\"|\"$", ""));
            } else if (!l.startsWith("%")) {
                body.append(l).append(' ');
            }
        }
        String movetext = body.toString()
                .replaceAll("\\{[^}]*}", " ")
                .replaceAll(";[^\\n]*", " ");
        // strip (nested) variations
        String prev;
        do { prev = movetext; movetext = movetext.replaceAll("\\([^()]*\\)", " "); } while (!prev.equals(movetext));
        String fen = headers.getOrDefault("FEN", Position.START_FEN);
        gameState g = new gameState(fen);
        for (String tok : movetext.trim().split("\\s+")) {
            if (tok.isEmpty() || tok.startsWith("$")) continue;
            String t = tok.replaceAll("^\\d+\\.+", "");
            if (t.isEmpty()) continue;
            if (t.equals("1-0") || t.equals("0-1") || t.equals("1/2-1/2") || t.equals("*")) break;
            Move m = fromSan(g.current(), t);
            if (m == null || !g.play(m)) throw new IllegalArgumentException("Illegal or unknown move in PGN: " + tok);
        }
        return new pgnGame(headers, g);
    }
}
