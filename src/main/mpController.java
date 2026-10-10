package main;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

import components.Board;
import components.style;
import net.netSession;
import rules.Move;
import rules.Position;
import rules.gameState;
import saves.savedGame;

/** Connects a netSession to the game screen. Session callbacks arrive on network threads; everything here hops to Swing. */
class mpController implements netSession.listener {
    private final game screen;
    private final netSession session;
    private final JPanel panel = new JPanel(new BorderLayout(0, 4));
    private final JLabel label = style.label(" ", Font.PLAIN, 15);
    private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final String invite;
    private netSession.syncInfo lastSync;
    private boolean left;

    mpController(game screen, netSession session) {
        this.screen = screen;
        this.session = session;
        this.invite = multiplayerDialog.inviteFor(session);
        panel.setOpaque(false);
        label.setVerticalAlignment(SwingConstants.TOP);
        buttons.setOpaque(false);
        panel.add(label, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
    }

    JComponent connectionLabel() { return panel; }

    void start() {
        session.setListener(this);
        session.start();
        updateIndicator();
    }

    int localColor() { return session.localColor(); }
    boolean canMove() { return session.canMove(); }
    boolean canAct() { return session.isPlaying(); }

    String statusOverride() {
        if (session.isPlaying()) {
            gameState g = screen.state();
            if (!g.isOver() && g.current().sideToMove() != localColor()) return "Waiting for " + opponentName() + " to move...";
            return null;
        }
        netSession.status s = session.status();
        return switch (s == null ? netSession.status.CONNECTING : s) {
            case WAITING -> session.isEstablished() ? "Paused - waiting for opponent" : "Waiting for an opponent";
            case CONNECTING, HANDSHAKE -> "Connecting...";
            case SYNCING -> "Paused - comparing saves";
            case PAUSED -> "Paused - waiting for opponent";
            case DESYNC -> "Paused - out of sync";
            case FAILED -> "Not connected";
            case CLOSED -> "Left the game";
            default -> null;
        };
    }

    void localMove(Move m) {
        if (!session.playMove(m)) screen.refresh();
    }

    void resign() { session.resign(); }
    void offerDraw() { session.offerDraw(); flash("Draw offered"); }

    void rematch() {
        if (session.isHost()) session.offerRematch();
        else { session.requestRematch(); flash("Rematch requested - waiting for the host"); }
    }

    void leave() {
        if (left) return;
        left = true;
        session.leave();
    }

    private String opponentName() {
        savedGame s = session.state();
        if (s == null) return "your opponent";
        return localColor() == Position.WHITE ? s.blackName : s.whiteName;
    }

    // ---------- session callbacks (network threads) ----------

    @Override public void onStatus(netSession.status s, String text) { ui(this::updateIndicator); }

    @Override public void onState(savedGame state) {
        ui(() -> {
            screen.replaceSave(state);
            Board b = screen.board();
            b.setFlipped(localColor() == Position.BLACK);
            updateIndicator();
        });
    }

    @Override public void onGameOffer(String hostName, int yourColor) {
        ui(() -> {
            int c = style.choose(screen, "Game invitation", hostName + " invites you to a game. You will play " + (yourColor == Position.WHITE ? "White" : "Black") + ".",
                    "Accept", "Decline");
            session.answerOffer(c == 0);
            if (c != 0 && !session.isEstablished()) screen.leaveToMenu();
        });
    }

    @Override public void onRematchRequested() {
        ui(() -> {
            if (style.choose(screen, "Rematch", opponentName() + " would like a rematch (colours swapped). Start one?", "Start rematch", "Not now") == 0)
                session.offerRematch();
        });
    }

    @Override public void onDrawOffer() {
        ui(() -> session.answerDraw(style.choose(screen, "Draw offer", opponentName() + " offers a draw. Accept?", "Accept draw", "Decline") == 0));
    }

    @Override public void onDrawAnswer(boolean accepted) { if (!accepted) ui(() -> flash("Draw declined")); }

    @Override public void onSync(netSession.syncInfo info) {
        ui(() -> {
            lastSync = info;
            updateIndicator();
            if (info.kind() == netSession.syncKind.DIVERGED || info.kind() == netSession.syncKind.RESULT_MISMATCH) showComparison(info);
        });
    }

    @Override public void onProposal(netSession.proposal p) {
        ui(() -> session.answerProposal(style.choose(screen, "Opponent's proposal", p.description()
                + "\n\nYour current save is backed up before anything changes.", "Accept", "Reject") == 0));
    }

    @Override public void onProposalAnswer(boolean accepted) {
        if (!accepted) ui(() -> style.info(screen, "Proposal rejected", opponentName()
                + " rejected your proposal. The game stays paused until you both agree - use \"Resolve...\" to choose again."));
    }

    @Override public void onNotice(String title, String text) {
        ui(() -> {
            if (title.equals("States match")) {
                closeDialogs("Saves differ"); // an open comparison dialog is no longer relevant
                flash("States match - game resumed");
            }
            else style.info(screen, title, text);
        });
    }

    @Override public void onDesync(String reason) {
        ui(() -> {
            updateIndicator();
            int c = style.choose(screen, "Out of sync", "The game is paused because the two boards disagree:\n" + reason
                    + "\n\nRe-sync compares both saves again; nothing is changed without both players agreeing.", "Re-sync", "Later");
            if (c == 0) session.resync();
        });
    }

    // ---------- UI pieces ----------

    private void updateIndicator() {
        netSession.status s = session.status();
        Color dot = switch (s == null ? netSession.status.CONNECTING : s) {
            case PLAYING -> new Color(46, 139, 87);
            case FAILED, DESYNC, CLOSED -> new Color(190, 30, 30);
            default -> new Color(214, 140, 20);
        };
        String hex = String.format("#%02x%02x%02x", dot.getRed(), dot.getGreen(), dot.getBlue());
        String text = session.statusText();
        if (s == netSession.status.WAITING && invite != null) text += "<br>Invite: <b>" + invite + "</b>";
        label.setText("<html><div style='width:390px'><font color='" + hex + "' size='+1'>•</font> " + text + "</div></html>");
        buttons.removeAll();
        if (s == netSession.status.WAITING && invite != null) buttons.add(small("Copy invite", () -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(invite), null);
            flash("Invite copied");
        }));
        if (s == netSession.status.SYNCING && lastSync != null
                && (lastSync.kind() == netSession.syncKind.DIVERGED || lastSync.kind() == netSession.syncKind.RESULT_MISMATCH))
            buttons.add(small("Resolve...", () -> showComparison(lastSync)));
        if (s == netSession.status.DESYNC) buttons.add(small("Re-sync", session::resync));
        panel.revalidate();
        panel.repaint();
        screen.refresh();
    }

    /** Divergence / result mismatch: show the common position and both versions, then propose. */
    private void showComparison(netSession.syncInfo info) {
        gameState common = new gameState(info.startFen());
        Position p = common.current();
        for (String san : info.commonSans()) {
            Move m = rules.notation.fromSan(p, san);
            if (m == null) break;
            common.play(m);
            p = common.current();
        }
        JPanel body = new JPanel(new BorderLayout(14, 0));
        miniBoard mini = new miniBoard(common.current());
        body.add(mini, BorderLayout.WEST);
        String head = info.kind() == netSession.syncKind.RESULT_MISMATCH
                ? "Both saves have the same moves but a different result."
                : "Your saves agree up to ply " + info.commonPly() + " (shown) and then differ.";
        String html = "<html><div style='width:360px'>" + head + "<br><br>"
                + "<b>Your version:</b> " + (info.mySans().isEmpty() ? "(no further moves)" : String.join(" ", info.mySans())) + "  [" + info.myResult() + "]<br><br>"
                + "<b>" + info.opponentName() + "'s version:</b> " + (info.theirSans().isEmpty() ? "(no further moves)" : String.join(" ", info.theirSans())) + "  [" + info.theirResult() + "]<br><br>"
                + "Pick one; your opponent must accept it. Mismatched choices keep the game paused. Your save is backed up before it is changed.</div></html>";
        body.add(style.messageLabel(html), BorderLayout.CENTER);
        int c = style.choose(screen, "Saves differ", body, "Roll back both", "Use mine", "Use theirs", "Decide later");
        if (c == 0) session.propose(netSession.ROLLBACK);
        else if (c == 1) session.propose(netSession.MINE);
        else if (c == 2) session.propose(netSession.THEIRS);
        if (c >= 0 && c <= 2) flash("Proposal sent - waiting for " + opponentName());
    }

    private void flash(String text) { screen.flash(text); }

    private static void closeDialogs(String title) {
        for (Window w : Window.getWindows())
            if (w instanceof Dialog d && d.isShowing() && title.equals(d.getTitle())) d.dispose();
    }

    private static JButton small(String text, Runnable r) {
        JButton b = style.button(text, style.YELLOW, 14, r);
        b.setPreferredSize(new Dimension(Math.max(90, b.getPreferredSize().width + 16), 24));
        return b;
    }

    private static void ui(Runnable r) { SwingUtilities.invokeLater(r); }

    /** Small read-only board for the comparison dialog. */
    private static final class miniBoard extends JComponent {
        private final Position pos;
        miniBoard(Position pos) { this.pos = pos; setPreferredSize(new Dimension(240, 240)); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int sq = 30;
            for (int i = 0; i < 64; i++) {
                int r = i / 8, c = i % 8;
                g.setColor((r + c) % 2 == 0 ? new Color(125, 80, 66) : new Color(80, 42, 42));
                g.fillRect(c * sq, r * sq, sq, sq);
                int piece = pos.pieceAt(i);
                if (piece != 0) {
                    Image img = Board.smallPiece(piece, 26);
                    if (img != null) g.drawImage(img, c * sq + (sq - img.getWidth(null)) / 2, r * sq + (sq - img.getHeight(null)) / 2, null);
                }
            }
        }
    }
}
