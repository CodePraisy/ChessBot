package components;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/** A sliding on/off switch with an animated knob. */
public class toggleSwitch extends JComponent {
    private boolean on;
    private double pos;
    private final Consumer<Boolean> onChange;
    private final Timer anim = new Timer(15, _ -> step());

    public toggleSwitch(boolean on, Consumer<Boolean> onChange) {
        this.on = on;
        this.pos = on ? 1 : 0;
        this.onChange = onChange;
        setPreferredSize(new Dimension(62, 34));
        setMaximumSize(new Dimension(62, 34));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { toggle(); }
        });
    }

    public boolean isOn() { return on; }

    private void toggle() {
        on = !on;
        style.click();
        anim.start();
        onChange.accept(on);
    }

    private void step() {
        double target = on ? 1 : 0;
        pos += (target - pos) * 0.3;
        if (Math.abs(target - pos) < 0.01) { pos = target; anim.stop(); }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        Color offC = fancyButton.mix(style.GRAY, style.CARD, 0.2f), onC = style.isDark() ? style.GREEN : fancyButton.mix(style.GREEN, Color.BLACK, 0.18f);
        g.setColor(fancyButton.mix(offC, onC, (float) pos));
        g.fillRoundRect(1, 1, w - 2, h - 2, h, h);
        g.setColor(fancyButton.mix(style.BORDER, style.CARD, 0.3f));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(1, 1, w - 3, h - 3, h - 2, h - 2);
        int d = h - 8;
        int x = (int) (4 + (w - d - 8) * pos);
        g.setColor(new Color(0, 0, 0, 40));
        g.fillOval(x, 6, d, d);
        g.setColor(Color.WHITE);
        g.fillOval(x, 4, d, d);
        g.dispose();
    }
}
