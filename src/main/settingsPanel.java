package main;
import javax.swing.*;

import components.fancyButton;
import components.roundedPanel;
import components.style;
import components.toggleSwitch;
import handlers.audioHandler;
import handlers.configHandler;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Settings: theme and font, visuals, sound and saving. Every change applies (and is saved) immediately. */
public class settingsPanel extends JPanel {

    public settingsPanel(mainWindow window) {
        setLayout(new BorderLayout());

        JButton back = style.button("‹  Back", style.GRAY, 17, () -> window.show("mainMenu"));
        back.setPreferredSize(new Dimension(130, 42));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 16));
        top.setOpaque(false);
        top.add(back);
        add(top, BorderLayout.NORTH);

        JPanel card = new roundedPanel(style.SIDE_PANEL, 26, true);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(26, 40, 30, 40));

        JLabel heading = new JLabel("Settings");
        heading.setFont(style.titleFont(48));
        heading.setForeground(style.TEXT);
        heading.setAlignmentX(LEFT_ALIGNMENT);
        card.add(heading);

        section(card, "Appearance");
        row(card, "Theme", themeRow(window));
        row(card, "Font", fontBox(window));
        row(card, "Animations", toggle("visual.animations", true, null));
        row(card, "Show legal moves", toggle("visual.moveHints", true, null));
        row(card, "Gray board", toggle("visual.grayBoard", false, null));

        section(card, "Sound");
        row(card, "Background music", toggle("audio.backgroundMusic", false, on -> {
            if (on && !audioHandler.isBackgroundMusicPlaying()) audioHandler.playRandomBackgroundMusic();
            if (!on) audioHandler.stopBackgroundMusic();
        }));
        row(card, "Sound effects", toggle("audio.soundEffects", false, null));

        section(card, "Engines");
        row(card, "Thinking time per move", thinkBox());

        section(card, "Saving");
        row(card, "Save game when closing", toggle("saves.saveState", false, null));
        row(card, "Offer to resume last game", toggle("saves.useSaveState", false, null));

        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        center.add(card);
        JScrollPane scroll = new JScrollPane(center);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);
    }

    private static void section(JPanel card, String title) {
        card.add(Box.createVerticalStrut(22));
        JLabel l = style.label(title.toUpperCase(), Font.BOLD, 14);
        l.setForeground(style.MUTED);
        l.setAlignmentX(LEFT_ALIGNMENT);
        card.add(l);
        card.add(Box.createVerticalStrut(6));
        JSeparator sep = new JSeparator();
        sep.setForeground(fancyButton.mix(style.SIDE_PANEL, style.BORDER, 0.4f));
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        sep.setAlignmentX(LEFT_ALIGNMENT);
        card.add(sep);
    }

    private static void row(JPanel card, String label, JComponent control) {
        JPanel r = new JPanel(new BorderLayout(30, 0));
        r.setOpaque(false);
        r.setBorder(BorderFactory.createEmptyBorder(9, 0, 9, 0));
        r.add(style.label(label, Font.PLAIN, 20), BorderLayout.WEST);
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        holder.setOpaque(false);
        holder.add(control);
        r.add(holder, BorderLayout.EAST);
        r.setAlignmentX(LEFT_ALIGNMENT);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.getPreferredSize().height));
        card.add(r);
    }

    private static toggleSwitch toggle(String key, boolean defaultOn, java.util.function.Consumer<Boolean> extra) {
        String v = configHandler.getConfigString(key, String.valueOf(defaultOn));
        return new toggleSwitch("true".equals(v), on -> {
            configHandler.setConfigBoolean(key, on);
            if (extra != null) extra.accept(on);
        });
    }

    /** A swatch per theme: background, panel and accent; the active one is outlined. */
    private static JComponent themeRow(mainWindow window) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        p.setOpaque(false);
        for (style.theme t : style.THEMES.values()) {
            JComponent sw = new JComponent() {
                boolean hover;
                {
                    setPreferredSize(new Dimension(84, 74));
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    setToolTipText(t.name() + " theme");
                    addMouseListener(new MouseAdapter() {
                        @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                        @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                        @Override public void mouseClicked(MouseEvent e) {
                            if (t == style.current) return;
                            style.click();
                            configHandler.setConfigString("visual.theme", t.name());
                            window.rebuild("settings");
                        }
                    });
                }
                @Override protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int w = 84, h = 52, y = hover ? 0 : 2;
                    g.setColor(t.bg());
                    g.fillRoundRect(2, y, w - 4, h, 14, 14);
                    g.setColor(t.panel());
                    g.fillRoundRect(w / 2, y + 8, w / 2 - 10, h - 16, 8, 8);
                    g.setColor(t.green()); g.fillOval(10, y + 10, 14, 14);
                    g.setColor(t.blue()); g.fillOval(10, y + 28, 14, 14);
                    g.setColor(t.text()); g.fillRoundRect(28, y + 14, 12, 4, 3, 3); g.fillRoundRect(28, y + 32, 10, 4, 3, 3);
                    boolean sel = t == style.current;
                    g.setStroke(new BasicStroke(sel ? 3f : 1.4f));
                    g.setColor(sel ? style.BLUE.darker() : t.border());
                    g.drawRoundRect(2, y, w - 5, h, 14, 14);
                    g.setFont(style.font(sel ? Font.BOLD : Font.PLAIN, 13));
                    g.setColor(style.TEXT);
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(t.name(), (w - fm.stringWidth(t.name())) / 2, 70);
                    g.dispose();
                }
            };
            p.add(sw);
        }
        return p;
    }

    /** Shorter time = faster replies but a shallower search (weaker play). Applies from the next move. */
    private static JComponent thinkBox() {
        long[] choices = ai.searchLimits.CHOICES;
        String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++)
            labels[i] = choices[i] < 1000 ? (choices[i] / 1000.0) + " s" : (choices[i] / 1000) + " s";
        JComboBox<String> box = new JComboBox<>(labels);
        long now = ai.searchLimits.thinkMs();
        int pick = 0;
        for (int i = 0; i < choices.length; i++) if (Math.abs(choices[i] - now) < Math.abs(choices[pick] - now)) pick = i;
        box.setSelectedIndex(pick);
        box.setPreferredSize(new Dimension(230, 40));
        box.setFont(style.font(Font.PLAIN, 18));
        box.setToolTipText("Shorter is faster but the engine looks fewer moves ahead");
        box.addActionListener(_ -> {
            style.click();
            configHandler.setConfigString("engine.thinkMs", String.valueOf(choices[box.getSelectedIndex()]));
        });
        return box;
    }

    private static JComponent fontBox(mainWindow window) {
        JComboBox<String> box = new JComboBox<>(style.FONTS);
        box.setSelectedItem(style.FONT);
        box.setPreferredSize(new Dimension(230, 40));
        box.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
                l.setFont(new Font(String.valueOf(value), Font.PLAIN, 18));
                l.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                return l;
            }
        });
        box.addActionListener(_ -> {
            String f = (String) box.getSelectedItem();
            if (f == null || f.equals(style.FONT)) return;
            style.click();
            configHandler.setConfigString("visual.font", f);
            SwingUtilities.invokeLater(() -> window.rebuild("settings"));
        });
        return box;
    }

    @Override
    protected void paintComponent(Graphics g) {
        style.paintBackdrop((Graphics2D) g, getWidth(), getHeight());
    }
}
