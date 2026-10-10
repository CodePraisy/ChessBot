package review;

import javax.swing.*;
import java.awt.*;

import ai.Suggestion;
import components.style;

/** Vertical evaluation bar: white fills from the bottom in proportion to White's advantage. */
public class evalBar extends JComponent {
    private Integer whiteScore; // null = no evaluation
    private String text = "";
    private boolean flipped;

    public evalBar(int width, int height) {
        setPreferredSize(new Dimension(width, height));
        setMinimumSize(new Dimension(width, height));
        setMaximumSize(new Dimension(width, height));
    }

    /** @param score White's point of view (centipawns or Suggestion mate score); null clears the bar */
    public void setScore(Integer score, String label) {
        whiteScore = score;
        text = label == null ? "" : label;
        if (score != null && components.Board.animationsOn()) { if (!anim.isRunning()) anim.start(); }
        else { shown = target(); repaint(); }
    }

    private double shown = 0.5;
    private final Timer anim = new Timer(15, _ -> animStep());

    /** Eases the bar toward the new evaluation. */
    private void animStep() {
        double t = target();
        shown += (t - shown) * 0.18;
        if (Math.abs(t - shown) < 0.002) { shown = t; anim.stop(); }
        repaint();
    }

    private double target() {
        if (whiteScore == null) return 0.5;
        if (whiteScore >= Suggestion.MATE_SCORE / 2) return 1;
        if (whiteScore <= -Suggestion.MATE_SCORE / 2) return 0;
        return 1 / (1 + Math.exp(-whiteScore / 250.0));
    }

    public void setFlipped(boolean f) { flipped = f; repaint(); }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g.setColor(new Color(60, 60, 60));
        g.fillRect(0, 0, w, h);
        if (whiteScore != null) {
            int wh = (int) Math.round(h * shown);
            g.setColor(new Color(240, 236, 226));
            if (flipped) g.fillRect(0, 0, w, wh); else g.fillRect(0, h - wh, w, wh);
        }
        g.setColor(new Color(150, 150, 150));
        g.drawLine(0, h / 2, w, h / 2);
        g.setFont(style.font(Font.BOLD, 11));
        boolean whiteAhead = whiteScore != null && whiteScore >= 0;
        g.setColor(whiteAhead ? new Color(40, 40, 40) : new Color(235, 235, 235));
        int y = whiteAhead != flipped ? h - 6 : 14;
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, Math.max(1, (w - fm.stringWidth(text)) / 2), y);
        g.setColor(style.BORDER);
        g.setStroke(new BasicStroke(3));
        g.drawRect(1, 1, w - 3, h - 3);
        g.dispose();
    }
}
