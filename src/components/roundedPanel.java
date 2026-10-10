package components;

import javax.swing.*;
import java.awt.*;

/** A panel drawn as a rounded card with a soft drop shadow and a thin themed outline. */
public class roundedPanel extends JPanel {
    private final Color fill;
    private final int arc;
    private final boolean shadow;

    public roundedPanel(Color fill) { this(fill, 28, true); }

    public roundedPanel(Color fill, int arc, boolean shadow) {
        this.fill = fill;
        this.arc = arc;
        this.shadow = shadow;
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight(), inset = shadow ? 8 : 2;
        if (shadow) for (int i = 0; i < 7; i++) {
            g.setColor(new Color(0, 0, 0, style.isDark() ? 22 : 11));
            g.fillRoundRect(i / 2 + 2, i + 2, w - 4 - i / 2, h - 4 - i / 2, arc + 4, arc + 4);
        }
        g.setPaint(new GradientPaint(0, 0, fancyButton.mix(fill, Color.WHITE, style.isDark() ? 0.03f : 0.08f), 0, h, fancyButton.mix(fill, Color.BLACK, 0.05f)));
        g.fillRoundRect(1, 1, w - inset, h - inset, arc, arc);
        g.setColor(fancyButton.mix(style.BORDER, fill, 0.35f));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(1, 1, w - inset, h - inset, arc, arc);
        g.dispose();
    }
}
