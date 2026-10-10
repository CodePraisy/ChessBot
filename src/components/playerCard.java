package components;

import javax.swing.*;
import java.awt.*;

/** One player's name plate: king icon, name, a status line, and a gently glowing outline while it is their turn. */
public class playerCard extends JComponent {
    private final int color;
    private String name = "", status = "";
    private boolean active;
    private int dots;
    private final Timer pulse = new Timer(60, _ -> repaint());

    /** @param color rules.Position.WHITE or BLACK */
    public playerCard(int color) {
        this.color = color;
        setPreferredSize(new Dimension(410, 52));
    }

    public void update(String name, String status, boolean active) {
        boolean changed = !name.equals(this.name) || !status.equals(this.status) || active != this.active;
        this.name = name;
        this.status = status;
        this.active = active;
        if (active && Board.animationsOn()) { if (!pulse.isRunning()) pulse.start(); } else pulse.stop();
        if (changed) repaint();
    }

    /** Animated "thinking..." dots in the status line. */
    public void tickDots() { dots = (dots + 1) % 4; repaint(); }
    public String dots() { return ".".repeat(dots); }

    @Override public void removeNotify() { pulse.stop(); super.removeNotify(); }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        Color bg = active ? fancyButton.mix(style.CARD, style.YELLOW, style.isDark() ? 0.18f : 0.35f) : style.CARD;
        g.setColor(bg);
        g.fillRoundRect(1, 1, w - 3, h - 3, 18, 18);
        if (active) {
            double p = Board.animationsOn() ? 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 300.0) : 1;
            Color gc = style.isDark() ? new Color(90, 210, 140) : new Color(46, 139, 87);
            g.setColor(new Color(gc.getRed(), gc.getGreen(), gc.getBlue(), (int) (120 + 120 * p)));
            g.setStroke(new BasicStroke(4));
        } else {
            g.setColor(fancyButton.mix(style.CARD, style.BORDER, 0.5f));
            g.setStroke(new BasicStroke(2));
        }
        g.drawRoundRect(2, 2, w - 5, h - 5, 18, 18);
        Image king = Board.smallPiece(color * 6, 38);
        if (king != null) {
            g.setColor(fancyButton.mix(style.CARD, style.isDark() ? Color.WHITE : style.BORDER, 0.12f));
            g.fillOval(7, (h - 44) / 2, 44, 44);
            g.drawImage(king, 10, (h - king.getHeight(null)) / 2, null);
        }
        g.setColor(style.TEXT);
        g.setFont(style.font(Font.BOLD, 20));
        String n = name.length() > 22 ? name.substring(0, 21) + "…" : name;
        g.drawString(n, 58, status.isEmpty() ? h / 2 + 7 : h / 2 - 1);
        g.setFont(style.font(Font.ITALIC, 14));
        g.setColor(active ? (style.isDark() ? new Color(110, 220, 150) : new Color(46, 120, 80)) : style.MUTED);
        g.drawString(status, 58, h / 2 + 17);
        g.dispose();
    }
}
