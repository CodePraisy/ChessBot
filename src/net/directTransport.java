package net;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Plain TCP: the host listens (default port 5757), the guest connects to host:port.
 * Works on a LAN, and over the internet only with port forwarding or a VPN.
 * A new incoming connection replaces the old one, so a reconnecting guest is never locked out.
 */
public class directTransport implements transport {
    public static final int DEFAULT_PORT = 5757;

    private ServerSocket server;
    private volatile Socket socket;
    private BufferedWriter out;
    private volatile int generation;
    private volatile boolean closed;
    private Consumer<Message> onMessage = _ -> {};
    private Runnable onConnected = () -> {}, onDisconnect = () -> {};

    /** Test hook: return false to silently drop an incoming message (simulates loss on the wire). */
    public volatile Predicate<Message> inboundFilter = _ -> true;

    @Override public void onMessage(Consumer<Message> c) { onMessage = c; }
    @Override public void onConnected(Runnable r) { onConnected = r; }
    @Override public void onDisconnect(Runnable r) { onDisconnect = r; }
    @Override public boolean isConnected() { Socket s = socket; return s != null && !s.isClosed(); }

    @Override
    public void listen(int port) throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        daemon("net-accept", () -> {
            while (!closed) {
                try {
                    Socket s = server.accept();
                    attach(s);
                } catch (IOException e) {
                    if (closed) return;
                }
            }
        });
    }

    /** The port actually bound (useful when listening on port 0 in tests). */
    public int localPort() { return server == null ? -1 : server.getLocalPort(); }

    @Override
    public void connect(String host, int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        attach(s);
    }

    /** Installs s as the current connection, replacing (and closing) any previous one. */
    protected void attach(Socket s) throws IOException {
        s.setTcpNoDelay(true);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        Socket old;
        int gen;
        synchronized (this) {
            old = socket;
            socket = s;
            out = w;
            gen = ++generation;
        }
        if (old != null) try { old.close(); } catch (IOException ignored) { }
        onConnected.run();
        daemon("net-read", () -> readLoop(in, s, gen));
    }

    private void readLoop(BufferedReader in, Socket s, int gen) {
        try {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank()) continue;
                Message m;
                try { m = Message.parse(line); } catch (RuntimeException bad) { continue; }
                if (gen != generation) return;
                if (inboundFilter.test(m)) onMessage.accept(m);
            }
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
            boolean current;
            synchronized (this) {
                current = gen == generation && socket == s;
                if (current) { socket = null; out = null; }
            }
            if (current && !closed) onDisconnect.run();
        }
    }

    @Override
    public void send(Message m) throws IOException {
        synchronized (this) {
            if (out == null) throw new IOException("not connected");
            out.write(m.toLine());
            out.write('\n');
            out.flush();
        }
    }

    @Override
    public void dropPeer() {
        Socket s = socket;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() {
        closed = true;
        dropPeer();
        if (server != null) try { server.close(); } catch (IOException ignored) { }
    }

    protected boolean isClosed() { return closed; }

    protected static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
