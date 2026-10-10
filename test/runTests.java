import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import ai.Suggestion;
import ai.devRandomEngine;
import ai.externalProcessProvider;
import ai.searchLimits;
import handlers.Json;
import net.Message;
import net.directTransport;
import net.netSession;
import net.relayTransport;
import review.moveClassifier;
import rules.Move;
import rules.Position;
import rules.gameState;
import saves.hashChain;
import saves.profile;
import saves.saveManager;
import saves.savedGame;

/**
 * Non-GUI test suite. From the project root:
 *   javac -d out $(find src -name "*.java")  &&  javac -cp out -d out-test test/runTests.java  &&  java -cp out:out-test runTests
 * (on Windows use ; instead of : in the classpath). Multiplayer tests run two in-process peers over loopback TCP.
 */
public class runTests {
    static int passed, failed;
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        section("Json / config");      jsonTests();
        section("Saves");              saveTests();
        section("Review classifier");  classifierTests();
        section("Engines");            engineTests();
        section("Multiplayer (loopback)"); netTests();
        System.out.printf("%n%d passed, %d failed (%d s)%n", passed, failed, (System.currentTimeMillis() - t0) / 1000);
        failures.forEach(f -> System.out.println("  FAILED: " + f));
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- helpers

    static void section(String s) { System.out.println("\n== " + s); }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (ok) passed++; else { failed++; failures.add(name); }
    }

    static boolean waitFor(BooleanSupplier c, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            try { Thread.sleep(20); } catch (InterruptedException e) { return false; }
        }
        return c.getAsBoolean();
    }

    static Path tempDir(String name) throws Exception { return Files.createTempDirectory("chessbot-" + name); }

    // ---------------------------------------------------------------- Json

    static void jsonTests() {
        String src = "{\"a\": 1, \"b\": [true, null, 2.5, \"x\\\"y\\n\\u00e9\"], \"c\": {\"d\": -3}}";
        Map<String, Object> m = Json.parseObject(src);
        check("parse numbers/arrays/strings", m.get("a").equals(1L) && ((List<?>) m.get("b")).get(2).equals(2.5)
                && ((List<?>) m.get("b")).get(3).equals("x\"y\né") && Json.num(Json.obj(m, "c"), "d", 0) == -3);
        check("write/parse round trip", Json.parseObject(Json.write(m)).equals(m) && Json.parseObject(Json.pretty(m)).equals(m));
        boolean threw = false;
        try { Json.parse("{\"a\": }"); } catch (IllegalArgumentException e) { threw = true; }
        check("malformed JSON throws", threw);
        check("tolerant accessors return defaults", Json.str(m, "missing", "def").equals("def") && Json.num(m, "b", 7) == 7);
        String config = "{\"saves\": {\"saveState\": true}, \"audio\": {\"soundEffects\": false}}";
        Map<String, Object> c = Json.parseObject(config);
        check("config nested lookup shape", Json.bool(Json.obj(c, "audio"), "soundEffects", true) == false);
    }

    // ---------------------------------------------------------------- saves

    static void saveTests() throws Exception {
        Path dir = tempDir("saves");
        savedGame s = savedGame.create(savedGame.LOCAL, Position.START_FEN);
        gameState g = new gameState();
        for (String u : List.of("e2e4", "e7e5", "g1f3")) g.play(Move.fromUci(u));
        s.syncFrom(g);
        saveManager.save(dir, s);
        try (Stream<Path> files = Files.list(dir)) {
            check("atomic save leaves no temp files", files.noneMatch(p -> p.toString().endsWith(".tmp")));
        }
        savedGame back = saveManager.load(dir, s.gameId);
        check("save/load round trip", back != null && back.moveList().equals(g.moves()) && back.headHash.equals(s.headHash));
        check("hash chain: hash0 = sha256(gameId+fen), hash_n = sha256(prev+uci)",
                s.moves.get(0).hash.equals(hashChain.sha256(hashChain.hash0(s.gameId, s.startFen) + "e2e4"))
                        && s.headHash.equals(hashChain.head(s.gameId, s.startFen, g.moves())));

        // unknown + missing fields
        Map<String, Object> raw = Json.parseObject(Files.readString(dir.resolve(s.gameId + ".json")));
        raw.put("futureField", Json.map("x", 1L));
        raw.remove("analysis");
        raw.remove("created");
        Files.writeString(dir.resolve(s.gameId + ".json"), Json.write(raw));
        savedGame tolerant = saveManager.load(dir, s.gameId);
        check("loader tolerates missing fields", tolerant != null && tolerant.analysis.isEmpty());
        saveManager.save(dir, tolerant);
        check("unknown fields are kept on rewrite", Files.readString(dir.resolve(s.gameId + ".json")).contains("futureField"));

        // corrupt file
        Path bad = dir.resolve("broken-game.json");
        Files.writeString(bad, "{ this is not json");
        saveManager.takeProblems();
        Path old = handlers.paths.savesDir();
        handlers.paths.setSavesDir(dir);
        List<savedGame> listed = saveManager.list();
        handlers.paths.setSavesDir(old);
        List<String> problems = saveManager.takeProblems();
        check("corrupt file skipped by list()", listed.size() == 1);
        check("corrupt file renamed to .corrupt", !Files.exists(bad) && Files.exists(dir.resolve("broken-game.corrupt")));
        check("corrupt file reported", problems.size() == 1 && problems.get(0).contains("broken-game"));

        // illegal moves count as corrupt
        raw = Json.parseObject(Files.readString(dir.resolve(s.gameId + ".json")));
        @SuppressWarnings("unchecked") Map<String, Object> second = (Map<String, Object>) Json.list(raw, "moves").get(1);
        second.put("to", "e3"); // e7e3 is illegal
        Path illegal = dir.resolve("illegal-moves.json");
        Files.writeString(illegal, Json.write(raw));
        check("save with illegal moves is treated as corrupt", saveManager.load(dir, "illegal-moves") == null && Files.exists(dir.resolve("illegal-moves.corrupt")));
        saveManager.takeProblems();

        // backup
        Path b = saveManager.backup(dir, s.gameId);
        check("backup written as <id>.bak.<timestamp>.json", b != null && b.getFileName().toString().startsWith(s.gameId + ".bak.") && Files.exists(b));

        // the hand-written review fixture loads and keeps its fake analysis
        savedGame fixture = savedGame.fromJson(Json.parseObject(Files.readString(Path.of("test/fixtures/fake-analysis.json"))));
        check("fixture with fake suggestions loads", fixture.plyCount() == 7 && fixture.analysis.size() == 7 && fixture.toGameState().status() == gameState.Status.CHECKMATE);
        @SuppressWarnings("unchecked") Suggestion sug = Suggestion.fromJson((Map<String, Object>) ((List<Object>) fixture.analysis.get("6")).get(0));
        check("cached suggestion parses (mate in 1)", sug != null && sug.mateIn() == 1 && sug.move().uci().equals("h5f7"));
    }

    // ---------------------------------------------------------------- classifier

    static void classifierTests() {
        check("loss 20 -> good", moveClassifier.classify(40, 20) == moveClassifier.Quality.GOOD);
        check("loss 70 -> inaccuracy", moveClassifier.classify(-20, -90) == moveClassifier.Quality.INACCURACY);
        check("loss 100 -> mistake", moveClassifier.classify(90, -10) == moveClassifier.Quality.MISTAKE);
        check("allowing mate -> blunder", moveClassifier.classify(10, -(Suggestion.MATE_SCORE - 1)) == moveClassifier.Quality.BLUNDER);
        check("missing a mate but still winning big -> good", moveClassifier.classify(Suggestion.MATE_SCORE - 3, 1200) == moveClassifier.Quality.GOOD);
        Suggestion mate = new Suggestion(Move.fromUci("h5f7"), null, 1, 5, List.of(), null);
        check("mate score sign from black's view", mate.whiteScore(Position.BLACK) < 0 && mate.scoreText().equals("M1"));
    }

    // ---------------------------------------------------------------- engines

    static void engineTests() throws Exception {
        devRandomEngine dev = new devRandomEngine(false);
        check("dev engine reports loading first", !dev.isAvailable());
        check("dev engine becomes available", waitFor(dev::isAvailable, 3000));
        Position p = Position.start();
        Move m = dev.chooseMove(p, List.of(), searchLimits.forSkill(3)).get(5, TimeUnit.SECONDS);
        check("dev engine returns a legal move", p.isLegal(m));
        List<Suggestion> s = dev.analyze(p, 3).get(5, TimeUnit.SECONDS);
        check("dev engine returns 3 fake suggestions", s.size() == 3 && s.stream().allMatch(x -> p.isLegal(x.move())));

        String python = Files.isRegularFile(Path.of("engine/venv/Scripts/python.exe")) ? "engine/venv/Scripts/python.exe" : "python";
        externalProcessProvider py = new externalProcessProvider("Atlas — Balanced", python, "engine/bridge.py", "--model", "atlas");
        check("Python model becomes available", waitFor(py::isAvailable, 20_000));
        Move pm = py.chooseMove(p, List.of(), searchLimits.forSkill(1)).get(10, TimeUnit.SECONDS);
        check("Python model returns a legal move", p.isLegal(pm));
        List<Suggestion> pythonAnalysis = py.analyze(p, 2).get(10, TimeUnit.SECONDS);
        check("Python model provides legal analysis", !pythonAnalysis.isEmpty() && pythonAnalysis.stream().allMatch(x -> p.isLegal(x.move())));
        py.shutdown();

        // externalProcessProvider against a tiny fake engine process speaking the JSON-lines contract
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        externalProcessProvider ext = new externalProcessProvider("fake", java, "test/fixtures/fakeEngine.java");
        check("external engine: unavailable until it says ready", !ext.isAvailable());
        check("external engine: becomes available after 'ready'", waitFor(ext::isAvailable, 20000));
        Position after = p.play(Move.fromUci("e2e4"));
        Move em = ext.chooseMove(after, List.of(Move.fromUci("e2e4")), searchLimits.forSkill(5)).get(10, TimeUnit.SECONDS);
        check("external engine: move round trip (engine form)", after.isLegal(em));
        List<Suggestion> lines = ext.analyze(after, 2).get(10, TimeUnit.SECONDS);
        check("external engine: analysis round trip", lines.size() == 2 && after.isLegal(lines.get(0).move()) && lines.get(0).centipawns() != null);
        ext.shutdown();
        check("searchLimits maps difficulty", searchLimits.forSkill(1).maxDepth() == 2 && searchLimits.forSkill(10).maxTimeMs() == 10000 && searchLimits.forSkill(42).skillLevel() == 10);
    }

    // ---------------------------------------------------------------- multiplayer

    /** One in-process player: a session, its own save folder and a scripted listener. */
    static final class peer implements netSession.listener {
        final String label;
        final Path dir;
        final profile me;
        directTransport t;
        netSession s;
        volatile netSession.status status;
        volatile String statusText = "";
        final List<String> notices = Collections.synchronizedList(new ArrayList<>());
        final List<String> desyncs = Collections.synchronizedList(new ArrayList<>());
        final List<netSession.syncInfo> syncs = Collections.synchronizedList(new ArrayList<>());
        final List<netSession.proposal> proposals = Collections.synchronizedList(new ArrayList<>());
        volatile boolean acceptOffers = true;
        volatile Boolean answerProposals = Boolean.TRUE;  // null = leave pending
        volatile String proposeOnDiverge;                  // MINE / THEIRS / ROLLBACK or null

        peer(String label, Path dir, profile me) { this.label = label; this.dir = dir; this.me = me; }

        void host(savedGame resume, int color) {
            t = new directTransport();
            s = netSession.host(me, t, 0, resume, color, dir);
            s.setListener(this);
            s.start();
        }

        void join(int port, savedGame resume) {
            t = new directTransport();
            s = netSession.join(me, t, "127.0.0.1", port, resume, dir);
            s.setListener(this);
            s.start();
        }

        void relayHost(int relayPort, String room, savedGame resume, int color) {
            t = new relayTransport("127.0.0.1", room);
            s = netSession.host(me, t, relayPort, resume, color, dir);
            s.setListener(this);
            s.start();
        }

        void relayJoin(int relayPort, String room, savedGame resume) {
            t = new relayTransport("127.0.0.1", room);
            s = netSession.join(me, t, "127.0.0.1", relayPort, resume, dir);
            s.setListener(this);
            s.start();
        }

        savedGame file(String id) { return saveManager.load(dir, id); }
        boolean playing() { return s.status() == netSession.status.PLAYING; }
        void leave() { if (s != null) s.leave(); }

        @Override public void onStatus(netSession.status st, String text) { status = st; statusText = text; }
        @Override public void onGameOffer(String hostName, int yourColor) { if (acceptOffers) s.answerOffer(true); }
        @Override public void onNotice(String title, String text) { notices.add(title + ": " + text); }
        @Override public void onDesync(String reason) { desyncs.add(reason); }
        @Override public void onSync(netSession.syncInfo info) {
            syncs.add(info);
            if ((info.kind() == netSession.syncKind.DIVERGED || info.kind() == netSession.syncKind.RESULT_MISMATCH) && proposeOnDiverge != null)
                s.propose(proposeOnDiverge);
        }
        @Override public void onProposal(netSession.proposal p) {
            proposals.add(p);
            if (answerProposals != null) s.answerProposal(answerProposals);
        }
        @Override public void onDrawOffer() { s.answerDraw(true); }
    }

    static int portOf(peer host) { waitFor(() -> host.t.localPort() > 0, 2000); return host.t.localPort(); }

    /** Plays moves alternately through the two sessions, waiting for each ACK. */
    static boolean play(peer white, peer black, String... ucis) {
        for (String u : ucis) {
            peer mover = white.s.canMove() ? white : black;
            peer other = mover == white ? black : white;
            int before = other.s.state().plyCount();
            if (!waitFor(mover.s::canMove, 3000)) return false;
            if (!mover.s.playMove(Move.fromUci(u))) return false;
            if (!waitFor(() -> other.s.state().plyCount() == before + 1 && !mover.s.state().hasPending(), 3000)) return false;
        }
        return true;
    }

    static void diag(peer a, peer b, String id) {
        for (peer p : List.of(a, b)) {
            savedGame f = p.file(id);
            System.out.println("    [" + p.label + "] status=" + p.s.status() + " text=" + p.s.statusText() + " plies=" + (f == null ? -1 : f.plyCount())
                    + " pending=" + (f != null && f.hasPending()) + " proposals=" + p.proposals.size() + " syncs=" + p.syncs + " desyncs=" + p.desyncs + " notices=" + p.notices);
        }
    }

    static boolean sameMoves(peer a, peer b, String id) {
        savedGame x = a.file(id), y = b.file(id);
        return x != null && y != null && x.hashes().equals(y.hashes()) && x.result.equals(y.result) && !x.hasPending() && !y.hasPending();
    }

    static long backups(peer p, String id) throws Exception {
        try (Stream<Path> f = Files.list(p.dir)) { return f.filter(x -> x.getFileName().toString().startsWith(id + ".bak.")).count(); }
    }

    /** Re-saves a player's copy with extra/different moves (simulating what that player saw before a disconnect). */
    static void rewrite(peer p, String id, String... ucis) throws Exception {
        savedGame g = p.file(id);
        List<Move> moves = new ArrayList<>();
        for (String u : ucis) moves.add(Move.fromUci(u));
        g.moves = new ArrayList<>();
        g.syncFrom(gameState.replay(g.startFen, moves));
        saveManager.save(p.dir, g);
    }

    static void netTests() throws Exception {
        profile alice = profile.of("player-alice", "Alice"), bob = profile.of("player-bob", "Bob");
        peer A = new peer("host", tempDir("host"), alice), B = new peer("guest", tempDir("guest"), bob);

        // --- new game, moves, ACKs
        A.host(null, Position.WHITE);
        B.join(portOf(A), null);
        check("new game: guest accepts offer, both PLAYING", waitFor(() -> A.playing() && B.playing(), 5000));
        String id = A.s.state().gameId;
        check("new game: both saved the same gameId and key", B.file(id) != null && A.file(id) != null
                && A.file(id).mpStr("gameKey", "a").equals(B.file(id).mpStr("gameKey", "b")));
        check("moves are validated, applied and ACKed", play(A, B, "e2e4", "e7e5", "g1f3"));
        check("both saves identical after ACKs (no pending)", sameMoves(A, B, id) && A.file(id).plyCount() == 3);
        check("a move is refused when it is not your turn", !A.s.playMove(Move.fromUci("d2d4")));

        // --- illegal / out-of-order move rejection (black, the guest, injects raw MOVE messages)
        B.t.send(Message.of(Message.MOVE, "ply", 9L, "move", "b8c6", "hash", "x"));
        check("out-of-order move rejected -> desync on both sides", waitFor(() -> A.desyncs.stream().anyMatch(d -> d.contains("out of order"))
                && A.s.status() == netSession.status.DESYNC && B.s.status() == netSession.status.DESYNC, 3000));
        B.s.resync();
        check("re-sync after desync -> states match", waitFor(() -> A.playing() && B.playing(), 3000));
        B.t.send(Message.of(Message.MOVE, "ply", 4L, "move", "e8e1", "hash", "x"));
        check("illegal incoming move rejected, nothing applied", waitFor(() -> A.desyncs.stream().anyMatch(d -> d.contains("illegal move e8e1")), 3000)
                && A.file(id).plyCount() == 3 && !A.s.canMove());
        A.s.resync();
        check("re-sync again, play continues", waitFor(() -> A.playing() && B.playing(), 3000) && play(A, B, "b8c6"));

        // --- disconnect then automatic reconnect with identical states
        A.notices.clear();
        A.t.dropPeer();
        check("drop: both pause, no moves allowed", waitFor(() -> !A.s.canMove() && !B.s.canMove() && B.s.status() != netSession.status.PLAYING, 3000));
        check("guest auto-reconnects, states match", waitFor(() -> A.playing() && B.playing(), 8000)
                && A.notices.stream().anyMatch(n -> n.startsWith("States match")));

        // --- un-ACKed pending move: the guest never receives the host's move
        java.util.concurrent.atomic.AtomicInteger lost = new java.util.concurrent.atomic.AtomicInteger();
        B.t.inboundFilter = m -> { if (m.type().equals(Message.MOVE)) { lost.incrementAndGet(); return false; } return true; };
        A.s.playMove(Move.fromUci("f1b5"));
        check("un-ACKed move is stored as pending", waitFor(() -> lost.get() == 1 && A.file(id).hasPending(), 2000) && B.file(id).plyCount() == 4);
        B.t.inboundFilter = m -> true;
        A.t.dropPeer();
        check("pending move settled by resume (catch-up accepted)", waitFor(() -> A.playing() && B.playing() && sameMoves(A, B, id), 10000)
                && B.file(id).plyCount() == 5 && B.proposals.stream().anyMatch(p -> p.description().contains("1 more move")));
        check("catch-up backed up the guest's old file", backups(B, id) >= 1);
        if (failed > 0) diag(A, B, id);
        A.leave();
        B.leave();

        // --- identical resume after both left (Rejoin from Saved Games, one hosts, one joins)
        A.notices.clear();
        A.host(A.file(id), 0);
        B.join(portOf(A), B.file(id));
        check("rejoin: identical saves -> 'States match' and play resumes", waitFor(() -> A.playing() && B.playing(), 5000)
                && A.notices.stream().anyMatch(n -> n.startsWith("States match")) && play(A, B, "a7a6"));
        A.leave();
        B.leave();

        // --- prefix: host ahead
        rewrite(A, id, "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4");
        long bBackups = backups(B, id);
        A.host(A.file(id), 0);
        B.join(portOf(A), B.file(id));
        check("prefix (host ahead): guest asked 'Opponent has 1 more move', accepts, both match",
                waitFor(() -> A.playing() && B.playing() && sameMoves(A, B, id), 6000) && B.file(id).plyCount() == 7
                        && B.proposals.get(B.proposals.size() - 1).description().contains("1 more move"));
        check("prefix: behind side's file backed up before overwrite", backups(B, id) == bBackups + 1);
        A.leave();
        B.leave();

        // --- prefix: guest ahead
        rewrite(B, id, "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6");
        A.proposals.clear();
        A.host(A.file(id), 0);
        B.join(portOf(A), B.file(id));
        check("prefix (guest ahead): host accepts the catch-up", waitFor(() -> A.playing() && B.playing() && sameMoves(A, B, id), 6000)
                && A.file(id).plyCount() == 8 && !A.proposals.isEmpty());
        A.leave();
        B.leave();

        // --- divergence, reject path: game stays paused, no file changes
        rewrite(A, id, "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6", "e1g1");
        rewrite(B, id, "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6", "d2d3");
        String aHead = A.file(id).headHash, bHead = B.file(id).headHash;
        A.proposeOnDiverge = netSession.MINE;
        B.answerProposals = Boolean.FALSE;
        A.host(A.file(id), 0);
        B.join(portOf(A), B.file(id));
        check("divergence detected on both sides (first differing ply 9)", waitFor(() -> A.syncs.stream().anyMatch(x -> x.kind() == netSession.syncKind.DIVERGED && x.commonPly() == 8)
                && B.syncs.stream().anyMatch(x -> x.kind() == netSession.syncKind.DIVERGED && x.theirSans().equals(List.of("O-O"))), 5000));
        check("divergence: guest rejects the proposal", waitFor(() -> B.proposals.stream().anyMatch(p -> p.description().contains("THEIR version")), 3000));
        try { Thread.sleep(400); } catch (InterruptedException ignored) { }
        check("divergence reject path: game stays paused, saves untouched", !A.s.canMove() && !B.s.canMove()
                && A.file(id).headHash.equals(aHead) && B.file(id).headHash.equals(bHead));

        // --- divergence, accept path (same connection): guest now proposes roll-back, host accepts
        A.answerProposals = Boolean.TRUE;
        B.s.propose(netSession.ROLLBACK);
        check("divergence accept path: roll back both to the last common position", waitFor(() -> A.playing() && B.playing() && sameMoves(A, B, id), 6000)
                && A.file(id).plyCount() == 8);
        check("roll-back backed up the host's file first", backups(A, id) >= 1);
        A.leave();
        B.leave();
        A.proposeOnDiverge = null;
        B.answerProposals = Boolean.TRUE;

        // --- divergence accept path, "use mine"
        rewrite(A, id, "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6", "e1g1");
        rewrite(B, id, "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6", "d2d3");
        B.proposeOnDiverge = netSession.MINE;
        A.host(A.file(id), 0);
        B.join(portOf(A), B.file(id));
        check("divergence 'use mine' (guest's line) accepted by host", waitFor(() -> A.playing() && B.playing() && sameMoves(A, B, id), 6000)
                && A.file(id).moves.get(8).san.equals("d3"));
        A.leave();
        B.leave();
        B.proposeOnDiverge = null;

        // --- result mismatch (one side thinks the game ended by resignation)
        savedGame resigned = A.file(id);
        gameState rg = resigned.toGameState();
        rg.resign(Position.BLACK);
        resigned.syncFrom(rg);
        saveManager.save(A.dir, resigned);
        A.proposeOnDiverge = netSession.THEIRS;
        A.host(A.file(id), 0);
        B.join(portOf(A), B.file(id));
        check("result mismatch detected and resolved by proposal + accept", waitFor(() -> A.playing() && B.playing() && sameMoves(A, B, id), 6000)
                && A.syncs.stream().anyMatch(x -> x.kind() == netSession.syncKind.RESULT_MISMATCH) && !A.file(id).isFinished());
        A.leave();
        B.leave();
        A.proposeOnDiverge = null;

        // --- wrong key
        savedGame tampered = B.file(id);
        tampered.multiplayer.put("gameKey", "00112233445566778899aabbccddeeff");
        saveManager.save(B.dir, tampered);
        String headBefore = A.file(id).headHash;
        A.notices.clear();
        A.host(A.file(id), 0);
        B.join(portOf(A), B.file(id));
        check("wrong key refused with a clear message", waitFor(() -> A.notices.stream().anyMatch(n -> n.contains("could not prove"))
                && B.status == netSession.status.FAILED, 5000));
        try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
        check("wrong key: guest stops retrying, nothing overwritten", B.s.status() == netSession.status.FAILED && A.file(id).headHash.equals(headBefore));
        A.leave();
        B.leave();

        // --- missing local save -> restore from opponent (needs accept)
        Files.delete(B.dir.resolve(id + ".json"));
        A.host(A.file(id), 0);
        B.join(portOf(A), null);
        check("missing local save: restored from opponent after accept", waitFor(() -> A.playing() && B.playing() && sameMoves(A, B, id), 6000)
                && B.syncs.stream().anyMatch(x -> x.kind() == netSession.syncKind.LOCAL_MISSING)
                && B.file(id).mpStr("localColor", "").equals("black") && B.file(id).mpStr("gameKey", "").equals(A.file(id).mpStr("gameKey", "?")));
        A.leave();
        B.leave();

        // --- corrupt local save -> renamed .corrupt, then restored
        Files.writeString(B.dir.resolve(id + ".json"), "{ corrupted", StandardCharsets.UTF_8);
        A.host(A.file(id), 0);
        B.join(portOf(A), null);
        check("corrupt local save: renamed .corrupt and restored", waitFor(() -> A.playing() && B.playing() && sameMoves(A, B, id), 6000)
                && Files.exists(B.dir.resolve(id + ".corrupt")) && B.notices.stream().anyMatch(n -> n.contains("unreadable")));
        check("restored game is playable", play(A, B, "f8e7"));
        A.leave();
        B.leave();

        // --- missing save, declined -> nothing written
        Files.delete(B.dir.resolve(id + ".json"));
        B.answerProposals = Boolean.FALSE;
        A.host(A.file(id), 0);
        B.join(portOf(A), null);
        waitFor(() -> !B.proposals.isEmpty() && B.proposals.get(B.proposals.size() - 1).choice().equals(netSession.RESTORE), 5000);
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        check("declined restore writes nothing", B.file(id) == null && !B.s.canMove());
        A.leave();
        B.leave();
        B.answerProposals = Boolean.TRUE;

        // --- wrong player and wrong game
        peer C = new peer("stranger", tempDir("stranger"), profile.of("player-carol", "Carol"));
        Files.copy(A.dir.resolve(id + ".json"), C.dir.resolve(id + ".json"));
        A.notices.clear();
        A.host(A.file(id), 0);
        C.join(portOf(A), C.file(id));
        check("wrong player refused", waitFor(() -> A.notices.stream().anyMatch(n -> n.contains("different player")) && C.status == netSession.status.FAILED, 5000));
        A.leave();
        C.leave();
        savedGame other = savedGame.create(savedGame.MULTIPLAYER, Position.START_FEN);
        other.multiplayer = Json.map("gameKey", "aa", "localColor", "black");
        saveManager.save(B.dir, other);
        A.notices.clear();
        A.host(A.file(id), 0);
        B.join(portOf(A), B.file(other.gameId));
        check("wrong game refused", waitFor(() -> B.notices.stream().anyMatch(n -> n.contains("different game")) && B.status == netSession.status.FAILED, 5000));
        A.leave();
        B.leave();

        // --- resign and draw over the wire, then rematch
        peer H = new peer("host2", tempDir("host2"), alice), G = new peer("guest2", tempDir("guest2"), bob);
        H.host(null, Position.BLACK);
        G.join(portOf(H), null);
        waitFor(() -> H.playing() && G.playing(), 5000);
        String id2 = H.s.state().gameId;
        check("host can choose black", H.s.localColor() == Position.BLACK && G.s.localColor() == Position.WHITE && play(G, H, "d2d4", "d7d5"));
        G.s.offerDraw();
        check("draw offer accepted on both sides", waitFor(() -> H.file(id2).isFinished() && G.file(id2).isFinished() && H.file(id2).result.equals("1/2-1/2"), 3000));
        H.s.offerRematch();
        check("rematch: new game with colours swapped", waitFor(() -> !H.s.state().gameId.equals(id2) && G.s.state().gameId.equals(H.s.state().gameId)
                && H.s.localColor() == Position.WHITE && G.playing(), 3000));
        String id3 = H.s.state().gameId;
        H.s.playMove(Move.fromUci("e2e4"));
        waitFor(() -> G.s.state().plyCount() == 1, 2000);
        G.s.resign();
        check("resignation reaches the opponent", waitFor(() -> H.file(id3).isFinished() && H.file(id3).result.equals("1-0"), 3000));
        H.leave();
        G.leave();

        // --- relay server: two clients paired by room code, moves forwarded, reconnect through the relay
        net.relay.relayServer relay = new net.relay.relayServer(0);
        relay.start();
        int rp = relay.port();
        peer RH = new peer("relayHost", tempDir("rh"), alice), RG = new peer("relayGuest", tempDir("rg"), bob);
        RH.relayHost(rp, "ROOM42", null, Position.WHITE);
        RG.relayJoin(rp, "ROOM42", null);
        check("relay: paired by room code, game starts", waitFor(() -> RH.playing() && RG.playing(), 8000));
        String rid = RH.s.state().gameId;
        check("relay: moves forwarded and ACKed", play(RH, RG, "e2e4", "c7c5"));
        RG.t.dropPeer();
        check("relay: both reconnect through the relay, states match", waitFor(() -> RH.playing() && RG.playing(), 15000) && sameMoves(RH, RG, rid));
        RH.leave();
        RG.leave();
        relay.stop();
    }
}
