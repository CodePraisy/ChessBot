package main;
import javax.swing.*;

import components.menuBackground;
import components.roundedPanel;
import components.style;
import handlers.audioHandler;
import handlers.configHandler;
import review.reviewPanel;
import saves.saveManager;
import saves.savedGame;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.List;
import java.util.Set;

public class mainWindow extends JFrame {
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);
    private game currentGame;
    private reviewPanel currentReview;
    private savedGamesMenu savedGames;
    private boolean fullscreen;
    private Rectangle windowedBounds;

    public mainWindow(String tabTitle, String iconPath, boolean resizable) {
        super(tabTitle);
        style.init();

        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setSize(1400, 1000);
        this.setMinimumSize(new Dimension(1200, 860));
        this.setLocationRelativeTo(null);
        this.setResizable(resizable);
        this.setIconImage(new ImageIcon(iconPath).getImage());
        this.setContentPane(cardPanel);

        buildCards();

        // F11: full-screen board mode, from any screen
        JRootPane root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), "fullscreen");
        root.getActionMap().put("fullscreen", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { toggleFullscreen(); } });

        if (configHandler.getConfigBoolean("audio.backgroundMusic")) {
            audioHandler.playRandomBackgroundMusic();
        }

        // Save State: save the game in progress when the window is closed.
        this.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { closeCurrentGame(); }
        });

        this.setVisible(true);
        SwingUtilities.invokeLater(this::offerResumeOnLaunch);
    }

    /** Builds every screen with the current theme and font. */
    private void buildCards() {
        cardPanel.removeAll();
        cardPanel.setBackground(style.BACKGROUND);
        cardPanel.add(buildMainMenu(), "mainMenu");
        cardPanel.add(new settingsPanel(this), "settings");
        cardPanel.add(new gameMenu(this, cardLayout, cardPanel, false), "standardGameMenu");
        cardPanel.add(new gameMenu(this, cardLayout, cardPanel, true), "aiGameMenu");
        savedGames = new savedGamesMenu(this);
        cardPanel.add(savedGames, "savedGames");
        cardPanel.revalidate();
        cardPanel.repaint();
    }

    /** Theme or font changed: rebuild every screen and come back to the given one. */
    public void rebuild(String card) {
        closeCurrentGame();
        style.init();
        buildCards();
        cardLayout.show(cardPanel, card);
        SwingUtilities.updateComponentTreeUI(this);
    }

    public void show(String card) { cardLayout.show(cardPanel, card); }

    private JPanel buildMainMenu() {
        JPanel mainMenuPanel = new menuBackground(style.BACKGROUND); // drifting pieces behind the menu
        mainMenuPanel.setLayout(new BorderLayout());

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);

        JLabel logo = new JLabel(new ImageIcon(new ImageIcon("src/assets/frontPage.png").getImage().getScaledInstance(190, 190, Image.SCALE_SMOOTH)));
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel title = new JLabel("Praise's Chess", SwingConstants.CENTER);
        title.setFont(style.titleFont(64));
        title.setForeground(style.TEXT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel tagline = style.label("Play a friend, an engine, or someone across the world", Font.PLAIN, 19);
        tagline.setForeground(style.MUTED);
        tagline.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel card = new roundedPanel(style.SIDE_PANEL);
        card.setLayout(new GridLayout(0, 1, 0, 14));
        card.setBorder(BorderFactory.createEmptyBorder(28, 34, 34, 40));
        Dimension size = new Dimension(520, 5 * 58 + 4 * 14 + 70);
        card.setPreferredSize(size);
        card.setMaximumSize(size);
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(menuButton("Start Game", style.GREEN, () -> show("standardGameMenu")));
        card.add(menuButton("AI Mode", style.BLUE, () -> show("aiGameMenu")));
        card.add(menuButton("Saved Games", style.YELLOW, this::showSavedGames));
        card.add(menuButton("Settings", style.GRAY, () -> show("settings")));
        card.add(menuButton("Quit", style.RED, () -> {
            if (style.choose(this, "Quit", "Are you sure you want to quit?", "Quit", "Cancel") == 0) {
                closeCurrentGame();
                System.exit(0);
            }
        }));

        column.add(Box.createVerticalGlue());
        column.add(logo);
        column.add(Box.createVerticalStrut(6));
        column.add(title);
        column.add(Box.createVerticalStrut(4));
        column.add(tagline);
        column.add(Box.createVerticalStrut(36));
        column.add(card);
        column.add(Box.createVerticalGlue());

        JButton githubButton = style.button("Support My GitHub Page :)", style.PURPLE, 14, () -> {
            try {
                Desktop.getDesktop().browse(new URI("https://github.com/CodePraisy"));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
        githubButton.setPreferredSize(new Dimension(240, 38));
        JLabel versionLabel = style.label(configHandler.loadVersion() + "   •   F11 full screen", Font.PLAIN, 16);
        versionLabel.setForeground(style.MUTED);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setOpaque(false);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(0, 14, 12, 20));
        bottomPanel.add(githubButton, BorderLayout.WEST);
        bottomPanel.add(versionLabel, BorderLayout.EAST);

        mainMenuPanel.add(column, BorderLayout.CENTER);
        mainMenuPanel.add(bottomPanel, BorderLayout.SOUTH);
        return mainMenuPanel;
    }

    private static JButton menuButton(String text, Color bg, Runnable action) {
        JButton b = style.button(text, bg, 24, action);
        b.setPreferredSize(new Dimension(440, 58));
        return b;
    }

    // ---------- full screen ----------

    /** F11 / "Full screen": borderless window filling the screen; the board grows to fill the height. */
    public void toggleFullscreen() {
        fullscreen = !fullscreen;
        dispose();
        if (fullscreen) {
            windowedBounds = getBounds();
            setUndecorated(true);
            setExtendedState(JFrame.MAXIMIZED_BOTH);
            GraphicsConfiguration gc = getGraphicsConfiguration();
            if (gc != null) setBounds(gc.getBounds());
        } else {
            setUndecorated(false);
            setExtendedState(JFrame.NORMAL);
            if (windowedBounds != null) setBounds(windowedBounds);
        }
        setVisible(true);
        if (currentGame != null) currentGame.onFullscreenChanged(fullscreen);
        if (currentReview != null) currentReview.onFullscreenChanged(fullscreen);
    }

    public boolean isFullscreen() { return fullscreen; }

    // ---------- navigation ----------

    /** Opens a fresh game card for this session (the previous game, if any, is closed first). */
    public void startGame(gameSetup setup) {
        closeCurrentGame();
        try {
            currentGame = new game(this, setup);
        } catch (RuntimeException e) {
            e.printStackTrace();
            style.info(this, "Could not open game", "The game could not be opened: " + e.getMessage());
            currentGame = null;
            showMenu();
            return;
        }
        cardPanel.add(currentGame, "game");
        cardLayout.show(cardPanel, "game");
        currentGame.onFullscreenChanged(fullscreen);
        currentGame.requestFocusInWindow();
    }

    public void showMenu() {
        closeCurrentGame();
        cardLayout.show(cardPanel, "mainMenu");
    }

    public void showSavedGames() {
        closeCurrentGame();
        savedGames.reload();
        cardLayout.show(cardPanel, "savedGames");
    }

    /** Opens the review screen; backTo is "savedGames" or anything else for the main menu. */
    public void openReview(savedGame s, String backTo) {
        closeCurrentGame();
        if (currentReview != null) cardPanel.remove(currentReview);
        currentReview = new reviewPanel(this, s, "savedGames".equals(backTo));
        cardPanel.add(currentReview, "review");
        cardLayout.show(cardPanel, "review");
        currentReview.onFullscreenChanged(fullscreen);
        currentReview.requestFocusInWindow();
    }

    private void closeCurrentGame() {
        if (currentGame != null) {
            currentGame.onLeaving();
            cardPanel.remove(currentGame);
            currentGame = null;
        }
    }

    // ---------- resume / dialogs ----------

    /** Use Save State: offer to continue the most recent unfinished game of the given modes. Returns true if resumed. */
    public boolean offerResume(Set<String> modes) {
        if (!configHandler.getConfigBoolean("saves.useSaveState")) return false;
        reportSaveProblems();
        savedGame s = saveManager.mostRecentUnfinished(modes);
        reportSaveProblems();
        if (s == null) return false;
        String what = savedGame.AI.equals(s.mode) ? (s.settingStr("whiteEngine", null) != null ? "AI vs AI" : "Player vs AI") : "Pass & Play";
        String when = s.updated.length() >= 16 ? s.updated.substring(0, 16).replace('T', ' ') : s.updated;
        int c = style.choose(this, "Resume game?", "You have an unfinished " + what + " game (" + (s.plyCount() + 1) / 2 + " moves, last played " + when + " UTC).\nResume it?",
                "Resume", "New game");
        if (c != 0) return false;
        startGame(gameSetup.resume(s));
        return true;
    }

    private void offerResumeOnLaunch() {
        offerResume(Set.of(savedGame.LOCAL, savedGame.AI));
    }

    /** Shows (once) any corrupt save files that were renamed. */
    public void reportSaveProblems() {
        List<String> problems = saveManager.takeProblems();
        if (!problems.isEmpty()) style.info(this, "Some saves could not be read", String.join("\n\n", problems));
    }

    public static void showNoEngineDialog(Component parent) {
        style.info(parent, "No engine connected",
                "<html><div style='width:420px'><b>No chess engine is connected yet.</b><br><br>"
                + "The Python models are registered in <i>src/ai/engineBootstrap.java</i>. Check that the Python environment has the packages in <i>engine/reqiurements.txt</i>.</div></html>");
    }
}
