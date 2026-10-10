package net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Connects through a relay server (net/relay/relayServer.java) that pairs two clients by room code
 * and forwards their lines. Both players connect outward, so no port forwarding is needed on either
 * side - but someone has to run the relay on a machine both can reach.
 * The link re-joins the room automatically after a drop, which gives the "host keeps listening" behaviour.
 */
public class relayTransport extends directTransport {
    public static final int DEFAULT_RELAY_PORT = 5758;

    private final String relayHost;
    private final String room;
    private volatile boolean linkRunning;

    public relayTransport(String relayHost, String room) {
        this.relayHost = relayHost;
        this.room = room;
    }

    public String room() { return room; }

    /** Host: join the room as "host" (relayHost:port), and keep re-joining while open. */
    @Override public void listen(int port) { maintain(relayHost, port, "host"); }

    /** Guest: join the room as "guest" at host:port (the relay), and keep re-joining while open. */
    @Override public void connect(String host, int port) { maintain(host, port, "guest"); }

    private synchronized void maintain(String host, int port, String role) {
        if (linkRunning) return;
        linkRunning = true;
        daemon("relay-link", () -> {
            int attempt = 0;
            while (!isClosed()) {
                if (isConnected()) { sleep(250); continue; }
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(host, port), 5000);
                    OutputStream out = s.getOutputStream();
                    out.write(("{\"type\":\"RELAY_JOIN\",\"room\":\"" + room + "\",\"role\":\"" + role + "\"}\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    String reply = readLine(s.getInputStream()); // blocks until the other player joins
                    if (reply == null || !reply.contains("RELAY_PAIRED")) throw new IOException("relay refused: " + reply);
                    if (isClosed()) { s.close(); return; }
                    attempt = 0;
                    attach(s);
                } catch (IOException e) {
                    try { s.close(); } catch (IOException ignored) { }
                    attempt++;
                    sleep(Math.min(15000, 1000L << Math.min(4, attempt - 1)));
                }
            }
        });
    }

    /** Reads one line byte by byte so nothing after it is buffered away from the real reader. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') return sb.toString();
            sb.append((char) c);
            if (sb.length() > 1000) throw new IOException("relay line too long");
        }
        return null;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
