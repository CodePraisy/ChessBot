package saves;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import rules.Move;

/** hash0 = SHA-256(gameId + startFen); hash_n = SHA-256(hash_{n-1} + move_n as UCI text), all lower-case hex. */
public final class hashChain {
    private hashChain() {}

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hash0(String gameId, String startFen) { return sha256(gameId + startFen); }

    public static String next(String prev, Move m) { return sha256(prev + m.uci()); }

    /** Per-ply hashes: element i is hash_{i+1} (the hash after move i+1). */
    public static List<String> chain(String gameId, String startFen, List<Move> moves) {
        List<String> out = new ArrayList<>();
        String h = hash0(gameId, startFen);
        for (Move m : moves) { h = next(h, m); out.add(h); }
        return out;
    }

    public static String head(String gameId, String startFen, List<Move> moves) {
        List<String> c = chain(gameId, startFen, moves);
        return c.isEmpty() ? hash0(gameId, startFen) : c.get(c.size() - 1);
    }
}
