package components;

import javax.swing.*;
import java.awt.*;

/**
 * Holds a title and a square board (with an optional bar on its left, e.g. the review eval bar).
 * The board takes the largest square that fits and is centred in the space it is given,
 * so it grows with the window and in full-screen mode.
 */
public class boardArea extends JPanel {
    private final JComponent title, board, bar;
    private static final int GAP = 12, TITLE_GAP = 18, PAD = 22;

    public boardArea(JComponent title, JComponent board, JComponent bar) {
        super(null);
        this.title = title;
        this.board = board;
        this.bar = bar;
        setOpaque(false);
        if (title != null) add(title);
        if (bar != null) add(bar);
        add(board);
    }

    public void setTitleVisible(boolean v) {
        if (title != null) title.setVisible(v);
        revalidate();
        repaint();
    }

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight();
        int top = PAD;
        if (title != null && title.isVisible()) {
            int th = title.getPreferredSize().height;
            int barW = bar == null ? 0 : bar.getPreferredSize().width + GAP;
            int s = side(w, h - th - TITLE_GAP, barW);
            // the title sits just above the board, both centred vertically as a group
            int groupH = th + TITLE_GAP + s;
            int y0 = Math.max(PAD / 2, (h - groupH) / 2);
            title.setBounds(0, y0, w, th);
            top = y0 + th + TITLE_GAP;
            place(w, s, barW, top);
        } else {
            int barW = bar == null ? 0 : bar.getPreferredSize().width + GAP;
            int s = side(w, h, barW);
            place(w, s, barW, (h - s) / 2);
        }
    }

    private static int side(int w, int h, int barW) {
        return Math.max(240, Math.min(w - barW - 2 * PAD, h - 2 * PAD));
    }

    private void place(int w, int s, int barW, int y) {
        int x = (w - s - barW) / 2;
        if (bar != null) bar.setBounds(x, y, barW - GAP, s);
        board.setBounds(x + barW, y, s, s);
    }

    @Override
    public Dimension getPreferredSize() { return new Dimension(800, 860); }

    @Override
    public Dimension getMinimumSize() { return new Dimension(400, 400); }
}
