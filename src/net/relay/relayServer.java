package net.relay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny standalone relay for ChessBot multiplayer. It pairs two clients by room code and forwards
 * their lines in both directions. It stores nothing and knows nothing about chess.
 *
 * Run it on any machine both players can reach (a cheap VPS, or a PC with one forwarded port):
 *     java src/net/relay/relayServer.java [port]        (default port 5758)
 *
 * Protocol: each client's first line is {"type":"RELAY_JOIN","room":"ABC123","role":"host"|"guest"}.
 * When a host and a guest are in the same room, both get {"type":"RELAY_PAIRED"} and from then on
 * every byte is forwarded. When either side disconnects, the other is disconnected too, and both may re-join.
 * Uses only the Java standard library (no other project classes) so it runs as a single file.
 */
public class relayServer {
    private static final Pattern JOIN = Pattern.compile("\"room\"\\s*:\\s*\"([A-Za-z0-9_-]{1,32})\".*\"role\"\\s*:\\s*\"(host|guest)\"");
    private final int requestedPort;
    private ServerSocket server;
    private final Map<String, Socket[]> waiting = new HashMap<>();
    private volatile boolean stopped;

    public relayServer(int port) { this.requestedPort = port; }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5758;
        relayServer r = new relayServer(port);
        r.start();
        System.out.println("ChessBot relay listening on port " + r.port() + " (Ctrl+C to stop)");
        Thread.currentThread().join();
    }

    public void start() throws IOException {
        server = new ServerSocket(requestedPort);
        thread("relay-accept", () -> {
            while (!stopped) {
                try {
                    Socket s = server.accept();
                    thread("relay-join", () -> join(s));
                } catch (IOException e) {
                    if (stopped) return;
                }
            }
        });
    }

    public int port() { return server.getLocalPort(); }

    public void stop() {
        stopped = true;
        try { server.close(); } catch (IOException ignored) { }
        synchronized (waiting) {
            for (Socket[] pair : waiting.values()) for (Socket s : pair) close(s);
            waiting.clear();
        }
    }

    private void join(Socket s) {
        try {
            s.setSoTimeout(15000);
            String first = readLine(s.getInputStream());
            s.setSoTimeout(0);
            Matcher m = first == null ? null : JOIN.matcher(first);
            if (m == null || !m.find()) { send(s, "{\"type\":\"RELAY_ERROR\",\"message\":\"bad join\"}"); close(s); return; }
            String room = m.group(1);
            int idx = m.group(2).equals("host") ? 0 : 1;
            Socket host, guest;
            synchronized (waiting) {
                Socket[] slot = waiting.computeIfAbsent(room, _ -> new Socket[2]);
                if (slot[idx] != null) close(slot[idx]); // a re-join replaces a stale waiting connection
                slot[idx] = s;
                if (slot[0] == null || slot[1] == null) return;
                waiting.remove(room);
                host = slot[0];
                guest = slot[1];
            }
            send(host, "{\"type\":\"RELAY_PAIRED\"}");
            send(guest, "{\"type\":\"RELAY_PAIRED\"}");
            thread("relay-pump", () -> pump(host, guest));
            thread("relay-pump", () -> pump(guest, host));
        } catch (IOException e) {
            close(s);
        }
    }

    private static void pump(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) != -1; ) { out.write(buf, 0, n); out.flush(); }
        } catch (IOException ignored) {
        } finally {
            close(from);
            close(to);
        }
    }

    private static void send(Socket s, String line) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') return sb.toString();
            sb.append((char) c);
            if (sb.length() > 1000) return null;
        }
        return null;
    }

    private static void close(Socket s) { try { if (s != null) s.close(); } catch (IOException ignored) { } }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
