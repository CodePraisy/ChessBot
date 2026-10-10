package rules;

import java.util.List;

/**
 * Rules self-test: perft node counts against published values plus FEN/SAN/PGN/draw checks.
 * Run from the project root:  java -cp out rules.rulesSelfTest
 */
public final class rulesSelfTest {
    private static int failures = 0;

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();
        // Published perft values (chessprogramming.org "Perft Results").
        perft("start", Position.START_FEN, new long[]{20, 400, 8902, 197281});
        perft("kiwipete", "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", new long[]{48, 2039, 97862});
        perft("position3", "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", new long[]{14, 191, 2812, 43238});
        perft("position4", "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", new long[]{6, 264, 9467});
        perft("position5", "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", new long[]{44, 1486, 62379});
        perft("position6", "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10", new long[]{46, 2079, 89890});

        // FEN round trip
        for (String f : List.of(Position.START_FEN, "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                "8/8/8/8/8/8/8/K6k b - - 12 40"))
            check("fen round trip " + f, Position.fromFen(f).toFen().equals(f));

        // SAN with disambiguation, castling, promotion, check and mate
        Position p = Position.fromFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1");
        check("san O-O", notation.toSan(p, Move.fromUci("e1g1")).equals("O-O"));
        check("san O-O-O", notation.toSan(p, Move.fromUci("e1c1")).equals("O-O-O"));
        check("san Ra8+", notation.toSan(p, Move.fromUci("a1a8")).equals("Ra8+"));
        p = Position.fromFen("4k3/8/8/8/8/8/4K3/R6R w - - 0 1");
        check("san file disambiguation Rad1", notation.toSan(p, Move.fromUci("a1d1")).equals("Rad1"));
        p = Position.fromFen("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1");
        check("san rank disambiguation R1a3", notation.toSan(p, Move.fromUci("a1a3")).equals("R1a3"));
        p = Position.fromFen("4k3/8/8/8/2N3N1/8/2N5/4K3 w - - 0 1");
        check("san square disambiguation Nc4e3", notation.toSan(p, Move.fromUci("c4e3")).equals("Nc4e3"));
        p = Position.fromFen("8/P7/8/8/8/8/7k/K7 w - - 0 1");
        check("san promotion a8=Q", notation.toSan(p, Move.fromUci("a7a8q")).equals("a8=Q"));
        p = Position.fromFen("rnbqkbnr/ppppp2p/8/5pp1/4P3/8/PPPP1PPP/RNBQKBNR w KQkq g6 0 3");
        check("san mate Qh5#", notation.toSan(p, Move.fromUci("d1h5")).equals("Qh5#"));
        p = Position.fromFen("rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3");
        check("san en passant exf6", notation.toSan(p, Move.fromUci("e5f6")).equals("exf6"));
        check("en passant removes pawn", p.play(Move.fromUci("e5f6")).pieceAt(Position.squareIndex("f5")) == 0);

        // Fool's mate -> checkmate, black wins
        gameState g = new gameState();
        for (String u : List.of("f2f3", "e7e5", "g2g4", "d8h4")) g.play(Move.fromUci(u));
        check("fool's mate status", g.status() == gameState.Status.CHECKMATE && g.result().equals("0-1"));

        // Stalemate
        g = new gameState("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        check("stalemate", g.status() == gameState.Status.STALEMATE);

        // Insufficient material
        check("K v K", new gameState("8/8/8/4k3/8/8/8/4K3 w - - 0 1").status() == gameState.Status.INSUFFICIENT_MATERIAL);
        check("KB v K", new gameState("8/8/8/4k3/8/8/8/2B1K3 w - - 0 1").status() == gameState.Status.INSUFFICIENT_MATERIAL);
        check("KN v K", new gameState("8/8/8/4k3/8/8/8/1N2K3 w - - 0 1").status() == gameState.Status.INSUFFICIENT_MATERIAL);
        check("KB v KB same colour", new gameState("8/8/8/2b1k3/8/8/8/2B1K3 w - - 0 1").status() == gameState.Status.INSUFFICIENT_MATERIAL);
        check("KB v KB opposite colour is playable", new gameState("8/8/8/3bk3/8/8/8/2B1K3 w - - 0 1").status() == gameState.Status.ONGOING);
        check("KNN v K is playable", new gameState("8/8/8/4k3/8/8/8/1NN1K3 w - - 0 1").status() == gameState.Status.ONGOING);

        // Threefold repetition (knights shuffle back and forth twice)
        g = new gameState();
        for (String u : List.of("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8")) g.play(Move.fromUci(u));
        check("threefold repetition", g.status() == gameState.Status.THREEFOLD_REPETITION && g.result().equals("1/2-1/2"));

        // 50-move rule
        g = new gameState("8/8/8/4k3/8/8/1R6/4K3 w - - 99 80");
        g.play(Move.fromUci("b2b3"));
        check("fifty-move rule", g.status() == gameState.Status.FIFTY_MOVE_RULE);

        // Castling rights lost after rook capture
        p = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").play(Move.fromUci("a1a8"));
        check("castling right removed on rook capture", p.castlingFen().equals("Kk"));

        // Engine form round trip
        p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        check("engine form round trip", Position.fromEngineForm(p.toEngineForm()).equals(p));
        check("engine form row 0 is rank 8", ((List<?>) ((List<?>) p.toEngineForm().get("board")).get(0)).get(0).equals(-4L));
        Move em = Move.fromUci("e7e8q");
        check("move engine form", Move.fromEngineForm(em.toEngineForm()).equals(em) && em.toEngineForm().toString().equals("[[1, 4], [0, 4], 5]"));

        // PGN round trip
        g = new gameState();
        for (String u : List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5c6", "d7c6", "e1g1")) g.play(Move.fromUci(u));
        String pgn = notation.toPgn(g, null);
        notation.pgnGame back = notation.parsePgn(pgn);
        check("pgn round trip", back.game().moves().equals(g.moves()));
        check("pgn contains O-O", pgn.contains("5. O-O"));

        System.out.printf("%nrulesSelfTest: %s (%d failure(s), %d ms)%n", failures == 0 ? "ALL PASSED" : "FAILED", failures, System.currentTimeMillis() - t0);
        if (failures > 0) System.exit(1);
    }

    static long perft(Position p, int depth) {
        if (depth == 1) return p.legalMoves().size();
        long n = 0;
        for (Move m : p.legalMoves()) n += perft(p.play(m), depth - 1);
        return n;
    }

    private static void perft(String name, String fen, long[] expected) {
        Position p = Position.fromFen(fen);
        for (int d = 1; d <= expected.length; d++) {
            long got = perft(p, d);
            check("perft " + name + " depth " + d + " = " + got + " (expected " + expected[d - 1] + ")", got == expected[d - 1]);
        }
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (!ok) failures++;
    }
}
