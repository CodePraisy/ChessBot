package main;

import javax.swing.*;
import java.awt.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import components.style;
import handlers.paths;
import net.directTransport;
import net.netSession;
import net.relayTransport;
import net.transport;
import rules.Position;
import saves.profile;
import saves.savedGame;

/**
 * Host / Join dialogs for online games, plus Rejoin from Saved Games.
 * Direct = this computer listens (LAN, or internet with port forwarding / VPN).
 * Relay  = both players connect out to a relay server someone runs (java src/net/relay/relayServer.java).
 */
final class multiplayerDialog {
    private multiplayerDialog() {}

    private static final Map<netSession, String> invites = Collections.synchronizedMap(new WeakHashMap<>());

    static String inviteFor(netSession s) { return invites.get(s); }

    /** Multiplayer "Start": host or join a new game. */
    static void open(mainWindow w) {
        profile me = ensureProfile(w);
        if (me == null) return;
        int c = style.choose(w, "Multiplayer", "Hi " + me.name + "! Host a new game, or join a friend's?\n\nTo continue an unfinished online game, use Rejoin in Saved Games.",
                "Host a game", "Join a game", "Cancel");
        if (c == 0) host(w, me, null);
        else if (c == 1) join(w, me, null);
    }

    /** Saved Games -> Rejoin: one player hosts, the other joins; both must have (or be offered) the save. */
    static void rejoin(mainWindow w, savedGame s) {
        profile me = ensureProfile(w);
        if (me == null) return;
        me = profile.of(s.mpStr("localPlayerId", me.playerId), me.name); // keep the identity this game was played with
        boolean wasHost = "host".equals(s.mpStr("role", "host"));
        String opp = "white".equals(s.mpStr("localColor", "white")) ? s.blackName : s.whiteName;
        int c = style.choose(w, "Rejoin game", "Rejoin your game against " + opp + " (" + (s.plyCount() + 1) / 2 + " moves played).\n\nOne of you hosts and the other joins. "
                + "When you connect, both saves are compared and nothing changes unless you both agree.", wasHost ? "Host" : "Join", wasHost ? "Join instead" : "Host instead", "Cancel");
        if (c < 0 || c == 2) return;
        boolean host = (c == 0) == wasHost;
        if (host) host(w, me, s); else join(w, me, s);
    }

    // ---------- host ----------

