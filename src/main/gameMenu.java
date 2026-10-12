package main;
import javax.swing.*;
import java.awt.*;

import ai.engineRegistry;
import ai.moveProvider;
import components.roundedPanel;
import components.style;
import rules.Position;
import saves.savedGame;

import java.util.List;
import java.util.Set;

/** The two mode cards behind "Start Game" (Pass & Play / Multiplayer) and "AI Mode" (Player vs AI / AI vs AI). */
public class gameMenu extends JPanel {
    private final mainWindow window;

    public gameMenu(mainWindow window, CardLayout cardLayout, JPanel cardPanel, boolean aiMode) {
        this.window = window;
        setLayout(new BorderLayout());

        JButton back = style.button("‹  Back", style.GRAY, 17, () -> cardLayout.show(cardPanel, "mainMenu"));
        back.setPreferredSize(new Dimension(130, 42));
        JLabel heading = new JLabel(aiMode ? "AI Mode" : "Start a Game", SwingConstants.CENTER);
        heading.setFont(style.titleFont(48));
        heading.setForeground(style.TEXT);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(24, 28, 0, 28));
        top.add(back, BorderLayout.WEST);
        top.add(heading, BorderLayout.CENTER);
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        spacer.setPreferredSize(new Dimension(130, 42));
        top.add(spacer, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        JPanel options = new JPanel(new GridBagLayout());
        options.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 40, 40, 40);
        if (aiMode) {
            options.add(modeCard("src/assets/desktop_logo2.png", "Player vs AI", "Challenge the Computer",
                    "Play against an AI opponent. Great for practice, learning new strategies, or testing your skills against different AI models.",
                    style.BLUE, this::startPlayerVsAI), c);
            options.add(modeCard("src/assets/sever_logo.png", "AI vs AI", "Watch the Bots Battle",
                    "Let two AIs face off and observe their strategies in action. Great for learning, testing engine strength, or just enjoying an automated match.",
                    style.PURPLE, this::startAIvsAI), c);
        } else {
            options.add(modeCard("src/assets/desktop_logo.png", "On-Device", "Pass & Play",
                    "Play chess locally with a friend using the same device. The board automatically rotates after each move, giving each player their own perspective during their turn.",
                    style.GREEN, () -> { if (!window.offerResume(Set.of(savedGame.LOCAL))) window.startGame(gameSetup.local()); }), c);
            options.add(modeCard("src/assets/internet_logo.png", "Multiplayer", "Play Across Devices",
                    "Play with friends or opponents over LAN or online. Easily host or join games, and pick up unfinished matches from Saved Games.",
                    style.LIGHT_BLUE, () -> multiplayerDialog.open(window)), c);
        }
        add(options, BorderLayout.CENTER);
    }

    @Override
    protected void paintComponent(Graphics g) { style.paintBackdrop((Graphics2D) g, getWidth(), getHeight()); }

    /** One big card: icon, title, subtitle, description and a Start button. */
    private JPanel modeCard(String icon, String title, String subtitle, String desc, Color accent, Runnable start) {
        JPanel card = new roundedPanel(style.SIDE_PANEL);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(40, 34, 40, 42));
        Dimension size = new Dimension(430, 640);
        card.setPreferredSize(size);

        JLabel iconLabel = new JLabel(new ImageIcon(new ImageIcon(icon).getImage().getScaledInstance(190, 190, Image.SCALE_SMOOTH)));
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel t = new JLabel(title);
        t.setFont(style.titleFont(42));
        t.setForeground(style.TEXT);
        t.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel sub = style.label(subtitle, Font.BOLD, 20);
        sub.setForeground(style.isDark() ? style.current.buttonText() : style.MUTED);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel d = style.label("<html><div style='text-align:center; width:270px'>" + desc + "</div></html>", Font.PLAIN, 16);
        d.setForeground(style.MUTED);
        d.setAlignmentX(Component.CENTER_ALIGNMENT);
        JButton go = style.button("Start", accent, 24, start);
        go.setPreferredSize(new Dimension(260, 56));
        go.setMaximumSize(new Dimension(260, 56));

        card.add(iconLabel);
        card.add(Box.createVerticalStrut(18));
        card.add(t);
        card.add(Box.createVerticalStrut(6));
        card.add(sub);
        card.add(Box.createVerticalStrut(14));
        card.add(d);
        card.add(Box.createVerticalGlue());
        card.add(go);
        return card;
    }

    // ---------- AI setup dialogs ----------

    private void startPlayerVsAI() {
        if (!engineRegistry.hasMoveProvider()) { mainWindow.showNoEngineDialog(this); return; }
        if (window.offerResume(Set.of(savedGame.AI))) return;

        JPanel p = new JPanel(new GridLayout(0, 1, 0, 8));
        p.add(style.label("Play as:", Font.BOLD, 20));
        JComboBox<String> colour = new JComboBox<>(new String[]{"White", "Black", "Random"});
        p.add(colour);
        List<moveProvider> providers = engineRegistry.providers();
        String[] models = providers.stream().map(m -> review.gameRating.label(m.name())).toArray(String[]::new);
        p.add(style.label("AI model:", Font.BOLD, 20));
        JComboBox<String> model = new JComboBox<>(models);
        p.add(model);
        style.deepFont(colour, style.font(Font.PLAIN, 18));
        style.deepFont(model, style.font(Font.PLAIN, 18));
        if (style.choose(this, "Player vs AI", p, "Start", "Cancel") != 0) return;
        int c = colour.getSelectedIndex() == 0 ? Position.WHITE : colour.getSelectedIndex() == 1 ? Position.BLACK
                : (Math.random() < 0.5 ? Position.WHITE : Position.BLACK);
        window.startGame(gameSetup.vsAi(c, 0, providers.get(model.getSelectedIndex()).name()));
    }

    private void startAIvsAI() {
        List<moveProvider> providers = engineRegistry.providers();
        if (providers.isEmpty()) { mainWindow.showNoEngineDialog(this); return; }
        String[] names = providers.stream().map(m -> review.gameRating.label(m.name())).toArray(String[]::new);
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 8));
        p.add(style.label("White engine:", Font.BOLD, 20));
        JComboBox<String> white = new JComboBox<>(names);
        p.add(white);
        p.add(style.label("Black engine:", Font.BOLD, 20));
        JComboBox<String> black = new JComboBox<>(names);
        p.add(black);
        style.deepFont(white, style.font(Font.PLAIN, 18));
        style.deepFont(black, style.font(Font.PLAIN, 18));
        if (style.choose(this, "AI vs AI", p, "Start", "Cancel") != 0) return;
        window.startGame(gameSetup.aiVsAi(providers.get(white.getSelectedIndex()).name(), providers.get(black.getSelectedIndex()).name(), 0));
    }

}
