package components;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Menu backdrop: the house colour with faint chess pieces drifting and turning slowly. */
public class menuBackground extends JPanel {
    private record floater(int piece, double x, double y, double vx, double vy, double rot, double vrot, double size, float alpha) {}
    private final List<floater> floaters = new ArrayList<>();
    private final Timer timer = new Timer(33, _ -> step());
    private final Color base;

    public menuBackground(Color base) {
        this.base = base;
        setBackground(base);
        Random r = new Random(7);
        int[] pieces = {1, 2, 3, 4, 5, 6, -1, -2, -3, -4, -5, -6, 2, -2, 5, -5};
        for (int p : pieces)
            floaters.add(new floater(p, r.nextDouble() * 1400, r.nextDouble() * 1000, (r.nextDouble() - 0.5) * 0.5, -0.15 - r.nextDouble() * 0.35,
                    r.nextDouble() * Math.PI, (r.nextDouble() - 0.5) * 0.004, 60 + r.nextDouble() * 70, 0.06f + r.nextFloat() * 0.07f));
    }

    @Override public void addNotify() { super.addNotify(); timer.start(); }
    @Override public void removeNotify() { timer.stop(); super.removeNotify(); }

    private void step() {
        if (!isShowing() || !Board.animationsOn()) return;
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        for (int i = 0; i < floaters.size(); i++) {
            floater f = floaters.get(i);
            double x = f.x() + f.vx(), y = f.y() + f.vy();
            if (y < -f.size()) y = h + f.size();
            if (x < -f.size()) x = w + f.size(); else if (x > w + f.size()) x = -f.size();
            floaters.set(i, new floater(f.piece(), x, y, f.vx(), f.vy(), f.rot() + f.vrot(), f.vrot(), f.size(), f.alpha()));
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        int w = getWidth(), h = getHeight();
        style.paintBackdrop(g, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (floater f : floaters) {
            Image img = Board.smallPiece(f.piece(), 120);
            if (img == null) continue;
            Graphics2D pg = (Graphics2D) g.create();
            pg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, f.alpha()));
            pg.translate(f.x(), f.y());
            pg.rotate(f.rot());
            double s = f.size() / 120.0;
            pg.scale(s, s);
            pg.drawImage(img, -img.getWidth(null) / 2, -img.getHeight(null) / 2, null);
            pg.dispose();
        }
        g.dispose();
    }
}
