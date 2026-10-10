package net;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Carries Messages between the two players, one JSON object per line over TCP.
 * Callbacks arrive on background threads.
 */
public interface transport {
    /** Host side: accept the opponent on this port; keeps listening after a drop so they can reconnect. */
    void listen(int port) throws IOException;

    /** Guest side: connect to the host (one attempt; the session retries with backoff). */
    void connect(String host, int port) throws IOException;

    void send(Message m) throws IOException;

    void onMessage(Consumer<Message> c);

    void onConnected(Runnable r);

    void onDisconnect(Runnable r);

    boolean isConnected();

    /** Closes the current connection only (a host keeps listening). */
    void dropPeer();

    /** Closes everything. */
    void close();
}