    private static void host(mainWindow w, profile me, savedGame resume) {
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 6));
        JRadioButton direct = new JRadioButton("Direct (this computer listens)", true), relay = new JRadioButton("Through a relay server");
        ButtonGroup bg = new ButtonGroup();
        bg.add(direct);
        bg.add(relay);
        JTextField port = new JTextField(String.valueOf(resume == null ? directTransport.DEFAULT_PORT : handlers.Json.num(resume.multiplayer, "port", directTransport.DEFAULT_PORT)));
        JTextField relayAddr = new JTextField("relay-host:" + relayTransport.DEFAULT_RELAY_PORT);
        JTextField room = new JTextField(roomCode());
        JComboBox<String> colour = new JComboBox<>(new String[]{"White", "Black", "Random"});
        p.add(style.label("Connection:", Font.BOLD, 18));
        p.add(direct);
        p.add(relay);
        p.add(style.label("Port (direct):", Font.PLAIN, 16));
        p.add(port);
        p.add(style.label("Relay address and room code (relay):", Font.PLAIN, 16));
        p.add(relayAddr);
        p.add(room);
        if (resume == null) {
            p.add(style.label("You play:", Font.BOLD, 18));
            p.add(colour);
        }
        p.add(style.label("<html><i>Your addresses: " + String.join(", ", localAddresses()) + "</i></html>", Font.PLAIN, 14));
        style.deepFont(p, style.font(Font.PLAIN, 16));
        if (style.choose(w, resume == null ? "Host a game" : "Host (rejoin)", p, "Start hosting", "Cancel") != 0) return;

        transport t;
        int listenPort;
        String invite;
        try {
            if (direct.isSelected()) {
                listenPort = Integer.parseInt(port.getText().trim());
                t = new directTransport();
                List<String> ips = localAddresses();
                invite = "chessbot:" + (ips.isEmpty() ? "your-ip" : ips.get(0)) + ":" + listenPort;
            } else {
                String[] hp = splitHostPort(relayAddr.getText().trim(), relayTransport.DEFAULT_RELAY_PORT);
                listenPort = Integer.parseInt(hp[1]);
                String code = room.getText().trim();
                if (!code.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Room codes use letters, digits, - and _ only.");
                t = new relayTransport(hp[0], code);
                invite = "chessbot-relay:" + hp[0] + ":" + listenPort + "/" + code;
            }
        } catch (RuntimeException e) {
            style.info(w, "Check the settings", "That address or port doesn't look right: " + e.getMessage());
            return;
        }
        int c = colour.getSelectedIndex() == 0 ? Position.WHITE : colour.getSelectedIndex() == 1 ? Position.BLACK : (Math.random() < 0.5 ? Position.WHITE : Position.BLACK);
        netSession s = netSession.host(me, t, listenPort, resume, c, paths.savesDir());
        invites.put(s, invite);
        w.startGame(gameSetup.multiplayer(s));
    }

    // ---------- join ----------

    private static void join(mainWindow w, profile me, savedGame resume) {
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 6));
        String lastHost = resume == null ? "" : resume.mpStr("host", "");
        String last = lastHost.isEmpty() ? "" : lastHost + ":" + handlers.Json.num(resume.multiplayer, "port", directTransport.DEFAULT_PORT);
        JTextField addr = new JTextField(last, 28);
        p.add(style.label("Paste the host's invite, or type host:port", Font.BOLD, 18));
        p.add(addr);
        p.add(style.label("<html><i>Examples: chessbot:192.168.1.20:5757 &nbsp; or &nbsp; chessbot-relay:relay.example.com:5758/ROOM42</i></html>", Font.PLAIN, 13));
        style.deepFont(addr, style.font(Font.PLAIN, 17));
        if (style.choose(w, resume == null ? "Join a game" : "Join (rejoin)", p, "Connect", "Cancel") != 0) return;

        transport t;
        String host;
        int port;
        try {
            String text = addr.getText().trim();
            if (text.startsWith("chessbot-relay:")) {
                String rest = text.substring("chessbot-relay:".length());
                int slash = rest.lastIndexOf('/');
                if (slash < 0) throw new IllegalArgumentException("relay invites end with /ROOM");
                String[] hp = splitHostPort(rest.substring(0, slash), relayTransport.DEFAULT_RELAY_PORT);
                host = hp[0];
                port = Integer.parseInt(hp[1]);
                t = new relayTransport(host, rest.substring(slash + 1));
            } else {
                if (text.startsWith("chessbot:")) text = text.substring("chessbot:".length());
                String[] hp = splitHostPort(text, directTransport.DEFAULT_PORT);
                host = hp[0];
                port = Integer.parseInt(hp[1]);
                t = new directTransport();
            }
            if (host.isEmpty()) throw new IllegalArgumentException("missing host");
        } catch (RuntimeException e) {
            style.info(w, "Check the address", "That address doesn't look right: " + e.getMessage());
            return;
        }
        netSession s = netSession.join(me, t, host, port, resume, paths.savesDir());
        w.startGame(gameSetup.multiplayer(s));
    }

    // ---------- helpers ----------

    private static profile ensureProfile(Component parent) {
        profile p = profile.load();
        if (p != null) return p;
        JTextField name = new JTextField(18);
        style.deepFont(name, style.font(Font.PLAIN, 18));
        JPanel body = new JPanel(new GridLayout(0, 1, 0, 6));
        body.add(style.label("Choose a display name for online games:", Font.BOLD, 18));
        body.add(name);
        while (true) {
            if (style.choose(parent, "Your name", body, "Save", "Cancel") != 0) return null;
            String n = name.getText().trim();
            if (n.isEmpty() || n.length() > 24) { style.info(parent, "Name", "Please use 1-24 characters."); continue; }
            try {
                return profile.save(n);
            } catch (Exception e) {
                style.info(parent, "Could not save profile", e.getMessage());
                return null;
            }
        }
    }

    static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) out.add(a.getHostAddress());
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static String[] splitHostPort(String text, int defaultPort) {
        int i = text.lastIndexOf(':');
        if (i < 0) return new String[]{text, String.valueOf(defaultPort)};
        return new String[]{text.substring(0, i), text.substring(i + 1)};
    }

    private static String roomCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
