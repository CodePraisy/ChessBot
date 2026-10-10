package net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import handlers.Json;
import rules.Move;
import rules.Position;
import rules.gameState;
import saves.hashChain;
import saves.profile;
import saves.saveManager;
import saves.savedGame;

/**
 * Two-player online game over a transport. Rules enforced here:
 *  - moves can only be made while both players are connected and their states agree;
 *  - every incoming move is validated with the rules layer and the hash chain;
 *  - a move is committed only after the opponent's ACK (until then it is saved as "pending");
 *  - on every (re)connection the players prove knowledge of the game key (HMAC-SHA256 over a fresh nonce),
 *    compare hash lists, and any difference needs an explicit proposal + acceptance before a save is changed
 *    (the old file is backed up first). No silent merges.
 * All callbacks happen on background threads; the UI must hop to Swing itself.
 */
public final class netSession {
    public static final int PROTOCOL = 1;
    public static final long PING_EVERY_MS = 5000, DROP_AFTER_MS = 15000;

    public enum status { CONNECTING, WAITING, HANDSHAKE, SYNCING, PLAYING, PAUSED, DESYNC, FAILED, CLOSED }
    public enum syncKind { BEHIND, AHEAD, DIVERGED, RESULT_MISMATCH, LOCAL_MISSING, PEER_MISSING }

    /** Proposal choices, always from the proposer's point of view. */
    public static final String MINE = "MINE", THEIRS = "THEIRS", ROLLBACK = "ROLLBACK", RESTORE = "RESTORE";

    /** What differs between the two saves (for the comparison dialog). */
    public record syncInfo(syncKind kind, String startFen, List<String> commonSans, List<String> mySans, List<String> theirSans,
                           String myResult, String theirResult, String opponentName) {
        public int commonPly() { return commonSans.size(); }
    }

    /** An opponent's proposal, already validated, described for the accept/reject dialog. */
    public record proposal(String id, String choice, String description) {}

    public interface listener {
        default void onStatus(status s, String text) {}
        default void onState(savedGame state) {}
        default void onGameOffer(String hostName, int yourColor) {}
        default void onRematchRequested() {}
        default void onDrawOffer() {}
        default void onDrawAnswer(boolean accepted) {}
        default void onSync(syncInfo info) {}
        default void onProposal(proposal p) {}
        default void onProposalAnswer(boolean accepted) {}
        default void onNotice(String title, String text) {}
        default void onDesync(String reason) {}
    }

    // ---------- configuration ----------
    private final boolean isHost;
    private final profile me;
    private final transport t;
    private final String host;
    private final int port;
    private final Path savesDir;
    private final String intentGameId;      // rejoin: the saved game chosen by the user
    private listener l = new listener() {};

    // ---------- game state ----------
    private savedGame game;                 // our copy (host: placeholder until the guest accepts a new game)
    private boolean established;            // a real, saved game both sides know about
    private savedGame pendingOffer;         // host: offered game not yet accepted
    private String missingGameId;           // guest: game the host wants to resume, but we have no readable save
    private volatile status st;
    private String statusText = "";
    private volatile boolean closed;

