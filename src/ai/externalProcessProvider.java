package ai;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import handlers.Json;
import rules.Move;
import rules.Position;

/**
 * SKELETON bridge to an engine running as a separate process (e.g. the future Python/TensorFlow engine).
 * It spawns the command, then speaks one JSON object per line over the process's stdin/stdout.
 * The full wire contract is in docs/ENGINE_HOOK.md. Summary:
 *
 *   engine -> app : {"type":"ready","name":"..."}            (send once the model is loaded; until then isAvailable() is false)
 *   app -> engine : {"type":"move","id":1,"position":{...},"history":[[[6,4],[4,4]], ...],"limits":{...}}
 *   engine -> app : {"type":"move","id":1,"move":[[6,4],[4,4]]}       or {"type":"error","id":1,"message":"..."}
 *   app -> engine : {"type":"analyze","id":2,"position":{...},"maxLines":3}
 *   engine -> app : {"type":"analysis","id":2,"lines":[{"move":[[..],[..]],"centipawns":35,"mateIn":null,"depth":8,"line":[...],"comment":""}]}
 *   app -> engine : {"type":"cancel","id":1}   /   {"type":"quit"}
 *
 * Lines on stdout that are not JSON (debug prints) are ignored; stderr is echoed to the console.
 * Not done yet (deliberately thin): restarting a crashed process, request queueing/back-pressure, versioning.
 */
public class externalProcessProvider implements moveProvider, analysisProvider {
    private final String displayName;
    private final String[] command;
    private Process process;
    private BufferedWriter toEngine;
    private volatile boolean ready;
    private volatile boolean closed;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<Map<String, Object>>> waiting = new ConcurrentHashMap<>();
    private volatile long currentMoveId = -1;

    /** @param command executable and arguments, run from the project root, e.g. ("python", "engine/bridge.py") */
    public externalProcessProvider(String displayName, String... command) {
        this.displayName = displayName;
        this.command = command;
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "engine-shutdown"));
        Thread t = new Thread(this::start, "engine-start");
        t.setDaemon(true);
        t.start(); // slow model loads happen in the background
    }

    private void start() {
        try {
            process = new ProcessBuilder(command).start();
            if (closed) { process.destroy(); return; }
            toEngine = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            daemon("engine-stdout", () -> readLoop(new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))));
            daemon("engine-stderr", () -> {
                try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    for (String l; (l = err.readLine()) != null; ) System.err.println("[engine] " + l);
                } catch (IOException ignored) { }
            });
            send(Json.map("type", "hello", "protocol", 1L));
        } catch (IOException e) {
            System.err.println("Could not start engine " + String.join(" ", command) + ": " + e.getMessage());
        }
    }

    private void readLoop(BufferedReader in) {
        try {
            for (String line; (line = in.readLine()) != null; ) {
                Map<String, Object> msg;
                try { msg = Json.parseObject(line); } catch (RuntimeException notJson) { continue; }
                String type = Json.str(msg, "type", "");
                if (type.equals("ready")) { ready = true; continue; }
                CompletableFuture<Map<String, Object>> f = waiting.remove(Json.num(msg, "id", -1));
                if (f == null) continue;
                if (type.equals("error")) f.completeExceptionally(new IllegalStateException(Json.str(msg, "message", "engine error")));
                else f.complete(msg);
            }
        } catch (IOException ignored) {
        } finally {
            ready = false;
            IllegalStateException dead = new IllegalStateException("engine process exited");
            waiting.values().forEach(f -> f.completeExceptionally(dead));
            waiting.clear();
        }
    }

    private CompletableFuture<Map<String, Object>> request(Map<String, Object> msg) {
        long id = ids.incrementAndGet();
        msg.put("id", id);
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        waiting.put(id, f);
        try { send(msg); } catch (IOException e) { waiting.remove(id); f.completeExceptionally(e); }
        return f;
    }

    private synchronized void send(Map<String, Object> msg) throws IOException {
        if (toEngine == null) throw new IOException("engine not started");
        toEngine.write(Json.write(msg));
        toEngine.write('\n');
        toEngine.flush();
    }

    @Override public String name() { return displayName; }

    @Override public boolean isAvailable() { return ready && process != null && process.isAlive(); }

    @Override
    public CompletableFuture<Move> chooseMove(Position pos, List<Move> history, searchLimits limits) {
        List<Object> h = new ArrayList<>();
        for (Move m : history) h.add(m.toEngineForm());
        Map<String, Object> msg = Json.map("type", "move", "position", pos.toEngineForm(), "history", h, "limits", limits.toJson());
        CompletableFuture<Map<String, Object>> f = request(msg);
        currentMoveId = Json.num(msg, "id", -1);
        return f.thenApply(r -> Move.fromEngineForm(r.get("move")));
    }

    @Override
    public void cancel() {
        long id = currentMoveId;
        if (id < 0) return;
        CompletableFuture<Map<String, Object>> f = waiting.remove(id);
        if (f != null) f.cancel(true);
        try { send(Json.map("type", "cancel", "id", id)); } catch (IOException ignored) { }
    }

    @Override
    public CompletableFuture<List<Suggestion>> analyze(Position pos, int maxLines) {
        return request(Json.map("type", "analyze", "position", pos.toEngineForm(), "maxLines", (long) maxLines)).thenApply(r -> {
            List<Suggestion> out = new ArrayList<>();
            for (Object o : Json.list(r, "lines")) {
                @SuppressWarnings("unchecked") Suggestion s = o instanceof Map ? Suggestion.fromJson((Map<String, Object>) o) : null;
                if (s != null) out.add(s);
            }
            return out;
        });
    }

    /** Asks the engine to exit (call on shutdown if you keep a reference). */
    public void shutdown() {
        closed = true;
        try { send(Json.map("type", "quit")); } catch (IOException ignored) { }
        if (process != null) process.destroy();
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
