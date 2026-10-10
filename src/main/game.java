package main;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import ai.Suggestion;
import ai.analysisProvider;
import ai.engineRegistry;
import ai.moveProvider;
import ai.searchLimits;
import components.Board;
import components.capturedPanel;
import components.playerCard;
import components.roundedPanel;
import components.moveListPanel;
import components.style;
import handlers.audioHandler;
import handlers.configHandler;
import rules.Move;
import rules.Position;
import rules.gameState;
import saves.saveManager;
import saves.savedGame;

/**
 * One game session (a fresh instance per game): board + side panel, for Pass & Play, Player vs AI,
 * AI vs AI and Multiplayer. Rules come from the rules package; engines from ai.engineRegistry.
 */
public class game extends JPanel {
    private static final long ENGINE_GRACE_MS = 5000;

    private final mainWindow window;
    private final gameSetup setup;
    private gameState g;
    private savedGame save;

    private final Board board = new Board();
    private final moveListPanel moveList = new moveListPanel(410, 230);
    private final capturedPanel captured = new capturedPanel(410);
    private final playerCard whiteCard = new playerCard(Position.WHITE), blackCard = new playerCard(Position.BLACK);
    private final JLabel statusLabel = style.label(" ", Font.ITALIC, 19);
    private final JLabel hintLabel = style.label(" ", Font.PLAIN, 15);
    private final JPanel enginePanel = new JPanel();
    private final JLabel engineLabel = style.label(" ", Font.ITALIC, 17);
    private final JPanel engineButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
    private JButton saveButton, resignButton, drawButton, hintButton, newGameButton, liveButton, undoButton, soundButton;
    private JCheckBox autoRotate;

    private int viewPly = -1;              // -1 = live position
    private boolean gameOverHandled;
    private boolean dirty;                 // moves made since the last save

    // engine state
    private int engineToken;
    private boolean engineBusy;
    private CompletableFuture<Move> engineFuture;
    private moveProvider engineInFlight;
    private int humanOverride;             // colour the user is playing for one move after "Take over"
    private boolean aiPlaying, stepOnce, resumeAfterRetry;
    private JSlider speedSlider;
    private final Timer refreshTimer;
    private final Timer dotsTimer = new Timer(350, _ -> { (g.current().sideToMove() == Position.WHITE ? whiteCard : blackCard).tickDots(); refreshCards(); });

    // multiplayer
    final mpController mp;

    public game(mainWindow window, gameSetup setup) {
        this.window = window;
        this.setup = setup;
        this.setLayout(new BorderLayout());
        this.setBackground(style.BACKGROUND);

        if (setup.resume() != null) {
            save = setup.resume();
            g = save.toGameState();
        } else if (setup.session() != null) {
            savedGame st = setup.session().state();
            save = st != null ? st : savedGame.create(savedGame.MULTIPLAYER, Position.START_FEN); // guest: until the host's offer arrives
            if (st == null) { save.whiteName = "?"; save.blackName = "?"; }
            g = save.toGameState();
        } else {
            save = savedGame.create(setup.kind().equals(gameSetup.LOCAL) ? savedGame.LOCAL : savedGame.AI, Position.START_FEN);
            g = new gameState();
            fillPlayers();
        }
        mp = setup.session() != null ? new mpController(this, setup.session()) : null;

        JLabel title = new JLabel(setup.title(), SwingConstants.CENTER);
        title.setFont(style.titleFont(52));
        title.setForeground(style.TEXT);
        boardArea = new components.boardArea(title, board, null);
        this.add(boardArea, BorderLayout.CENTER);
        this.add(sideColumn(buildSidePanel()), BorderLayout.EAST);

        board.setOnMove(this::onBoardMove);
        moveList.setOnSelect(this::viewPly);
        if (setup.kind().equals(gameSetup.AI) && setup.humanColor() == Position.BLACK) board.setFlipped(true);
        if (mp != null && mp.localColor() == Position.BLACK) board.setFlipped(true);
        // Pass & Play: a resumed game opens with the side to move at the bottom
        if (isLocal()) board.setFlipped(!g.isOver() && g.current().sideToMove() == Position.BLACK);
        installKeys();

        refreshTimer = new Timer(1000, _ -> refreshButtons());
        refreshTimer.start();
        gameOverHandled = g.isOver();
        refresh();
        if (mp != null) mp.start();
        if (g.plyCount() == 0 && mp == null) board.showBanner(isAiVsAi() ? "Let the bots battle!" : "Good luck!", style.GREEN);
        else if (g.plyCount() > 0 && !g.isOver()) board.showBanner("Welcome back!", style.LIGHT_BLUE);
        SwingUtilities.invokeLater(this::maybeRequestEngine);
    }