    // ---------- per-connection state ----------
    private String myNonce, peerNonce, peerId, peerName, peerGameId;
    private boolean peerHasSave, helloDone, authNeeded, peerVerified, resumeSent, movesSent;
    private Message peerResume, peerMoves, gameOffer;
    private String peerAddress;
    private int common;                     // plies shared by both saves while syncing
    private String myProposalId, myProposalChoice;
    private Message theirProposal;
    private volatile long lastHeard;
    private int reconnectAttempt;
    private int round;                      // sync round: a RESUME with a higher round restarts the comparison
    private boolean refused;                // a fatal refusal (wrong key/game/player): the guest stops retrying

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread th = new Thread(r, "net-timer");
        th.setDaemon(true);
        return th;
    });
    private static final SecureRandom RNG = new SecureRandom();

    private netSession(boolean isHost, profile me, transport t, String host, int port, Path savesDir, savedGame resume, int hostColor) {
        this.isHost = isHost;
        this.me = me;
        this.t = t;
        this.host = host;
        this.port = port;
        this.savesDir = savesDir;
        this.intentGameId = resume == null ? null : resume.gameId;
        if (resume != null) {
            game = resume.copy();
            established = true;
        } else if (isHost) {
            game = newGame(hostColor, me, null, null);
        }
    }

    /** Host a new game (hostColor = Position.WHITE/BLACK) or rejoin a saved one (resume != null). */
    public static netSession host(profile me, transport t, int port, savedGame resume, int hostColor, Path savesDir) {
        return new netSession(true, me, t, null, port, savesDir, resume, hostColor);
    }

    /** Join a host; resume = the saved game to rejoin, or null to accept whatever the host offers or resumes. */
    public static netSession join(profile me, transport t, String host, int port, savedGame resume, Path savesDir) {
        return new netSession(false, me, t, host, port, savesDir, resume, 0);
    }

    public void setListener(listener listener) { l = listener == null ? new listener() {} : listener; }

    // ---------- public API (called from the UI) ----------

    public synchronized void start() {
        t.onMessage(this::handle);
        t.onConnected(this::connected);
        t.onDisconnect(this::disconnected);
        timer.scheduleAtFixedRate(this::heartbeat, PING_EVERY_MS, PING_EVERY_MS, TimeUnit.MILLISECONDS);
        if (isHost) {
            try {
                t.listen(port);
                setStatus(status.WAITING, established ? "Waiting for your opponent to rejoin..." : "Waiting for an opponent to join...");
            } catch (IOException e) {
                setStatus(status.FAILED, "Could not listen on port " + port + ": " + e.getMessage());
                l.onNotice("Cannot host", "Could not listen on port " + port + ": " + e.getMessage());
            }
        } else {
            setStatus(status.CONNECTING, "Connecting to " + host + ":" + port + "...");
            timer.execute(this::tryConnect);
        }
    }

    public synchronized savedGame state() { return game == null ? null : game.copy(); }
    public synchronized status status() { return st; }
    public synchronized String statusText() { return statusText; }
    public boolean isHost() { return isHost; }
    public synchronized boolean isEstablished() { return established; }

    public synchronized int localColor() {
        if (game == null) return Position.WHITE;
        return "black".equals(game.mpStr("localColor", "white")) ? Position.BLACK : Position.WHITE;
    }

    /** True when a local move may be made right now. */
    public synchronized boolean canMove() {
        if (st != status.PLAYING || !t.isConnected() || game == null || game.isFinished()) return false;
        return game.toGameState().current().sideToMove() == localColor();
    }

    public synchronized boolean isPlaying() { return st == status.PLAYING && t.isConnected(); }

    public synchronized boolean playMove(Move m) {
        if (!canMove()) return false;
        gameState g = game.toGameState();
        if (!g.play(m)) return false;
        game.syncFrom(g);
        savedGame.moveRecord rec = game.moves.get(game.moves.size() - 1);
        rec.pending = true;
        persist();
        l.onState(game.copy());
        sendQuiet(Message.of(Message.MOVE, "gameId", game.gameId, "ply", (long) rec.ply, "move", m.uci(), "hash", rec.hash));
        return true;
    }

    public synchronized void resign() {
        if (st != status.PLAYING || game == null || game.isFinished()) return;
        long ply = game.plyCount();
        String head = game.headHash;
        gameState g = game.toGameState();
        g.resign(localColor());
        game.syncFrom(g);
        persist();
        l.onState(game.copy());
        sendQuiet(Message.of(Message.RESIGN, "ply", ply, "hash", head));
    }

    public synchronized void offerDraw() {
        if (st != status.PLAYING || game == null || game.isFinished()) return;
        sendQuiet(Message.of(Message.DRAW_OFFER, "ply", (long) game.plyCount()));
    }

    public synchronized void answerDraw(boolean accept) {
        if (game == null || game.isFinished()) return;
        if (accept) {
            gameState g = game.toGameState();
            g.agreeDraw();
            game.syncFrom(g);
            persist();
            l.onState(game.copy());
            sendQuiet(Message.of(Message.DRAW_ACCEPT, "ply", (long) game.plyCount()));
        } else {
            sendQuiet(Message.of(Message.DRAW_DECLINE, "ply", (long) game.plyCount()));
        }
    }

    /** Guest: answer a GAME_OFFER. */
    public synchronized void answerOffer(boolean accept) {
        Message o = gameOffer;
        gameOffer = null;
        if (o == null) return;
        if (!accept) {
            sendQuiet(Message.of(Message.GAME_DECLINE, "gameId", o.str("gameId")));
            return;
        }
        int hostColor = "black".equals(o.str("hostColor")) ? Position.BLACK : Position.WHITE;
        savedGame g = newGame(-hostColor, me, o.str("hostPlayerId"), o.str("hostName"));
        g.gameId = o.str("gameId");
        g.startFen = Position.fromFen(o.str("startFen")).toFen();
        g.multiplayer.put("gameKey", o.str("gameKey"));
        g.headHash = hashChain.hash0(g.gameId, g.startFen);
        game = g;
        established = true;
        persist();
        sendQuiet(Message.of(Message.GAME_ACCEPT, "gameId", g.gameId, "playerId", me.playerId, "name", me.name));
        setStatus(status.PLAYING, "Connected - game on!");
        l.onState(game.copy());
    }

    /** Host: offer a new game (colours swapped) after the current one finished. */
    public synchronized void offerRematch() {
        if (!isHost || !t.isConnected() || game == null) return;
        int newColor = -localColor();
        pendingOffer = newGame(newColor, me, peerId, peerName);
        sendOffer(pendingOffer);
        l.onNotice("Rematch", "Rematch offered - waiting for your opponent to accept.");
    }

    /** Guest: ask the host for a rematch. */
    public synchronized void requestRematch() { sendQuiet(Message.of(Message.REMATCH_REQUEST)); }

    /** Propose how to reconcile different saves: MINE, THEIRS or ROLLBACK (from our point of view). */
    public synchronized void propose(String choice) {
        if (st != status.SYNCING && st != status.DESYNC) return;
        TargetState target = target(choice, true);
        if (target == null) { l.onNotice("Cannot propose", "That option is not possible here."); return; }
        myProposalId = UUID.randomUUID().toString();
        myProposalChoice = choice;
        sendQuiet(Message.of(Message.SYNC_PROPOSE, "proposalId", myProposalId, "choice", choice,
                "targetPly", (long) target.moves.size(), "targetHead", target.head(game)));
    }

    /** Answer the opponent's proposal. Accepting backs up and rewrites our save, then both re-check. */
    public synchronized void answerProposal(boolean accept) {
        Message p = theirProposal;
        theirProposal = null;
        if (p == null) return;
        String id = p.str("proposalId");
        if (!accept) {
            sendQuiet(Message.of(Message.SYNC_REJECT, "proposalId", id));
            return;
        }
        TargetState target = target(p.str("choice"), false);
        if (target == null) { sendQuiet(Message.of(Message.SYNC_REJECT, "proposalId", id, "reason", "invalid")); return; }
        if (!apply(target)) { sendQuiet(Message.of(Message.SYNC_REJECT, "proposalId", id, "reason", "could not save")); return; }
        sendQuiet(Message.of(Message.SYNC_ACCEPT, "proposalId", id, "targetHead", game.headHash));
        restartSync();
    }

    /** After a desync: compare states again. */
    public synchronized void resync() {
        if (!t.isConnected()) return;
        restartSync();
    }

    /** Leave (the game stays saved; both can rejoin later). */
    public void leave() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            sendQuiet(Message.of(Message.LEAVE));
            if (established) persist();
            setStatus(status.CLOSED, "Left the game");
        }
        timer.shutdownNow();
        t.close();
    }

    public String peerName() { return peerName; }

    // ---------- connection lifecycle ----------

    private void tryConnect() {
        if (closed || t.isConnected()) return;
        try {
            t.connect(host, port);
        } catch (IOException e) {
            synchronized (this) {
                if (closed) return;
                reconnectAttempt++;
                long delay = Math.min(15, 1L << Math.min(4, reconnectAttempt - 1));
                setStatus(established ? status.PAUSED : status.CONNECTING,
                        (established ? "Waiting for opponent... (host unreachable" : "Can't reach " + host + ":" + port + " (") + " - retrying in " + delay + "s, attempt " + reconnectAttempt + ")");
                timer.schedule(this::tryConnect, delay, TimeUnit.SECONDS);
            }
        }
    }

    private synchronized void connected() {
        if (closed) { t.dropPeer(); return; }
        reconnectAttempt = 0;
        myNonce = randomHex(16);
        peerNonce = peerId = peerName = peerGameId = null;
        peerHasSave = helloDone = authNeeded = peerVerified = resumeSent = movesSent = false;
        peerResume = peerMoves = theirProposal = gameOffer = null;
        myProposalId = null;
        round = 0;
        refused = false;
        lastHeard = System.currentTimeMillis();
        setStatus(status.HANDSHAKE, "Connected - checking identities...");
        if (isHost) sendHello();
    }

    private synchronized void disconnected() {
        if (closed) return;
        if (established) persist();
        setStatus(established ? status.PAUSED : (isHost ? status.WAITING : status.CONNECTING),
                established ? "Waiting for opponent... (connection lost; the game is paused and saved)" : isHost ? "Waiting for an opponent to join..." : "Connection lost - reconnecting...");
        if (refused) { setStatus(status.FAILED, statusText); return; }
        if (!isHost) timer.schedule(this::tryConnect, 1, TimeUnit.SECONDS);
    }

    private void heartbeat() {
        if (closed || !t.isConnected()) return;
        if (System.currentTimeMillis() - lastHeard > DROP_AFTER_MS) { t.dropPeer(); return; }
        sendQuiet(Message.of(Message.PING, "t", System.currentTimeMillis()));
    }

    // ---------- message dispatch ----------

    private synchronized void handle(Message m) {
        if (closed) return;
        lastHeard = System.currentTimeMillis();
        try {
            switch (m.type()) {
                case Message.PING -> sendQuiet(Message.of(Message.PONG, "t", m.num("t", 0)));
                case Message.PONG -> { }
                case Message.HELLO -> onHello(m);
                case Message.ERROR -> onError(m);
                case Message.AUTH -> onAuth(m);
                case Message.GAME_OFFER -> onGameOffer(m);
                case Message.GAME_ACCEPT -> onGameAccept(m);
                case Message.GAME_DECLINE -> { pendingOffer = isHost && established ? null : pendingOffer; l.onNotice("Declined", "Your opponent declined the game."); }
                case Message.REMATCH_REQUEST -> { if (isHost) l.onRematchRequested(); }
                case Message.RESUME -> onResume(m);
                case Message.SYNC_MOVES -> { peerMoves = m; classify(); }
                case Message.SYNC_PROPOSE -> onPropose(m);
                case Message.SYNC_ACCEPT -> onSyncAccept(m);
                case Message.SYNC_REJECT -> { if (m.str("proposalId").equals(myProposalId)) { myProposalId = null; l.onProposalAnswer(false); } }
                case Message.MOVE -> onMove(m);
                case Message.ACK -> onAck(m);
                case Message.REJECT -> desync("Your opponent rejected move " + m.num("ply", 0) + ": " + m.str("reason"));
                case Message.RESIGN -> onResign(m);
                case Message.DRAW_OFFER -> { if (st == status.PLAYING && m.num("ply", -1) == game.plyCount()) l.onDrawOffer(); }
                case Message.DRAW_ACCEPT -> onDrawAccept(m);
                case Message.DRAW_DECLINE -> l.onDrawAnswer(false);
                case Message.LEAVE -> l.onNotice("Opponent left", (peerName == null ? "Your opponent" : peerName) + " left. The game is saved - you can both rejoin it later from Saved Games.");
                default -> { }
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            desync("Bad message from opponent (" + m.type() + "): " + e.getMessage());
        }
    }

    // ---------- handshake ----------

    private void sendHello() {
        String gid = established ? game.gameId : missingGameId;
        sendQuiet(Message.of(Message.HELLO, "v", (long) PROTOCOL, "playerId", me.playerId, "name", me.name,
                "gameId", gid, "hasSave", established, "nonce", myNonce));
    }

    private void onHello(Message m) {
        if (m.num("v", 0) != PROTOCOL) { fail("VERSION", "The other player runs an incompatible version."); return; }
        peerId = m.str("playerId");
        peerName = m.str("name");
        peerNonce = m.str("nonce");
        peerGameId = m.str("gameId");
        peerHasSave = m.bool("hasSave");
        if (peerId == null || peerNonce == null) { fail("BAD_HELLO", "Malformed greeting."); return; }

        if (isHost) {
            if (!established) {
                if (peerGameId != null) { fail("WRONG_GAME", "The host is starting a new game, but you chose to rejoin a saved game."); return; }
                helloDone = true;
                sendOffer(game);
                setStatus(status.HANDSHAKE, (peerName == null ? "Opponent" : peerName) + " connected - waiting for them to accept...");
                return;
            }
            if (peerGameId != null && !peerGameId.equals(game.gameId)) { fail("WRONG_GAME", "The host is resuming a different game."); return; }
            if (!peerId.equals(opponentId())) { fail("WRONG_PLAYER", "This saved game belongs to a different player."); return; }
        } else {
            String wanted = established ? game.gameId : intentGameId;
            if (peerGameId == null) {
                if (wanted != null) { fail("WRONG_GAME", "The host is starting a new game, but you chose to rejoin a saved game."); return; }
                sendHello();
                helloDone = true;
                setStatus(status.HANDSHAKE, "Connected to " + peerName + " - waiting for their game offer...");
                return;
            }
            if (wanted != null && !wanted.equals(peerGameId)) { fail("WRONG_GAME", "The host is resuming a different game than the one you chose."); return; }
            if (game == null || !game.gameId.equals(peerGameId)) {
                game = saveManager.load(savesDir, peerGameId);   // a corrupt file is renamed .corrupt and reported
                established = game != null;
                missingGameId = game == null ? peerGameId : null;
                List<String> problems = saveManager.takeProblems();
                if (!problems.isEmpty()) l.onNotice("Local save unreadable", String.join("\n", problems));
            }
            if (established && !peerId.equals(opponentId())) { fail("WRONG_PLAYER", "This saved game belongs to a different player."); return; }
            sendHello();
        }
        helloDone = true;
        authNeeded = established && peerHasSave;
        if (!established && !peerHasSave) { fail("NO_SAVE", "Neither player has this game saved, so it cannot be resumed."); return; }
        setStatus(status.HANDSHAKE, authNeeded ? "Verifying the game key..." : "Comparing saves...");
        if (authNeeded) sendQuiet(Message.of(Message.AUTH, "mac", hmac(gameKey(), peerNonce + ":" + game.gameId + ":" + me.playerId)));
        else sendResume();
    }

    private void onAuth(Message m) {
        if (!authNeeded || peerVerified) return;
        String expected = hmac(gameKey(), myNonce + ":" + game.gameId + ":" + peerId);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), String.valueOf(m.str("mac")).getBytes(StandardCharsets.UTF_8))) {
            fail("WRONG_KEY", "The other player could not prove they have this game's key. Connection refused.");
            return;
        }
        peerVerified = true;
        sendResume();
    }

    private void onError(Message m) {
        refused = true;
        String text = m.str("message");
        setStatus(status.FAILED, "Refused: " + text);
        l.onNotice("Connection refused", text);
        t.dropPeer();
    }

    private void fail(String code, String text) {
        refused = true;
        sendQuiet(Message.of(Message.ERROR, "code", code, "message", text));
        setStatus(status.FAILED, "Refused: " + text);
        l.onNotice("Connection refused", text);
        timer.schedule(t::dropPeer, 200, TimeUnit.MILLISECONDS); // let the ERROR line go out first
    }

    // ---------- new game / rematch ----------

    private savedGame newGame(int myColor, profile mine, String oppId, String oppName) {
        savedGame g = savedGame.create(savedGame.MULTIPLAYER, Position.START_FEN);
        boolean white = myColor == Position.WHITE;
        g.whiteName = white ? mine.name : (oppName == null ? "Opponent" : oppName);
        g.whiteId = white ? mine.playerId : (oppId == null ? "" : oppId);
        g.blackName = white ? (oppName == null ? "Opponent" : oppName) : mine.name;
        g.blackId = white ? (oppId == null ? "" : oppId) : mine.playerId;
        g.multiplayer = Json.map("gameKey", randomHex(16), "localColor", white ? "white" : "black", "localPlayerId", mine.playerId,
                "role", isHost ? "host" : "guest", "host", host, "port", (long) port);
        return g;
    }

    private void sendOffer(savedGame g) {
        sendQuiet(Message.of(Message.GAME_OFFER, "gameId", g.gameId, "startFen", g.startFen, "hostColor", g.mpStr("localColor", "white"),
                "gameKey", g.mpStr("gameKey", ""), "hostName", me.name, "hostPlayerId", me.playerId));
    }

    private void onGameOffer(Message m) {
        if (isHost) return;
        if (intentGameId != null && !established) { fail("WRONG_GAME", "You chose to rejoin a saved game, but the host offered a new one."); return; }
        gameOffer = m;
        int yourColor = "black".equals(m.str("hostColor")) ? Position.WHITE : Position.BLACK;
        l.onGameOffer(m.str("hostName"), yourColor);
    }

    private void onGameAccept(Message m) {
        if (!isHost) return;
        savedGame offered = established ? pendingOffer : game;
        if (offered == null || !offered.gameId.equals(m.str("gameId"))) return;
        boolean hostWhite = "white".equals(offered.mpStr("localColor", "white"));
        if (hostWhite) { offered.blackId = m.str("playerId"); offered.blackName = m.str("name"); }
        else { offered.whiteId = m.str("playerId"); offered.whiteName = m.str("name"); }
        peerName = m.str("name");
        game = offered;
        pendingOffer = null;
        established = true;
        persist();
        setStatus(status.PLAYING, "Connected - game on!");
        l.onState(game.copy());
    }

    // ---------- resume & agreement ----------

    private void sendResume() {
        resumeSent = true;
        if (game == null) {
            sendQuiet(Message.of(Message.RESUME, "round", (long) round, "gameId", missingGameId, "missing", true));
        } else {
            sendQuiet(Message.of(Message.RESUME, "round", (long) round, "gameId", game.gameId, "plyCount", (long) game.plyCount(), "headHash", game.headHash,
                    "hashes", game.hashes(), "status", game.status, "result", game.result, "termination", game.termination, "missing", false));
        }
        compare();
    }

    private void onResume(Message m) {
        if (!helloDone) return;
        if (authNeeded && !peerVerified) return;
        int r = (int) m.num("round", 0);
        if (r < round) return; // stale
        if (r > round) { round = r; resetRound(); }
        peerResume = m;
        if (!resumeSent) sendResume(); else compare();
    }

    /** Called once both RESUMEs are known. */
    private void compare() {
        if (!resumeSent || peerResume == null || movesSent) return;
        setStatus(status.SYNCING, "Comparing saves...");
        boolean iMissing = game == null, peerMissing = peerResume.bool("missing");
        if (iMissing && peerMissing) { fail("NO_SAVE", "Neither player has this game saved."); return; }
        if (iMissing) {
            movesSent = true;
            l.onSync(new syncInfo(syncKind.LOCAL_MISSING, Position.START_FEN, List.of(), List.of(), List.of(), "?", "?", peerName));
            setStatus(status.SYNCING, "Your save of this game is missing - waiting for your opponent's copy...");
            return;
        }
        if (peerMissing) {
            movesSent = true;
            common = 0;
            sendQuiet(Message.of(Message.SYNC_MOVES, "fromPly", 0L, "moves", ucis(game.moveList()), "status", game.status, "result", game.result,
                    "termination", game.termination, "startFen", game.startFen, "full", fullCopy()));
            l.onSync(new syncInfo(syncKind.PEER_MISSING, game.startFen, List.of(), sans(game, 0), List.of(), game.result, "?", peerName));
            setStatus(status.SYNCING, "Your opponent's save is missing - offering them your copy...");
            propose(RESTORE);
            return;
        }
        List<String> mine = game.hashes(), theirs = peerResume.strings("hashes");
        int c = 0;
        while (c < mine.size() && c < theirs.size() && mine.get(c).equals(theirs.get(c))) c++;
        common = c;
        boolean sameResult = game.status.equals(peerResume.str("status")) && game.result.equals(peerResume.str("result"));
        if (mine.equals(theirs) && sameResult && game.headHash.equals(peerResume.str("headHash"))) { statesMatch(); return; }
        movesSent = true;
        sendQuiet(Message.of(Message.SYNC_MOVES, "fromPly", (long) c, "moves", ucis(game.moveList().subList(c, game.plyCount())),
                "status", game.status, "result", game.result, "termination", game.termination));
        classify();
    }

    /** Once both SYNC_MOVES are known, describe the difference and let the UI (or auto-catch-up) act. */
    private void classify() {
        if (!movesSent || peerMoves == null || game == null) return;
        TargetState theirs = theirState();
        if (theirs == null) { desync("Your opponent's save contains illegal moves, so it cannot be used."); return; }
        int mineN = game.plyCount(), theirN = theirs.moves.size();
        syncKind kind;
        if (mineN == theirN && common == mineN) kind = syncKind.RESULT_MISMATCH;
        else if (common == theirN && mineN > common && !theirs.finished()) kind = syncKind.AHEAD;
        else if (common == mineN && theirN > common && !game.isFinished()) kind = syncKind.BEHIND;
        else kind = syncKind.DIVERGED;
        List<String> commonSans = new ArrayList<>(), mySans = sans(game, common), theirSans = new ArrayList<>();
        gameState g = new gameState(game.startFen);
        for (int i = 0; i < theirs.moves.size(); i++) {
            Move m = theirs.moves.get(i);
            String s = rules.notation.toSan(g.current(), m);
            if (i < common) commonSans.add(s); else theirSans.add(s);
            g.play(m);
        }
        l.onSync(new syncInfo(kind, game.startFen, commonSans, mySans, theirSans, game.result, theirs.result, peerName));
        if (kind == syncKind.AHEAD) {
            setStatus(status.SYNCING, "You have " + (mineN - common) + " move(s) your opponent is missing - asking them to accept...");
            propose(MINE);
        } else if (kind == syncKind.BEHIND) {
            setStatus(status.SYNCING, "Your opponent has " + (theirN - common) + " more move(s) - waiting for their proposal...");
        } else {
            setStatus(status.SYNCING, "Your saves differ - choose how to continue (the game is paused).");
        }
    }

    private void onPropose(Message m) {
        if (st != status.SYNCING && st != status.DESYNC) return;
        String choice = m.str("choice");
        TargetState target = target(choice, false);
        if (target == null) { sendQuiet(Message.of(Message.SYNC_REJECT, "proposalId", m.str("proposalId"), "reason", "invalid")); return; }
        theirProposal = m;
        l.onProposal(new proposal(m.str("proposalId"), choice, describe(choice, target)));
    }

    private void onSyncAccept(Message m) {
        if (myProposalId == null || !myProposalId.equals(m.str("proposalId"))) return;
        TargetState target = target(myProposalChoice, true);
        myProposalId = null;
        if (target == null || !apply(target)) { desync("Could not apply the agreed state."); return; }
        l.onProposalAnswer(true);
        setStatus(status.SYNCING, "Agreed - re-checking both saves...");
    }

    /** Starts a new comparison round (after accepting a proposal, or "Re-sync" after a desync). */
    private void restartSync() {
        round++;
        resetRound();
        sendResume();
    }

    private void resetRound() {
        peerResume = null;
        peerMoves = null;
        resumeSent = movesSent = false;
        theirProposal = null;
    }

    private void statesMatch() {
        boolean changed = false;
        for (savedGame.moveRecord r : game.moves) if (r.pending) { r.pending = false; changed = true; }
        if (changed) persist();
        peerMoves = null;
        movesSent = false;
        setStatus(status.PLAYING, "States match - connected");
        l.onNotice("States match", "Both saves agree (" + game.plyCount() + " moves). Game resumed.");
        l.onState(game.copy());
    }

    // ---------- target states ----------

    private record TargetState(List<Move> moves, String status, String result, String termination, Map<String, Object> full) {
        boolean finished() { return savedGame.FINISHED.equals(status); }
        String head(savedGame g) { return hashChain.head(full != null ? Json.str(full, "gameId", g.gameId) : g.gameId, g.startFen, moves); }
    }

    private TargetState myState() {
        return new TargetState(game.moveList(), game.status, game.result, game.termination, null);
    }

    /** The opponent's state rebuilt from our common prefix + their SYNC_MOVES tail; null if illegal or unknown. */
    private TargetState theirState() {
        if (peerMoves == null) return null;
        Map<String, Object> full = peerMoves.obj("full");
        List<Move> moves = new ArrayList<>();
        String fen;
        if (full != null) {
            fen = Json.str(full, "startFen", Position.START_FEN);
        } else {
            if (game == null) return null;
            fen = game.startFen;
            moves.addAll(game.moveList().subList(0, Math.min(common, game.plyCount())));
        }
        for (String u : peerMoves.strings("moves")) {
            Move m = Move.fromUci(u);
            if (m == null) return null;
            moves.add(m);
        }
        try {
            gameState.replay(fen, moves);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new TargetState(moves, Json.str(peerMoves.body, "status", savedGame.IN_PROGRESS), Json.str(peerMoves.body, "result", "*"),
                Json.str(peerMoves.body, "termination", "ONGOING"), full);
    }

    /** What the agreed state would be, for a choice made by us (proposerIsMe) or by the opponent. */
    private TargetState target(String choice, boolean proposerIsMe) {
        if (choice == null) return null;
        if (RESTORE.equals(choice)) {
            if (proposerIsMe) return game == null ? null : new TargetState(game.moveList(), game.status, game.result, game.termination, null);
            return game != null ? null : theirState();
        }
        if (game == null) return null;
        TargetState mine = myState(), theirs = theirState();
        if (theirs == null) return null;
        return switch (choice) {
            case MINE -> proposerIsMe ? mine : theirs;
            case THEIRS -> proposerIsMe ? theirs : mine;
            case ROLLBACK -> new TargetState(new ArrayList<>(game.moveList().subList(0, common)), savedGame.IN_PROGRESS, "*", "ONGOING", null);
            default -> null;
        };
    }

    private String describe(String choice, TargetState target) {
        String who = peerName == null ? "Your opponent" : peerName;
        int mine = game == null ? 0 : game.plyCount();
        return switch (choice) {
            case RESTORE -> "Your save of this game is missing or unreadable. " + who + " offers to restore it from their copy ("
                    + target.moves.size() + " moves" + (target.finished() ? ", result " + target.result : "") + "). Accept?";
            case MINE -> (target.moves.size() > mine && common == mine
                    ? who + " has " + (target.moves.size() - mine) + " more move(s) (ply " + mine + " to " + target.moves.size() + "). Accept?"
                    : who + " proposes using THEIR version of the game (" + target.moves.size() + " moves, result " + target.result + "). Accept?");
            case THEIRS -> who + " proposes using YOUR version of the game (" + target.moves.size() + " moves, result " + target.result + "). Accept?";
            case ROLLBACK -> who + " proposes rolling both games back to the last position you agree on (ply " + target.moves.size() + "). Accept?";
            default -> who + " proposes: " + choice;
        };
    }

    /** Writes the agreed state after backing up the current file. */
    private boolean apply(TargetState target) {
        try {
            savedGame next;
            if (game == null) {
                if (target.full == null) return false;
                next = savedGame.fromJson(target.full);
                next.multiplayer = Json.obj(target.full, "multiplayer");
                if (next.multiplayer == null) return false;
                // the copy is the opponent's: swap the "local" fields to us
                String theirColor = next.mpStr("localColor", "white");
                next.multiplayer.put("localColor", "white".equals(theirColor) ? "black" : "white");
                next.multiplayer.put("localPlayerId", me.playerId);
                next.multiplayer.put("role", isHost ? "host" : "guest");
                next.multiplayer.put("host", host);
                next.multiplayer.put("port", (long) port);
            } else {
                saveManager.backup(savesDir, game.gameId);
                next = game.copy();
            }
            gameState g = gameState.replay(next.startFen, target.moves);
            if (savedGame.FINISHED.equals(target.status) && !g.isOver()) {
                int winner = "1-0".equals(target.result) ? 1 : "0-1".equals(target.result) ? -1 : 0;
                gameState.Status s;
                try { s = gameState.Status.valueOf(target.termination); } catch (IllegalArgumentException e) { s = gameState.Status.DRAW_AGREED; }
                g.forceResult(s, winner);
            }
            next.moves = new ArrayList<>();
            next.syncFrom(g);
            for (savedGame.moveRecord r : next.moves) r.pending = false;
            saveManager.save(savesDir, next);
            game = next;
            established = true;
            missingGameId = null;
            l.onState(game.copy());
            return true;
        } catch (Exception e) {
            l.onNotice("Save failed", "Could not write the agreed game: " + e.getMessage());
            return false;
        }
    }

    // ---------- moves ----------

    private void onMove(Message m) {
        long ply = m.num("ply", -1);
        Move mv = Move.fromUci(m.str("move"));
        String why = null;
        if (st != status.PLAYING || game == null) why = "not in a synced game";
        else if (game.isFinished()) why = "the game is already over";
        else if (ply != game.plyCount() + 1) why = "out of order (expected ply " + (game.plyCount() + 1) + ", got " + ply + ")";
        else {
            gameState g = game.toGameState();
            if (g.current().sideToMove() == localColor()) why = "it is not the opponent's turn";
            else if (mv == null || !g.current().isLegal(mv)) why = "illegal move " + m.str("move");
            else if (!hashChain.next(game.headHash, mv).equals(m.str("hash"))) why = "hash mismatch";
            else {
                g.play(mv);
                game.syncFrom(g);
                persist();
                sendQuiet(Message.of(Message.ACK, "ply", ply, "hash", game.headHash));
                l.onState(game.copy());
                return;
            }
        }
        sendQuiet(Message.of(Message.REJECT, "ply", ply, "reason", why));
        desync("Rejected the opponent's move " + ply + ": " + why);
    }

    private void onAck(Message m) {
        if (game == null) return;
        long ply = m.num("ply", -1);
        if (ply < 1 || ply > game.plyCount()) { desync("Opponent acknowledged a move we never made (ply " + ply + ")"); return; }
        savedGame.moveRecord r = game.moves.get((int) ply - 1);
        if (!r.hash.equals(m.str("hash"))) { desync("Acknowledgement hash mismatch at ply " + ply); return; }
        if (r.pending) { r.pending = false; persist(); l.onState(game.copy()); }
    }

    private void onResign(Message m) {
        if (game == null || game.isFinished()) return;
        if (m.num("ply", -1) != game.plyCount() || !game.headHash.equals(m.str("hash"))) { desync("Resignation does not match our position."); return; }
        gameState g = game.toGameState();
        g.resign(-localColor());
        game.syncFrom(g);
        persist();
        l.onState(game.copy());
    }

    private void onDrawAccept(Message m) {
        if (game == null || game.isFinished()) return;
        if (m.num("ply", -1) != game.plyCount()) { l.onDrawAnswer(false); return; }
        gameState g = game.toGameState();
        g.agreeDraw();
        game.syncFrom(g);
        persist();
        l.onDrawAnswer(true);
        l.onState(game.copy());
    }

    private void desync(String reason) {
        setStatus(status.DESYNC, "Out of sync - game paused");
        l.onDesync(reason);
    }

    // ---------- helpers ----------

    private void persist() {
        if (game != null && established) saveManager.trySave(savesDir, game);
    }

    private void sendQuiet(Message m) {
        try { t.send(m); } catch (IOException ignored) { /* the disconnect callback handles it */ }
    }

    private void setStatus(status s, String text) {
        st = s;
        statusText = text;
        l.onStatus(s, text);
    }

    private String opponentId() {
        if (game == null) return "";
        return localColor() == Position.WHITE ? game.blackId : game.whiteId;
    }

    private String gameKey() { return game.mpStr("gameKey", ""); }

    private Map<String, Object> fullCopy() { return game.toJson(); }

    private static List<String> ucis(List<Move> moves) {
        List<String> l = new ArrayList<>();
        for (Move m : moves) l.add(m.uci());
        return l;
    }

    private static List<String> sans(savedGame g, int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < g.moves.size(); i++) out.add(g.moves.get(i).san);
        return out;
    }

    static String hmac(String keyHex, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(keyHex), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "invalid";
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
