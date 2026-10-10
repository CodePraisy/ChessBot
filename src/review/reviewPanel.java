package review;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import ai.Suggestion;
import ai.analysisProvider;
import ai.engineRegistry;
import components.Board;
import components.moveListPanel;
import components.style;
import main.mainWindow;
import rules.Move;
import rules.Position;
import rules.gameState;
import rules.notation;
import saves.saveManager;
import saves.savedGame;

/**
 * Read-only review of a saved game: board at the selected ply, move list, navigation (buttons and arrow keys),
 * engine suggestions (cached per ply in the save, shown even with no engine connected), "Analyze game" with
 * move-quality marks, and a scratch analysis board for trying side lines without touching the save.
 */
public class reviewPanel extends JPanel {
    private static final int LINES = 3;
    private static final long ANALYSIS_TIMEOUT_S = 30;

    private final mainWindow window;
    private final savedGame save;
    private final gameState g;
    private final boolean backToLibrary;

    private final Board board = new Board();
    private final evalBar bar = new evalBar(28, 720);
    private final moveListPanel list = new moveListPanel(410, 230);
    private final JLabel positionLabel = style.label(" ", Font.ITALIC, 17);
    private final JLabel engineStatus = style.label(" ", Font.ITALIC, 15);
    private final JLabel summary = style.label(" ", Font.PLAIN, 14);
    private final JPanel linesPanel = new JPanel(new GridLayout(LINES + 1, 1, 0, 2));
    private final JButton analyzeGameButton, cancelButton, backToGameButton;
    private final JProgressBar progress = new JProgressBar();

    private int ply;                                      // shown ply of the real game (0 = start)
    private final List<Position> branch = new ArrayList<>(); // side line: branch.get(0) = position at ply
    private final List<Move> branchMoves = new ArrayList<>();
    private final Map<String, List<Suggestion>> branchCache = new HashMap<>();
    private int viewToken;                                // invalidates late answers for a position no longer shown
    private boolean analyzingGame;
    private int analyzeToken;

    public reviewPanel(mainWindow window, savedGame save, boolean backToLibrary) {
        this.window = window;
        this.save = save;
        this.g = save.toGameState();
        this.backToLibrary = backToLibrary;
        setLayout(new BorderLayout());
        setBackground(style.BACKGROUND);

        JLabel title = new JLabel("Review", SwingConstants.CENTER);
        title.setFont(style.titleFont(52));
        title.setForeground(style.TEXT);
        area = new components.boardArea(title, board, bar);

        JPanel side = new components.roundedPanel(style.SIDE_PANEL);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(450, 950));
        side.setMaximumSize(new Dimension(450, 950));
        side.setBorder(BorderFactory.createEmptyBorder(14, 18, 18, 24));

        JLabel players = style.label("<html><b>" + esc(save.whiteName) + "</b> (White) vs <b>" + esc(save.blackName) + "</b> (Black)</html>", Font.PLAIN, 18);
        add(side, players, 50);
        add(side, style.label(save.isFinished() ? "Result: " + save.result + " - " + g.terminationText() : "Unfinished game", Font.ITALIC, 16), 26);
        side.add(Box.createVerticalStrut(6));
        add(side, list, 230);
        side.add(Box.createVerticalStrut(6));

        JPanel nav = new JPanel(new GridLayout(1, 4, 8, 0));
        nav.setOpaque(false);
        nav.add(style.button("|<", style.BLUE, 20, () -> go(0)));
        nav.add(style.button("<", style.BLUE, 20, () -> step(-1)));
        nav.add(style.button(">", style.BLUE, 20, () -> step(1)));
        nav.add(style.button(">|", style.BLUE, 20, () -> go(g.plyCount())));
        add(side, nav, 40);
        add(side, positionLabel, 28);
        backToGameButton = style.button("Leave side line (back to game)", style.YELLOW, 15, this::leaveBranch);
        add(side, backToGameButton, 28);
        side.add(Box.createVerticalStrut(6));

        JPanel sugBox = style.box(style.CARD);
        sugBox.setLayout(new BorderLayout(0, 4));
        JLabel sugTitle = style.label("Engine suggestions", Font.BOLD, 18);
        sugBox.add(sugTitle, BorderLayout.NORTH);
        linesPanel.setOpaque(false);
        sugBox.add(linesPanel, BorderLayout.CENTER);
        sugBox.add(engineStatus, BorderLayout.SOUTH);
        add(side, sugBox, 190);
        side.add(Box.createVerticalStrut(8));