    private final components.boardArea boardArea;

    /** The side panel, vertically centred, scrolling only if the window is too short for it. */
    public static JComponent sideColumn(JPanel side) {
        JPanel holder = new JPanel(new GridBagLayout());
        holder.setOpaque(false);
        holder.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 24));
        holder.add(side);
        JScrollPane sp = new JScrollPane(holder, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        return sp;
    }

    /** Full screen: hide the title so the board can use nearly the whole height. */
    public void onFullscreenChanged(boolean full) {
        boardArea.setTitleVisible(!full);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        style.paintBackdrop((Graphics2D) g0, getWidth(), getHeight());
    }

    // ---------- setup helpers ----------

    private boolean isLocal() { return setup.kind().equals(gameSetup.LOCAL); }
    private boolean isAi() { return setup.kind().equals(gameSetup.AI); }
    private boolean isAiVsAi() { return setup.kind().equals(gameSetup.AIVAI); }
    boolean isMultiplayer() { return mp != null; }

    private void fillPlayers() {
        save.whiteId = "";
        save.blackId = "";
        if (isAi()) {
            String engine = engineName(-setup.humanColor());
            save.whiteName = setup.humanColor() == Position.WHITE ? "You" : engine;
            save.blackName = setup.humanColor() == Position.BLACK ? "You" : engine;
            save.settings.put("humanColor", setup.humanColor() == Position.WHITE ? "white" : "black");
            save.settings.put("skill", (long) setup.skill());
        } else if (isAiVsAi()) {
            save.whiteName = setup.whiteEngine();
            save.blackName = setup.blackEngine();
            save.settings.put("whiteEngine", setup.whiteEngine());
            save.settings.put("blackEngine", setup.blackEngine());
            save.settings.put("skill", (long) setup.skill());
        } else {
            save.whiteName = "White";
            save.blackName = "Black";
        }
    }

    private String engineName(int color) {
        moveProvider p = providerFor(color);
        return p == null ? "Engine" : p.name();
    }

    private moveProvider providerFor(int color) {
        if (isAiVsAi()) return engineRegistry.byName(color == Position.WHITE ? setup.whiteEngine() : setup.blackEngine());
        return engineRegistry.forColor(color);
    }

    private boolean isHuman(int color) {
        if (humanOverride == color) return true;
        if (isLocal()) return true;
        if (isAi()) return color == setup.humanColor();
        if (mp != null) return color == mp.localColor();
        return false;
    }

    private boolean isEngineSide(int color) {
        if (humanOverride == color) return false;
        return isAiVsAi() || (isAi() && color != setup.humanColor());
    }

    // ---------- side panel ----------

    private JPanel buildSidePanel() {
        JPanel side = new roundedPanel(style.SIDE_PANEL);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(450, 950));
        side.setMaximumSize(new Dimension(450, 950));
        side.setBorder(BorderFactory.createEmptyBorder(14, 18, 18, 24));

        JPanel players = new JPanel(new GridLayout(2, 1, 0, 6));
        players.setOpaque(false);
        players.add(blackCard);
        players.add(whiteCard);
        add(side, players, 110);
        side.add(Box.createVerticalStrut(6));
        add(side, statusLabel, 30);
        if (mp != null) add(side, mp.connectionLabel(), 84);

        enginePanel.setLayout(new BorderLayout(0, 4));
        enginePanel.setBackground(style.YELLOW);
        engineLabel.setForeground(style.current.buttonText());
        enginePanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(style.BORDER, 2, true), BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        engineButtons.setOpaque(false);
        enginePanel.add(engineLabel, BorderLayout.CENTER);
        enginePanel.add(engineButtons, BorderLayout.SOUTH);
        enginePanel.setVisible(false);
        if (isAi() || isAiVsAi()) {
            // fixed-height holder so the side panel does not jump when the engine panel appears
            JPanel holder = new JPanel(new BorderLayout());
            holder.setOpaque(false);
            holder.add(enginePanel, BorderLayout.CENTER);
            add(side, holder, 84);
        }

        side.add(Box.createVerticalStrut(6));
        add(side, moveList, isLocal() ? 290 : isAiVsAi() ? 200 : 260);
        liveButton = style.button("Back to current position", style.YELLOW, 15, () -> viewPly(-1));
        liveButton.setVisible(false);
        add(side, liveButton, 28);
        side.add(Box.createVerticalStrut(4));
        add(side, captured, 70);

        if (isAiVsAi()) side.add(buildAiVsAiControls());
        hintLabel.setVerticalAlignment(SwingConstants.TOP);
        add(side, hintLabel, isAiVsAi() ? 34 : 44);

        if (isLocal()) {
            autoRotate = new JCheckBox("Auto-rotate board after each move", true);
            autoRotate.setFont(style.font(Font.ITALIC, 16));
            autoRotate.setOpaque(false);
            autoRotate.setForeground(style.TEXT);
            autoRotate.setFocusPainted(false);
            autoRotate.setCursor(new Cursor(Cursor.HAND_CURSOR));
            autoRotate.addActionListener(_ -> { style.click(); applyAutoRotate(); });
            add(side, autoRotate, 28);
        }

        JPanel grid = new JPanel(new GridLayout(0, 2, 10, 8));
        grid.setOpaque(false);
        newGameButton = style.button("New Game", style.GREEN, 19, this::newGame);
        grid.add(newGameButton);
        JButton flip = style.button("Flip Board", style.BLUE, 19, () -> board.flipTo(!board.isFlipped()));
        flip.setToolTipText("Flip the board (F)");
        grid.add(flip);
        undoButton = style.button("Undo", style.YELLOW, 19, this::undo);
        undoButton.setToolTipText("Take back your last move (Ctrl+Z)");
        grid.add(undoButton);
        hintButton = style.button("Hint", style.PURPLE, 19, this::hint);
        grid.add(hintButton);
        saveButton = style.button("Save", style.LIGHT_BLUE, 19, this::explicitSave);
        grid.add(saveButton);
        drawButton = style.button("Offer Draw", style.GRAY, 19, this::offerDraw);
        grid.add(drawButton);
        resignButton = style.button("Resign", style.RED, 19, this::resign);
        grid.add(resignButton);
        soundButton = style.button("", style.LIGHT_BLUE, 19, this::toggleSound);
        soundButton.setToolTipText("Sound effects on/off");
        grid.add(soundButton);
        add(side, grid, 4 * 46 + 24);
        side.add(Box.createVerticalStrut(10));
        JButton back = style.button(mp != null ? "Leave (resume later)" : "Back to Menu", style.RED, 20, this::backToMenu);
        add(side, back, 44);
        side.add(Box.createVerticalGlue());
        return side;
    }

    private static void add(JPanel side, JComponent c, int height) {
        c.setAlignmentX(Component.CENTER_ALIGNMENT);
        c.setMaximumSize(new Dimension(410, height));
        c.setPreferredSize(new Dimension(410, height));
        side.add(c);
    }

    private JPanel buildAiVsAiControls() {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setOpaque(false);
        JPanel buttons = new JPanel(new GridLayout(1, 3, 8, 0));
        buttons.setOpaque(false);
        buttons.add(style.button("Play", style.GREEN, 18, () -> { aiPlaying = true; maybeRequestEngine(); refresh(); }));
        buttons.add(style.button("Pause", style.GRAY, 18, () -> { aiPlaying = false; refresh(); }));
        buttons.add(style.button("Step", style.BLUE, 18, () -> { aiPlaying = false; stepOnce = true; maybeRequestEngine(); }));
        speedSlider = new JSlider(0, 3000, 800);
        speedSlider.setOpaque(false);
        speedSlider.setInverted(true);
        speedSlider.setToolTipText("Delay between moves");
        JLabel l = style.label("Speed:  slow  ‹ ›  fast", Font.ITALIC, 14);
        speedSlider.setForeground(style.TEXT);
        p.add(buttons, BorderLayout.NORTH);
        p.add(l, BorderLayout.CENTER);
        p.add(speedSlider, BorderLayout.SOUTH);
        p.setAlignmentX(Component.CENTER_ALIGNMENT);
        p.setMaximumSize(new Dimension(410, 92));
        p.setPreferredSize(new Dimension(410, 92));
        return p;
    }

    private void installKeys() {
        InputMap im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "prev");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next");
        am.put("prev", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { step(-1); } });
        am.put("next", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { step(1); } });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK), "undo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, 0), "flip");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, 0), "hint");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear");
        am.put("undo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { if (undoButton.isEnabled()) undo(); } });
        am.put("flip", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { board.flipTo(!board.isFlipped()); } });
        am.put("hint", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { if (hintButton.isEnabled()) hint(); } });
        am.put("clear", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { board.clearAnnotations(); } });
    }

    private void step(int d) {
        int cur = viewPly < 0 ? g.plyCount() : viewPly;
        int n = Math.max(0, Math.min(g.plyCount(), cur + d));
        viewPly(n >= g.plyCount() ? -1 : n);
    }

    // ---------- state -> UI ----------

    private void viewPly(int ply) {
        viewPly = ply >= g.plyCount() ? -1 : ply;
        refresh();
    }

    void refresh() {
        int shown = viewPly < 0 ? g.plyCount() : viewPly;
        Move last = shown == 0 ? null : g.move(shown - 1);
        board.setPosition(g.positionAt(shown), last);
        board.setMovable(viewPly >= 0 || g.isOver() ? Board.NONE : movableNow());
        moveList.setMoves(g.sans(), g.positionAt(0).sideToMove() == Position.BLACK, g.positionAt(0).fullmoveNumber());
        moveList.setCurrentPly(shown);
        liveButton.setVisible(viewPly >= 0);
        List<Integer> caps = new ArrayList<>();
        for (int i = 0; i < shown; i++) caps.add(g.capturedAt(i));
        captured.setCaptured(caps);

        int turn = g.current().sideToMove();
        refreshCards();

        String status;
        if (g.isOver()) status = g.terminationText();
        else if (mp != null && mp.statusOverride() != null) status = mp.statusOverride();
        else if (viewPly >= 0) status = "Viewing move " + viewPly + " of " + g.plyCount();
        else {
            status = (turn == Position.WHITE ? "White" : "Black") + " to move";
            if (g.current().inCheck()) status += " - Check!";
            if (isAiVsAi() && !aiPlaying && !engineBusy) status += " (paused)";
        }
        statusLabel.setText(status);
        statusLabel.setForeground(g.current().inCheck() && !g.isOver() ? (style.isDark() ? new Color(255, 120, 120) : new Color(170, 30, 30)) : style.TEXT);
        refreshButtons();
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;"); }

    /** Name plates: whose turn, who is thinking, who won. */
    private void refreshCards() {
        int turn = g.current().sideToMove();
        for (int color : new int[]{Position.WHITE, Position.BLACK}) {
            playerCard card = color == Position.WHITE ? whiteCard : blackCard;
            String name = color == Position.WHITE ? save.whiteName : save.blackName;
            boolean active = !g.isOver() && turn == color;
            String status;
            if (g.isOver()) status = g.winner() == 0 ? "Draw" : g.winner() == color ? "Winner!" : "";
            else if (!active) status = "";
            else if (g.current().inCheck()) status = "In check!";
            else if (engineBusy && isEngineSide(color)) status = "Thinking" + card.dots();
            else if (mp != null) status = color == mp.localColor() ? "Your move" : "Their move" + card.dots();
            else if (isAi() && isHuman(color)) status = "Your move";
            else status = "To move";
            card.update(name == null ? "" : name, status, active);
        }
        boolean wantDots = !g.isOver() && (engineBusy || (mp != null && turn != mp.localColor()));
        if (wantDots && !dotsTimer.isRunning()) dotsTimer.start();
        if (!wantDots) dotsTimer.stop();
    }

    private int movableNow() {
        int turn = g.current().sideToMove();
        if (mp != null) return mp.canMove() && turn == mp.localColor() ? turn : Board.NONE;
        if (engineBusy) return Board.NONE;
        if (isLocal()) return Board.BOTH;
        return isHuman(turn) ? turn : Board.NONE;
    }

    private void refreshButtons() {
        boolean live = !g.isOver();
        boolean anyHuman = !isAiVsAi();
        resignButton.setEnabled(live && anyHuman && (mp == null || mp.canAct()));
        drawButton.setEnabled(live && anyHuman && (mp == null || mp.canAct()));
        analysisProvider a = engineRegistry.analysis();
        hintButton.setEnabled(live && mp == null && a != null && a.isAvailable() && isHuman(g.current().sideToMove()));
        hintButton.setToolTipText(mp != null ? "Hints are off in multiplayer games" : a == null ? "Engine analysis not connected" : a.isAvailable() ? null : "Engine is loading...");
        newGameButton.setEnabled(mp == null);
        undoButton.setEnabled(mp == null && (isLocal() || isAi()) && g.plyCount() > 0 && viewPly < 0
                && !(isAi() && g.plyCount() == 1 && !isHuman(g.positionAt(0).sideToMove())));
        boolean sound = configHandler.getConfigBoolean("soundEffects");
        soundButton.setText(sound ? "Sound: On" : "Sound: Off");
        saveButton.setEnabled(mp == null);
        saveButton.setToolTipText(mp == null ? null : "Multiplayer games save automatically after every move");
    }

    private void applyAutoRotate() {
        if (isLocal() && !g.isOver() && autoRotate != null && autoRotate.isSelected()) board.flipTo(g.current().sideToMove() == Position.BLACK);
    }

    // ---------- moves ----------

    private void onBoardMove(Move m) {
        if (viewPly >= 0 || g.isOver()) return;
        if (mp != null) { mp.localMove(m); return; }
        int mover = g.current().sideToMove();
        if (!isHuman(mover) || !g.play(m)) { refresh(); return; }
        if (humanOverride == mover) humanOverride = 0;
        hideEnginePanel();
        afterMove();
    }

    void afterMove() {
        dirty = true;
        board.setArrows(null);
        hintLabel.setText(" ");
        moveFeedback();
        applyAutoRotate();
        refresh();
        if (g.isOver()) handleGameOver();
        else maybeRequestEngine();
    }

    /** Multiplayer: replace the whole game with the agreed/updated state. */
    void replaceState(gameState ng) {
        boolean wasOver = g.isOver();
        boolean moved = ng.plyCount() != g.plyCount();
        g = ng;
        if (!g.isOver()) gameOverHandled = false;
        if (moved) {
            board.setArrows(null);
            moveFeedback();
            if (mp != null && !g.isOver() && !g.current().inCheck() && g.current().sideToMove() == mp.localColor() && g.plyCount() > 0)
                board.showBanner("Your move!", style.GREEN);
        }
        refresh();
        if (g.isOver() && !wasOver) handleGameOver();
    }

    /** Sound + banner for the move just made. */
    private void moveFeedback() {
        if (g.plyCount() == 0) return;
        if (g.isOver()) { audioHandler.playMoveSound(g.capturedAt(g.plyCount() - 1) != 0); return; }
        if (g.current().inCheck()) {
            audioHandler.playEffect("check.wav");
            board.showBanner("Check!", style.RED);
        } else {
            audioHandler.playMoveSound(g.capturedAt(g.plyCount() - 1) != 0);
            if (isLocal()) board.showBanner((g.current().sideToMove() == Position.WHITE ? "White" : "Black") + " to move", style.YELLOW);
        }
    }

    gameState state() { return g; }
    Board board() { return board; }

    // ---------- game over ----------

    private void handleGameOver() {
        if (gameOverHandled) return;
        gameOverHandled = true;
        cancelEngine(false);
        aiPlaying = false;
        int w = g.winner();
        int me = isAi() ? setup.humanColor() : mp != null ? mp.localColor() : 0;
        if (w == 0 || isAiVsAi()) audioHandler.playEffect("completed.wav");   // draws, engine matches
        else if (me == 0) audioHandler.playRandomEffect("winning");           // pass & play: someone won
        else audioHandler.playRandomEffect(me == w ? "winning" : "losing");
        if (mp == null) {
            save.syncFrom(g);
            if (configHandler.getConfigBoolean("saves.saveState") || saveManager.exists(save.gameId)) saveQuietly();
        }
        String banner = w == 0 ? "Draw!" : (w > 0 ? "White" : "Black") + " wins!";
        board.showBanner(banner, w == 0 ? style.GRAY : style.GREEN);
        if (w != 0 && (me == 0 || me == w || isAiVsAi())) board.celebrate();
        Timer later = new Timer(Board.animationsOn() ? 1400 : 1, _ -> SwingUtilities.invokeLater(() -> {
            if (!isShowing()) return;
            String title = w == 0 ? "Draw" : (w > 0 ? "White" : "Black") + " wins!";
            int c = style.choose(this, "Game over", "<html><div style='width:320px'><b style='font-size:22px'>" + title + "</b><br><br>" + g.terminationText() + "</div></html>",
                    "Rematch", "Review", "Menu");
            if (c == 0) rematch();
            else if (c == 1) review();
            else if (c == 2) leave();
        }));
        later.setRepeats(false);
        later.start();
    }

    private void rematch() {
        if (mp != null) { mp.rematch(); return; }
        window.startGame(setup.fresh());
    }

    private void review() {
        save.syncFrom(g);
        if (mp == null) saveQuietly();
        window.openReview(save.copy(), "game");
    }

    // ---------- buttons ----------

    private boolean inProgress() { return g.plyCount() > 0 && !g.isOver(); }

    private void newGame() {
        if (inProgress()) {
            boolean autosave = configHandler.getConfigBoolean("saves.saveState");
            int c = style.choose(this, "New game", "Start a new game?" + (autosave ? " This game will be saved so you can resume it later." : " This game will be lost unless you save it first."),
                    "Start new game", "Cancel");
            if (c != 0) return;
            if (autosave) saveCurrent();
        }
        cancelEngine(false);
        window.startGame(setup.fresh());
    }

    /** Takes back the last move (Pass & Play) or your last move plus the engine's reply (vs AI). */
    private void undo() {
        if (mp != null || !(isLocal() || isAi()) || g.plyCount() == 0) return;
        cancelEngine(false);
        hideEnginePanel();
        humanOverride = 0;
        int plies = 1;
        if (isAi()) {
            int after = g.plyCount() - 1;
            if (after > 0 && !isHuman(g.positionAt(after).sideToMove())) plies = 2;
        }
        takeBack(plies);
    }

    private void takeBack(int plies) {
        if (plies <= 0 || g.plyCount() == 0) return;
        gameState back = gameState.replay(g.startFen(), g.moves().subList(0, g.plyCount() - 1));
        g = back;
        gameOverHandled = false;
        viewPly = -1;
        dirty = true;
        board.setArrows(null);
        hintLabel.setText(" ");
        audioHandler.playMoveSound(false);
        applyAutoRotate();
        refresh();
        if (plies > 1) {
            Timer t = new Timer(Board.animationsOn() ? 260 : 1, _ -> takeBack(plies - 1));
            t.setRepeats(false);
            t.start();
        }
    }

    private void toggleSound() {
        configHandler.setConfigBoolean("audio.soundEffects", !configHandler.getConfigBoolean("soundEffects"));
        refreshButtons();
    }

    private void explicitSave() {
        if (saveCurrent()) statusFlash("Game saved");
    }

    private boolean saveCurrent() {
        if (mp != null) return true;
        save.syncFrom(g);
        try {
            saveManager.save(save);
            dirty = false;
            return true;
        } catch (Exception e) {
            style.info(this, "Save failed", "Could not save the game: " + e.getMessage());
            return false;
        }
    }

    private void saveQuietly() {
        if (saveManager.trySave(save)) dirty = false;
    }

    void flash(String text) { statusFlash(text); }

    /** Multiplayer: a new or agreed state from the session replaces what is shown. */
    void replaceSave(savedGame s) {
        save = s;
        replaceState(s.toGameState());
    }

    void leaveToMenu() { leave(); }

    private void statusFlash(String text) {
        statusLabel.setText(text);
        Timer t = new Timer(1800, _ -> refresh());
        t.setRepeats(false);
        t.start();
    }

    private void resign() {
        if (g.isOver()) return;
        if (mp != null) {
            if (style.choose(this, "Resign", "Resign this game?", "Resign", "Cancel") == 0) mp.resign();
            return;
        }
        int color;
        if (isLocal()) {
            int c = style.choose(this, "Resign", "Which side resigns?", "White resigns", "Black resigns", "Cancel");
            if (c < 0 || c == 2) return;
            color = c == 0 ? Position.WHITE : Position.BLACK;
        } else {
            if (style.choose(this, "Resign", "Resign this game?", "Resign", "Cancel") != 0) return;
            color = setup.humanColor();
        }
        cancelEngine(false);
        g.resign(color);
        afterMove();
    }

    private void offerDraw() {
        if (g.isOver()) return;
        if (mp != null) { mp.offerDraw(); return; }
        if (isLocal()) {
            String offerer = g.current().sideToMove() == Position.WHITE ? "White" : "Black";
            String other = offerer.equals("White") ? "Black" : "White";
            if (style.choose(this, "Draw offer", offerer + " offers a draw. " + other + ", do you accept?", "Accept draw", "Decline") == 0) {
                g.agreeDraw();
                afterMove();
            } else statusFlash("Draw declined");
            return;
        }
        // The engine hook has no draw negotiation yet, so engines always decline.
        style.info(this, "Draw offer", "The engine declined the draw offer.\n(Engines can't accept draws through the hook yet.)");
    }

    private void hint() {
        analysisProvider a = engineRegistry.analysis();
        if (a == null || !a.isAvailable()) { refreshButtons(); return; }
        Position pos = g.current();
        int plyAtRequest = g.plyCount();
        hintLabel.setText("Thinking about a hint...");
        CompletableFuture<List<Suggestion>> f;
        try { f = a.analyze(pos, 3); } catch (RuntimeException ex) { f = CompletableFuture.failedFuture(ex); }
        f.orTimeout(15, TimeUnit.SECONDS).whenComplete((list, ex) -> SwingUtilities.invokeLater(() -> {
            if (g.plyCount() != plyAtRequest) return;
            if (ex != null || list == null || list.isEmpty()) { hintLabel.setText("No hint available (" + reason(ex) + ")"); return; }
            List<Move> arrows = new ArrayList<>();
            for (Suggestion s : list) if (pos.isLegal(s.move()) && arrows.size() < 3) arrows.add(s.move());
            if (arrows.isEmpty()) { hintLabel.setText("The engine's hint was not a legal move."); return; }
            board.setArrows(arrows);
            Suggestion best = list.get(0);
            hintLabel.setText("<html>Hint: <b>" + rules.notation.toSan(pos, best.move()) + "</b> (" + best.scoreText() + ", depth " + best.depth() + ")"
                    + (best.comment() != null ? "<br><i>" + esc(best.comment()) + "</i>" : "") + "</html>");
        }));
    }

    private void backToMenu() {
        if (mp != null) {
            if (style.choose(this, "Leave game", "Leave this game? It is saved, and you can rejoin it later from Saved Games.", "Leave", "Stay") != 0) return;
            leave();
            return;
        }
        if (inProgress()) {
            boolean autosave = configHandler.getConfigBoolean("saves.saveState");
            int c = style.choose(this, "Back to menu", "Leave this game?" + (autosave ? " It will be saved so you can resume it later." : " Unsaved progress will be lost."),
                    "Leave", "Save and leave", "Stay");
            if (c == 2 || c < 0) return;
            if (c == 1 && !saveCurrent()) return;
        }
        leave();
    }

    /** Leaves to the main menu (saving according to the Save State setting). */
    private void leave() {
        onLeaving();
        window.showMenu();
    }

    /** Called when this game is closed: by leaving, by starting another game, or by quitting the app. */
    public void onLeaving() {
        refreshTimer.stop();
        dotsTimer.stop();
        cancelEngine(false);
        aiPlaying = false;
        if (mp != null) { mp.leave(); return; }
        if (dirty && g.plyCount() > 0 && configHandler.getConfigBoolean("saves.saveState")) {
            save.syncFrom(g);
            saveQuietly();
        }
    }

    // ---------- engine turns ----------

    private void maybeRequestEngine() {
        if (g.isOver() || engineBusy) return;
        int side = g.current().sideToMove();
        if (!isEngineSide(side)) return;
        if (isAiVsAi() && !aiPlaying && !stepOnce) { refresh(); return; }
        moveProvider p = providerFor(side);
        if (p == null) {
            aiPlaying = false;
            mainWindow.showNoEngineDialog(this);
            return;
        }
        engineBusy = true;
        int token = ++engineToken;
        int delay = isAiVsAi() ? (stepOnce ? 0 : speedSlider.getValue()) : 250;
        stepOnce = false;
        showThinking(p.name() + " is thinking...");
        refresh();
        Timer t = new Timer(Math.max(1, delay), _ -> waitForEngine(p, token, side, System.currentTimeMillis()));
        t.setRepeats(false);
        t.start();
    }

    /** Waits (without blocking Swing) for a provider whose model is still loading. */
    private void waitForEngine(moveProvider p, int token, int side, long since) {
        if (token != engineToken) return;
        if (!p.isAvailable()) {
            if (System.currentTimeMillis() - since > 120_000) { engineProblem(side, p.name() + " did not finish loading."); return; }
            showThinking("Waiting for " + p.name() + " to load...");
            Timer t = new Timer(400, _ -> waitForEngine(p, token, side, since));
            t.setRepeats(false);
            t.start();
            return;
        }
        showThinking(p.name() + " is thinking...");
        searchLimits limits = searchLimits.forSkill(setup.skill() <= 0 ? 5 : setup.skill());
        Position pos = g.current();
        CompletableFuture<Move> f;
        try {
            f = p.chooseMove(pos, g.moves(), limits);
            if (f == null) f = CompletableFuture.failedFuture(new IllegalStateException("provider returned no future"));
        } catch (RuntimeException ex) {
            f = CompletableFuture.failedFuture(ex);
        }
        engineFuture = f;
        engineInFlight = p;
        f.orTimeout(limits.maxTimeMs() + ENGINE_GRACE_MS, TimeUnit.MILLISECONDS)
                .whenComplete((m, ex) -> SwingUtilities.invokeLater(() -> engineReturned(token, side, pos, m, ex)));
    }

    private void engineReturned(int token, int side, Position asked, Move m, Throwable ex) {
        if (token != engineToken) return; // cancelled or superseded
        engineBusy = false;
        engineFuture = null;
        engineInFlight = null;
        if (ex != null) { engineProblem(side, "The engine failed: " + reason(ex)); return; }
        if (m == null || !asked.equals(g.current()) || !g.current().isLegal(m)) {
            engineProblem(side, "The engine returned an illegal move" + (m == null ? " (none)" : " (" + m.uci() + ")") + ".");
            return;
        }
        hideEnginePanel();
        g.play(m);
        afterMove();
    }

    private static String reason(Throwable ex) {
        if (ex == null) return "no answer";
        Throwable c = ex;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) c = c.getCause();
        if (c instanceof TimeoutException) return "it took too long (timeout)";
        if (c instanceof CancellationException) return "cancelled";
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    private void cancelEngine(boolean showProblem) {
        if (!engineBusy) return;
        engineToken++;
        engineBusy = false;
        if (engineInFlight != null) { try { engineInFlight.cancel(); } catch (RuntimeException ignored) { } }
        if (engineFuture != null) engineFuture.cancel(true);
        engineFuture = null;
        engineInFlight = null;
        hideEnginePanel();
        if (showProblem) {
            aiPlaying = false;
            resumeAfterRetry = false;
            engineProblem(g.current().sideToMove(), "Engine move cancelled.");
        }
        refresh();
    }

    private void showThinking(String text) {
        engineLabel.setText(text);
        engineButtons.removeAll();
        engineButtons.add(smallButton("Cancel", style.GRAY, () -> cancelEngine(true)));
        enginePanel.setBackground(style.YELLOW);
        enginePanel.setVisible(true);
        enginePanel.revalidate();
        enginePanel.repaint();
    }

    private void engineProblem(int side, String message) {
        engineBusy = false;
        boolean resume = aiPlaying || resumeAfterRetry;   // AI vs AI: Retry continues the match if it was running
        resumeAfterRetry = resume;
        aiPlaying = false;
        engineLabel.setText("<html>" + esc(message) + "</html>");
        engineButtons.removeAll();
        engineButtons.add(smallButton("Retry", style.GREEN, () -> {
            hideEnginePanel();
            if (isAiVsAi()) { if (resumeAfterRetry) aiPlaying = true; else stepOnce = true; }
            resumeAfterRetry = false;
            maybeRequestEngine();
        }));
        engineButtons.add(smallButton("Take over this move", style.BLUE, () -> {
            humanOverride = side;
            resumeAfterRetry = false;
            hideEnginePanel();
            statusFlash("Your move for " + (side == Position.WHITE ? "White" : "Black"));
            refresh();
        }));
        enginePanel.setBackground(components.fancyButton.mix(style.RED, style.CARD, 0.45f));
        enginePanel.setVisible(true);
        enginePanel.revalidate();
        enginePanel.repaint();
        refresh();
    }

    private void hideEnginePanel() {
        enginePanel.setVisible(false);
    }

    private static JButton smallButton(String text, Color bg, Runnable r) {
        JButton b = style.button(text, bg, 15, r);
        b.setPreferredSize(new Dimension(Math.max(90, b.getPreferredSize().width + 20), 28));
        return b;
    }
}
