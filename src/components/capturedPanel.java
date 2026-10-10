package components;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Two rows of small icons: pieces each side has captured, plus the material lead. */
public class capturedPanel extends JPanel {
    private static final int[] VALUE = {0, 1, 3, 3, 5, 9, 0};
    private final List<Integer> byWhite = new ArrayList<>(), byBlack = new ArrayList<>();

    public capturedPanel(int width) {
        setOpaque(false);
        setPreferredSize(new Dimension(width, 70));
        setMaximumSize(new Dimension(width, 70));
    }

    /** @param captured signed codes of every captured piece in the game */
    public void setCaptured(List<Integer> captured) {
        byWhite.clear();
        byBlack.clear();
        for (int c : captured) if (c != 0) (c < 0 ? byWhite : byBlack).add(c);
        byWhite.sort((a, b) -> Integer.compare(Math.abs(a), Math.abs(b)));
        byBlack.sort((a, b) -> Integer.compare(Math.abs(a), Math.abs(b)));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int wScore = 0, bScore = 0;
        for (int c : byWhite) wScore += VALUE[Math.abs(c)];
        for (int c : byBlack) bScore += VALUE[Math.abs(c)];
        row(g, "White took:", byWhite, 4, wScore - bScore);
        row(g, "Black took:", byBlack, 38, bScore - wScore);
    }

    private void row(Graphics2D g, String label, List<Integer> pieces, int y, int lead) {
        g.setFont(style.font(Font.ITALIC, 14));
        g.setColor(style.TEXT);
        g.drawString(label, 0, y + 20);
        int x = 88;
        for (int c : pieces) {
            Image img = Board.smallPiece(c, 26);
            if (img != null) g.drawImage(img, x, y, null);
            x += 17;
        }
        if (lead > 0) { g.setFont(style.font(Font.BOLD, 14)); g.drawString("+" + lead, x + 14, y + 20); }
    }
}