        analyzeGameButton = style.button("Analyze game", style.PURPLE, 20, this::analyzeGame);
        cancelButton = style.button("Cancel", style.GRAY, 18, this::cancelAnalysis);
        JPanel analyzeRow = new JPanel(new BorderLayout(8, 0));
        analyzeRow.setOpaque(false);
        analyzeRow.add(analyzeGameButton, BorderLayout.CENTER);
        analyzeRow.add(cancelButton, BorderLayout.EAST);
        add(side, analyzeRow, 42);
        progress.setStringPainted(true);
        progress.setFont(style.font(Font.PLAIN, 13));
        add(side, progress, 22);
        summary.setVerticalAlignment(SwingConstants.TOP);
        add(side, summary, 60);
        side.add(Box.createVerticalGlue());
        add(side, style.button(backToLibrary ? "Back to Saved Games" : "Back to Menu", style.RED, 20,
                () -> { cancelAnalysis(); if (backToLibrary) window.showSavedGames(); else window.showMenu(); }), 44);

        add(area, BorderLayout.CENTER);
        add(main.game.sideColumn(side), BorderLayout.EAST);

        list.setMoves(g.sans(), g.positionAt(0).sideToMove() == Position.BLACK, g.positionAt(0).fullmoveNumber());
        list.setOnSelect(this::go);
        board.setOnMove(this::branchMove);
        if ("black".equals(save.mpStr("localColor", "")) || "black".equals(save.settingStr("humanColor", ""))) {
            board.setFlipped(true);
            bar.setFlipped(true);
        }
        installKeys();
        progress.setVisible(false);
        cancelButton.setVisible(false);
        recomputeMarks();
        go(0);
        engineTimer.start();
    }

    private final components.boardArea area;

    /** Full screen: hide the title so the board can use nearly the whole height. */
    public void onFullscreenChanged(boolean full) { area.setTitleVisible(!full); }

    private final Timer engineTimer = new Timer(1000, _ -> refreshEngineState());

    @Override public void removeNotify() {
        engineTimer.stop();
        analyzeToken++;
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        style.paintBackdrop((Graphics2D) g0, getWidth(), getHeight());
    }

    private static void add(JPanel side, JComponent c, int h) {
        c.setAlignmentX(Component.CENTER_ALIGNMENT);
        c.setPreferredSize(new Dimension(410, h));
        c.setMaximumSize(new Dimension(410, h));
        side.add(c);
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;"); }

    private void installKeys() {
        InputMap im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "prev");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next");
        getActionMap().put("prev", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { step(-1); } });
        getActionMap().put("next", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { step(1); } });
    }

    // ---------- navigation ----------

    private void step(int d) {
        if (!branch.isEmpty()) {
            if (d < 0) {
                branch.remove(branch.size() - 1);
                branchMoves.remove(branchMoves.size() - 1);
                if (branch.size() <= 1) { leaveBranch(); return; }
                render();
            }
            return;
        }
        go(ply + d);
    }

    private void go(int p) {
        branch.clear();
        branchMoves.clear();
        ply = Math.max(0, Math.min(g.plyCount(), p));
        render();
    }

    private void leaveBranch() { go(ply); }

    private void branchMove(Move m) {
        if (branch.isEmpty()) branch.add(g.positionAt(ply));
        Position cur = branch.get(branch.size() - 1);
        if (!cur.isLegal(m)) return;
        branch.add(cur.play(m));
        branchMoves.add(m);
        render();
    }

    private Position shownPosition() { return branch.isEmpty() ? g.positionAt(ply) : branch.get(branch.size() - 1); }

    private void render() {
        Position pos = shownPosition();
        Move last = !branch.isEmpty() ? branchMoves.get(branchMoves.size() - 1) : ply == 0 ? null : g.move(ply - 1);
        board.setPosition(pos, last);
        board.setMovable(Board.BOTH);
        list.setCurrentPly(branch.isEmpty() ? ply : -1);
        backToGameButton.setVisible(!branch.isEmpty());
        if (branch.isEmpty()) {
            positionLabel.setText(ply == 0 ? "Start position" : "After " + moveLabel(ply) + "   (ply " + ply + " of " + g.plyCount() + ")");
        } else {
            StringBuilder sb = new StringBuilder("Side line: ");
            Position p = branch.get(0);
            for (Move m : branchMoves) { sb.append(notation.toSan(p, m)).append(' '); p = p.play(m); }
            positionLabel.setText(sb.toString().trim());
        }
        updateSuggestions();
    }

    private String moveLabel(int p) {
        Position before = g.positionAt(p - 1);
        return before.fullmoveNumber() + (before.sideToMove() == Position.WHITE ? ". " : "... ") + g.san(p - 1);
    }

    // ---------- suggestions ----------

    /** Cached suggestions for a ply of the real game, or null. */
    private List<Suggestion> cached(int p) {
        Object o = save.analysis.get(String.valueOf(p));
        if (!(o instanceof List<?> raw)) return null;
        List<Suggestion> out = new ArrayList<>();
        for (Object item : raw) {
            @SuppressWarnings("unchecked") Suggestion s = item instanceof Map ? Suggestion.fromJson((Map<String, Object>) item) : null;
            if (s != null && g.positionAt(p).isLegal(s.move())) out.add(s);
        }
        return out.isEmpty() ? null : out;
    }

    private void store(int p, List<Suggestion> list) {
        List<Object> raw = new ArrayList<>();
        for (Suggestion s : list) raw.add(s.toJson());
        save.analysis.put(String.valueOf(p), raw);
        if (saveManager.exists(save.gameId)) saveManager.trySave(save);
    }

    private void updateSuggestions() {
        int token = ++viewToken;
        Position pos = shownPosition();
        List<Suggestion> s = branch.isEmpty() ? cached(ply) : branchCache.get(pos.toFen());
        if (pos.legalMoves().isEmpty()) {
            displayTerminal(pos);
            return;
        }
        if (s != null) { display(pos, s, branch.isEmpty() ? "Cached analysis" : "Analysis"); return; }
        analysisProvider a = engineRegistry.analysis();
        if (a == null || !a.isAvailable()) { display(pos, null, a == null ? "Engine analysis not connected" : "Engine is loading..."); return; }
        display(pos, null, "Analyzing...");
        final int atPly = ply;
        final boolean inBranch = !branch.isEmpty();
        request(a, pos).whenComplete((list, ex) -> SwingUtilities.invokeLater(() -> {
            if (ex != null || list == null || list.isEmpty()) {
                if (token == viewToken) display(pos, null, "Analysis failed: " + (ex == null ? "no lines" : rootMessage(ex)));
                return;
            }
            if (inBranch) branchCache.put(pos.toFen(), list); else { store(atPly, list); recomputeMarks(); }
            if (token == viewToken) display(pos, list, "Analysis");
        }));
    }

    private CompletableFuture<List<Suggestion>> request(analysisProvider a, Position pos) {
        try {
            CompletableFuture<List<Suggestion>> f = a.analyze(pos, LINES);
            if (f == null) return CompletableFuture.failedFuture(new IllegalStateException("provider returned no future"));
            return f.orTimeout(ANALYSIS_TIMEOUT_S, TimeUnit.SECONDS).thenApply(l -> {
                List<Suggestion> ok = new ArrayList<>();
                if (l != null) for (Suggestion s : l) if (s != null && pos.isLegal(s.move())) ok.add(s);
                return ok;
            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t instanceof java.util.concurrent.TimeoutException ? "timeout" : String.valueOf(t.getMessage());
    }

    private void display(Position pos, List<Suggestion> lines, String status) {
        linesPanel.removeAll();
        List<Move> arrows = new ArrayList<>();
        if (lines == null) {
            bar.setScore(null, "");
            JLabel l = style.label(status, Font.ITALIC, 16);
            linesPanel.add(l);
        } else {
            Suggestion best = lines.get(0);
            bar.setScore(best.whiteScore(pos.sideToMove()), whiteText(best, pos.sideToMove()));
            for (int i = 0; i < Math.min(LINES, lines.size()); i++) {
                Suggestion s = lines.get(i);
                arrows.add(s.move());
                linesPanel.add(style.label("<html><b>" + (i + 1) + ". " + notation.toSan(pos, s.move()) + "</b>&nbsp;&nbsp;" + whiteText(s, pos.sideToMove())
                        + "&nbsp;&nbsp;<i>d" + s.depth() + "</i>&nbsp;&nbsp;" + lineText(pos, s.line()) + "</html>", Font.PLAIN, 15));
            }
            if (best.comment() != null && !best.comment().isBlank()) linesPanel.add(style.label("<html><i>" + esc(best.comment()) + "</i></html>", Font.PLAIN, 13));
        }
        board.setArrows(arrows);
        engineStatus.setText(lines == null ? " " : status);
        linesPanel.revalidate();
        linesPanel.repaint();
        refreshEngineState();
    }

    private void displayTerminal(Position pos) {
        linesPanel.removeAll();
        String text = pos.inCheck() ? "Checkmate" : "No legal moves (stalemate)";
        linesPanel.add(style.label(text, Font.BOLD, 16));
        bar.setScore(pos.inCheck() ? -pos.sideToMove() * Suggestion.MATE_SCORE : 0, pos.inCheck() ? "#" : "0.0");
        board.setArrows(null);
        engineStatus.setText(" ");
        linesPanel.revalidate();
        linesPanel.repaint();
    }

    /** Score text from White's point of view ("+0.35" means White is better). */
    private static String whiteText(Suggestion s, int sideToMove) {
        if (s.mateIn() != null) {
            int m = s.mateIn() * sideToMove;
            return (m > 0 ? "M" : "-M") + Math.abs(s.mateIn());
        }
        return s.centipawns() == null ? "?" : String.format("%+.2f", s.centipawns() * sideToMove / 100.0);
    }

    private static String lineText(Position pos, List<Move> line) {
        StringBuilder sb = new StringBuilder();
        Position p = pos;
        for (int i = 0; i < Math.min(6, line == null ? 0 : line.size()); i++) {
            Move m = line.get(i);
            if (!p.isLegal(m)) break;
            sb.append(notation.toSan(p, m)).append(' ');
            p = p.play(m);
        }
        return esc(sb.toString().trim());
    }

    private void refreshEngineState() {
        analysisProvider a = engineRegistry.analysis();
        boolean ok = a != null && a.isAvailable();
        analyzeGameButton.setEnabled(ok && !analyzingGame);
        analyzeGameButton.setToolTipText(a == null ? "Engine analysis not connected" : ok ? null : "Engine is loading...");
    }

    // ---------- whole-game analysis ----------

    private void analyzeGame() {
        analysisProvider a = engineRegistry.analysis();
        if (a == null || !a.isAvailable() || analyzingGame) return;
        analyzingGame = true;
        int token = ++analyzeToken;
        progress.setVisible(true);
        cancelButton.setVisible(true);
        progress.setMaximum(g.plyCount() + 1);
        progress.setValue(0);
        refreshEngineState();
        analyzeFrom(a, 0, token);
    }

    private void analyzeFrom(analysisProvider a, int p, int token) {
        if (token != analyzeToken) return;
        if (p > g.plyCount()) { finishAnalysis("Analysis complete"); return; }
        progress.setValue(p);
        progress.setString("Analyzing ply " + p + " of " + g.plyCount());
        Position pos = g.positionAt(p);
        if (cached(p) != null || pos.legalMoves().isEmpty()) { analyzeFrom(a, p + 1, token); return; }
        request(a, pos).whenComplete((lines, ex) -> SwingUtilities.invokeLater(() -> {
            if (token != analyzeToken) return;
            if (ex != null) { finishAnalysis("Stopped: " + rootMessage(ex)); return; }
            if (!lines.isEmpty()) store(p, lines);
            recomputeMarks();
            if (p == ply && branch.isEmpty()) updateSuggestions();
            analyzeFrom(a, p + 1, token);
        }));
    }

    private void cancelAnalysis() {
        if (!analyzingGame) return;
        analyzeToken++;
        finishAnalysis("Analysis cancelled");
    }

    private void finishAnalysis(String text) {
        analyzingGame = false;
        progress.setValue(progress.getMaximum());
        progress.setString(text);
        cancelButton.setVisible(false);
        refreshEngineState();
        recomputeMarks();
    }

    /** Marks each move whose before/after evaluations are known with ?!, ? or ??. */
    private void recomputeMarks() {
        list.clearMarks();
        int[][] counts = new int[2][4];
        int marked = 0;
        for (int p = 1; p <= g.plyCount(); p++) {
            List<Suggestion> before = cached(p - 1);
            if (before == null) continue;
            Position prev = g.positionAt(p - 1), next = g.positionAt(p);
            Integer after = null;
            if (next.legalMoves().isEmpty()) after = next.inCheck() ? Suggestion.MATE_SCORE : 0;
            else {
                List<Suggestion> s = cached(p);
                if (s != null) after = -s.get(0).moverScore();
            }
            if (after == null) continue;
            moveClassifier.Quality q = before.get(0).move().equals(g.move(p - 1)) ? moveClassifier.Quality.GOOD
                    : moveClassifier.classify(before.get(0).moverScore(), after);
            list.setMark(p, moveClassifier.symbol(q).isEmpty() ? null : moveClassifier.symbol(q), moveClassifier.color(q));
            counts[prev.sideToMove() == Position.WHITE ? 0 : 1][q.ordinal()]++;
            marked++;
        }
        if (marked == 0) { summary.setText(" "); return; }
        summary.setText("<html>White: " + counts[0][1] + " inaccuracies, " + counts[0][2] + " mistakes, " + counts[0][3] + " blunders<br>"
                + "Black: " + counts[1][1] + " inaccuracies, " + counts[1][2] + " mistakes, " + counts[1][3] + " blunders</html>");
    }
}
