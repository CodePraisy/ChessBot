package components;

import javax.swing.*;
import java.awt.*;

/**
 * The house button, rounded: background colour from setBackground, a 3px dark outline, a lighter shade on hover,
 * a darker one when pressed and a washed-out look when disabled. Drop-in replacement for JButton.
 */
public class fancyButton extends JButton {
    public fancyButton(String text) {
        super(text);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setForeground(style.current.buttonText());
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        int arc = Math.min(22, h);
        Color base = getBackground();
        ButtonModel m = getModel();
        Color fill = !isEnabled() ? mix(base, style.CARD, 0.65f)
                : m.isPressed() ? mix(base, Color.BLACK, 0.15f)
                : m.isRollover() ? mix(base, Color.WHITE, style.isDark() ? 0.14f : 0.3f) : base;
        int lift = isEnabled() && m.isRollover() && !m.isPressed() ? 1 : 0;
        g.setColor(new Color(0, 0, 0, isEnabled() ? 45 : 20));
        g.fillRoundRect(2, 4, w - 4, h - 5, arc, arc);
        g.setColor(fill);
        g.fillRoundRect(1, 1 - lift, w - 3, h - 5, arc, arc);
        g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, style.isDark() ? 30 : 70), 0, h / 2f, new Color(255, 255, 255, 0)));
        g.fillRoundRect(1, 1 - lift, w - 3, h / 2, arc, arc);
        g.setColor(isEnabled() ? mix(style.BORDER, base, 0.2f) : mix(style.BORDER, style.CARD, 0.6f));
        g.setStroke(new BasicStroke(2.5f));
        g.drawRoundRect(2, 2 - lift, w - 5, h - 7, arc, arc);
        g.dispose();
        Graphics2D t = (Graphics2D) g0.create();
        t.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        t.translate(0, -2 - lift);
        super.paintComponent(t);
        t.dispose();
    }

    public static Color mix(Color a, Color b, float k) {
        return new Color((int) (a.getRed() * (1 - k) + b.getRed() * k), (int) (a.getGreen() * (1 - k) + b.getGreen() * k), (int) (a.getBlue() * (1 - k) + b.getBlue() * k));
    }
}
