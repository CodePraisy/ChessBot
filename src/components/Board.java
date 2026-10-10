package components;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import handlers.configHandler;
import handlers.paths;
import rules.Move;
import rules.Position;

/**
 * 8x8 board with file/rank labels, textured squares and piece images.
 * Input: click-to-select + click-to-move, drag-and-drop, right-click to draw your own arrows and circles.
 * Highlights the selection, legal targets, the last move and a king in check; draws suggestion arrows.
 * Animations (Settings > Animations): pieces slide (knights hop), captured pieces spin away, the king
 * shakes when checked, promotions sparkle, the board flips smoothly, turn banners and confetti.
 */
public class Board extends JPanel {
    private static final int SIZE = 750, MARGIN = 30, SQ = (SIZE - MARGIN) / 8;
    /** Images are kept at twice the logical size so they stay crisp when the board is scaled up. */
    private static final int HI = 2;
    private boolean showCoordinates = true;

    private Position position = Position.start();
    private boolean flipped;
    /** Which colour the user may move: Position.WHITE, Position.BLACK, BOTH, or NONE. */
    private int movable = BOTH;
    public static final int BOTH = 2, NONE = 0;
    private Move lastMove;
    private final List<Move> arrows = new ArrayList<>();
    private Consumer<Move> onMove = _ -> {};

    // input state
    private int selected = -1;
    private List<Move> targets = List.of();
    private int dragFrom = -1;
    private Point dragPoint;
    private boolean dragged;
    private int hover = -1;
    private boolean skipNextAnimation;
    private long selectedAt;

    // right-click annotations (your own arrows and circles)
    private int rightFrom = -1;
    private final List<Move> userArrows = new ArrayList<>();
    private final Set<Integer> userCircles = new LinkedHashSet<>();

    // animation state
    private final Timer ticker = new Timer(15, _ -> tick());
    private long moveStart = -1, moveDuration;
    private final List<slide> slides = new ArrayList<>();
    private int fadingPiece, fadingSquare = -1;   // captured piece spinning away (or reappearing when reversed)
    private boolean fadingIn;
    private long shakeStart = -1;
    private long flipStart = -1;
    private boolean flipTarget;
    private String bannerText;
    private Color bannerColor;
    private long bannerStart = -1;
    private final List<particle> particles = new ArrayList<>();
    private final Random rnd = new Random();

    private record slide(int piece, int from, int to, boolean hop) {}

    private static final class particle {
        double x, y, vx, vy, rot, vrot, size, life, maxLife;
        Color color;
        boolean star;
    }

    private static final Map<Integer, Image> pieceImages = new HashMap<>();
    private static final Map<Integer, Image> smallImages = new HashMap<>();
    private static Image brownLight, brownDark, grayLight, grayDark;

    public Board() {
        this.setPreferredSize(new Dimension(SIZE, SIZE));
        this.setMinimumSize(new Dimension(240, 240));
        this.setOpaque(false);
        loadImages();

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) { rightFrom = squareAt(logical(e)); clearSelection(); repaint(); return; }
                userArrows.clear();
                userCircles.clear();
                pressed(logical(e));
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragFrom >= 0 && SwingUtilities.isLeftMouseButton(e)) { dragPoint = logical(e); dragged = true; repaint(); }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) { annotate(rightFrom, squareAt(logical(e))); return; }
                released(logical(e));
            }
            @Override public void mouseMoved(MouseEvent e) { hoverAt(logical(e)); }
            @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    // The board is laid out in fixed logical units (SIZE x SIZE) and scaled to whatever square it is given.
    private double scale() { return Math.max(0.1, Math.min(getWidth(), getHeight()) / (double) SIZE); }
    private double offX() { return (getWidth() - SIZE * scale()) / 2; }
    private double offY() { return (getHeight() - SIZE * scale()) / 2; }

    private Point logical(MouseEvent e) {
        double s = scale();
        return new Point((int) Math.floor((e.getX() - offX()) / s), (int) Math.floor((e.getY() - offY()) / s));
    }

    /** Settings > Animations. */
    public static boolean animationsOn() { return !"false".equals(configHandler.getConfigString("visual.animations", "true")); }
    private static boolean hintsOn() { return !"false".equals(configHandler.getConfigString("visual.moveHints", "true")); }

    // ---------- public API ----------

    /** Shows p. If p is one move away from what is shown (either direction), the move is animated. */
    public void setPosition(Position p, Move last) {
        if (p.equals(position)) {
            if (last == null ? lastMove != null : !last.equals(lastMove)) { lastMove = last; repaint(); }
            return;
        }
        Position old = position;
        Move oldLast = lastMove;
        position = p;
        lastMove = last;
        clearSelection();
        boolean skip = skipNextAnimation;
        skipNextAnimation = false;
        if (!animationsOn()) { repaint(); return; }
        if (last != null && old.isLegal(last) && old.play(last).equals(p)) startMove(old, last, false, skip);
        else if (oldLast != null && p.isLegal(oldLast) && p.play(oldLast).equals(old)) startMove(p, oldLast, true, false);
        repaint();
    }

    public Position getPosition() { return position; }
    public void setFlipped(boolean f) { flipped = f; flipStart = -1; repaint(); }
    public boolean isFlipped() { return flipStart >= 0 ? flipTarget : flipped; }

    /** Turns the board round with a short flip animation (instant when animations are off). */
    public void flipTo(boolean f) {
        if (f == flipTarget(f)) return;
        if (!animationsOn() || !isShowing()) { setFlipped(f); return; }
        flipTarget = f;
        // let a piece that is still sliding land first, then turn the board
        flipStart = moveStart >= 0 ? moveStart + moveDuration + 120 : now();
        startTicker();
    }

    private boolean flipTarget(boolean f) { return flipStart >= 0 ? flipTarget : flipped; }

    public void setMovable(int who) {
        if (who == movable) return;
        movable = who;
        clearSelection();
        repaint();
    }

    public void setOnMove(Consumer<Move> c) { onMove = c; }
    public void setArrows(List<Move> a) { arrows.clear(); if (a != null) arrows.addAll(a); repaint(); }
    public void setShowCoordinates(boolean s) { showCoordinates = s; repaint(); }

    public void clearAnnotations() {
        userArrows.clear();
        userCircles.clear();
        clearSelection();
        repaint();
    }

    /** A pill-shaped message that slides in over the top of the board and fades away. */
    public void showBanner(String text, Color color) {
        bannerText = text;
        bannerColor = color;
        bannerStart = now();
        startTicker();
    }

    /** Confetti! */
    public void celebrate() {
        if (!animationsOn()) return;
        Color[] palette = {style.GREEN, style.BLUE, style.PURPLE, style.YELLOW, style.RED, new Color(255, 255, 255), new Color(255, 170, 80)};
        for (int i = 0; i < 160; i++) {
            particle p = new particle();
            p.x = MARGIN + rnd.nextDouble() * SQ * 8;
            p.y = -20 - rnd.nextDouble() * 200;
            p.vx = (rnd.nextDouble() - 0.5) * 3;
            p.vy = 2 + rnd.nextDouble() * 3;
            p.rot = rnd.nextDouble() * Math.PI;
            p.vrot = (rnd.nextDouble() - 0.5) * 0.3;
            p.size = 6 + rnd.nextDouble() * 8;
            p.maxLife = p.life = 3.5 + rnd.nextDouble();
            p.color = palette[rnd.nextInt(palette.length)];
            particles.add(p);
        }
        startTicker();
    }

    /** Small piece icon for side panels (captured pieces, promotion chooser). */
    public static Image smallPiece(int code, int px) {
        loadImages();
        return smallImages.computeIfAbsent(code * 1000 + px, _ -> {
            Image img = pieceImages.get(code);
            return img == null ? null : scaleToFit(img, px);  // from the 2x image, so small icons stay sharp
        });
    }

    // ---------- images ----------

    private static synchronized void loadImages() {
        if (!pieceImages.isEmpty()) return;
        String dir = paths.piecesDir();
        String[] names = {"", "pawn", "knight", "bishop", "rook", "queen", "king"};
        String size = dir.replaceAll(".*?(\\d+px).*", "$1");
        for (int t = 1; t <= 6; t++) {
            pieceImages.put(t, read(dir + "/w_" + names[t] + "_png_shadow_" + size + ".png"));
            pieceImages.put(-t, read(dir + "/b_" + names[t] + "_png_shadow_" + size + ".png"));
        }
        brownLight = texture(dir + "/square brown light_png_shadow_" + size + ".png");
        brownDark = texture(dir + "/square brown dark_png_shadow_" + size + ".png");
        grayLight = texture(dir + "/square gray light _png_shadow_" + size + ".png");
        grayDark = texture(dir + "/square gray dark _png_shadow_" + size + ".png");
        for (int t = -6; t <= 6; t++) {
            Image img = pieceImages.get(t);
            if (img != null) pieceImages.put(t, scaleToFit(img, (int) (SQ * 0.86) * HI));
        }
    }

    private static Image texture(String path) {
        BufferedImage b = read(path);
        return b == null ? null : scaled(b, SQ * HI, SQ * HI);
    }

    private static BufferedImage read(String path) {
        try {
            return ImageIO.read(new File(path));
        } catch (Exception e) {
            System.err.println("Missing image: " + path);
            return null;
        }
    }

    private static Image scaleToFit(Image img, int box) {
        int w = img.getWidth(null), h = img.getHeight(null);
        double s = (double) box / Math.max(w, h);
        return scaled((BufferedImage) img, Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s)));
    }

    /** High-quality synchronous downscale (halving steps, then bicubic). */
    private static BufferedImage scaled(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) cur = draw(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        return draw(cur, w, h);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // ---------- geometry ----------

    private int squareAt(Point p) {
        int x = p.x - MARGIN, y = p.y;
        if (x < 0 || y < 0 || x >= SQ * 8 || y >= SQ * 8) return -1;
        int c = x / SQ, r = y / SQ;
        if (flipped) { c = 7 - c; r = 7 - r; }
        return r * 8 + c;
    }

    private Point origin(int sq) {
        int r = sq / 8, c = sq % 8;
        if (flipped) { r = 7 - r; c = 7 - c; }
        return new Point(MARGIN + c * SQ, r * SQ);
    }

    // ---------- input ----------

    private boolean canMove(int sq) {
        int p = position.pieceAt(sq);
        if (p == 0 || movable == NONE) return false;
        int color = Integer.signum(p);
        return color == position.sideToMove() && (movable == BOTH || movable == color);
    }

    private void hoverAt(Point pt) {
        int sq = squareAt(pt);
        if (sq == hover) return;
        hover = sq;
        boolean target = false;
        for (Move m : targets) if (m.to() == sq) target = true;
        setCursor(Cursor.getPredefinedCursor(sq >= 0 && (canMove(sq) || target) ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
    }

    private void pressed(Point pt) {
        int sq = squareAt(pt);
        if (sq < 0) { clearSelection(); repaint(); return; }
        if (selected >= 0 && sq != selected && tryMove(selected, sq, false)) return;
        if (sq == selected) { dragFrom = sq; dragPoint = pt; dragged = false; repaint(); return; }
        if (canMove(sq)) {
            selected = sq;
            selectedAt = now();
            targets = position.legalMovesFrom(sq);
            dragFrom = sq;
            dragPoint = pt;
            dragged = false;
            startTicker();
        } else {
            clearSelection();
        }
        repaint();
    }

    private void released(Point pt) {
        if (dragFrom < 0) return;
        int from = dragFrom;
        boolean wasDrag = dragged;
        dragFrom = -1;
        dragPoint = null;
        dragged = false;
        int sq = squareAt(pt);
        if (wasDrag && sq >= 0 && sq != from) {
            if (!tryMove(from, sq, true)) clearSelection();
        }
        repaint();
    }

    private boolean tryMove(int from, int to, boolean fromDrag) {
        List<Move> options = new ArrayList<>();
        for (Move m : position.legalMovesFrom(from)) if (m.to() == to) options.add(m);
        if (options.isEmpty()) return false;
        Move chosen = options.get(0);
        if (options.size() > 1) {
            int promo = choosePromotion(position.sideToMove());
            if (promo == 0) { clearSelection(); repaint(); return true; }
            for (Move m : options) if (m.promotion() == promo) chosen = m;
        }
        clearSelection();
        skipNextAnimation = fromDrag; // a dropped piece is already where it belongs
        repaint();
        onMove.accept(chosen);
        skipNextAnimation = false;
        return true;
    }

    private void annotate(int from, int to) {
        if (from < 0 || to < 0) return;
        if (from == to) {
            if (!userCircles.remove(from)) userCircles.add(from);
        } else {
            Move a = new Move(from, to);
            if (!userArrows.remove(a)) userArrows.add(a);
        }
        repaint();
    }

    private int choosePromotion(int color) {
        int[] types = {Position.QUEEN, Position.ROOK, Position.BISHOP, Position.KNIGHT};
        String[] names = {"Queen", "Rook", "Bishop", "Knight"};
        Object[] options = new Object[4];
        for (int i = 0; i < 4; i++) {
            Image img = smallPiece(color * types[i], 64);
            JButton b = new fancyButton(names[i]);
            if (img != null) b.setIcon(new ImageIcon(img));
            b.setVerticalTextPosition(SwingConstants.BOTTOM);
            b.setHorizontalTextPosition(SwingConstants.CENTER);
            b.setFont(style.font(Font.ITALIC, 16));
            b.setBackground(style.SIDE_PANEL);
            b.setPreferredSize(new Dimension(110, 110));
            b.setCursor(new Cursor(Cursor.HAND_CURSOR));
            b.addActionListener(_ -> {
                style.click();
                JOptionPane pane = (JOptionPane) SwingUtilities.getAncestorOfClass(JOptionPane.class, b);
                if (pane != null) pane.setValue(b);
            });
            options[i] = b;
        }
        JLabel msg = style.label("Promote pawn to:", Font.BOLD, 20);
        int r = JOptionPane.showOptionDialog(this, msg, "Promotion", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        return r >= 0 && r < 4 ? types[r] : 0;
    }

    private void clearSelection() {
        selected = -1;
        targets = List.of();
        dragFrom = -1;
        dragPoint = null;
        dragged = false;
    }

    // ---------- animation ----------

    private static long now() { return System.currentTimeMillis(); }

    private static double easeOut(double t) { return 1 - Math.pow(1 - t, 3); }

    private void startMove(Position before, Move m, boolean reverse, boolean skipSlide) {
        slides.clear();
        int piece = before.pieceAt(m.from());
        boolean knight = Math.abs(piece) == Position.KNIGHT;
        if (!skipSlide) {
            if (reverse) slides.add(new slide(piece, m.to(), m.from(), knight));
            else slides.add(new slide(piece, m.from(), m.to(), knight));
            if (before.isCastle(m)) {
                boolean king = m.to() > m.from();
                int rookFrom = king ? m.from() + 3 : m.from() - 4, rookTo = king ? m.from() + 1 : m.from() - 1;
                int rook = before.pieceAt(rookFrom);
                slides.add(reverse ? new slide(rook, rookTo, rookFrom, false) : new slide(rook, rookFrom, rookTo, false));
            }
        }
        int captured = before.capturedBy(m);
        fadingSquare = -1;
        if (captured != 0) {
            fadingPiece = captured;
            fadingSquare = before.isEnPassant(m) ? m.to() - (before.sideToMove() == Position.WHITE ? -8 : 8) : m.to();
            fadingIn = reverse;
        }
        moveDuration = knight ? 320 : 230;
        moveStart = now();
        Position after = before.play(m);
        if (!reverse) {
            if (after.inCheck()) shakeStart = moveStart + moveDuration;
            if (m.promotion() != 0) sparkle(m.to(), moveStart + moveDuration);
        }
        startTicker();
    }

    private final List<long[]> pendingSparkles = new ArrayList<>();

    private void sparkle(int sq, long at) { pendingSparkles.add(new long[]{sq, at}); }

    private void burst(int sq) {
        Point o = origin(sq);
        for (int i = 0; i < 26; i++) {
            particle p = new particle();
            double a = rnd.nextDouble() * Math.PI * 2, speed = 2 + rnd.nextDouble() * 4;
            p.x = o.x + SQ / 2.0;
            p.y = o.y + SQ / 2.0;
            p.vx = Math.cos(a) * speed;
            p.vy = Math.sin(a) * speed - 1;
            p.size = 7 + rnd.nextDouble() * 7;
            p.maxLife = p.life = 0.8 + rnd.nextDouble() * 0.5;
            p.color = rnd.nextBoolean() ? new Color(255, 215, 80) : Color.WHITE;
            p.star = true;
            p.vrot = (rnd.nextDouble() - 0.5) * 0.4;
            particles.add(p);
        }
    }

    private void startTicker() { if (!ticker.isRunning()) ticker.start(); }

    private void tick() {
        long t = now();
        for (int i = pendingSparkles.size() - 1; i >= 0; i--) {
            long[] s = pendingSparkles.get(i);
            if (t >= s[1]) { burst((int) s[0]); pendingSparkles.remove(i); }
        }
        if (moveStart >= 0 && t - moveStart > moveDuration) { moveStart = -1; slides.clear(); fadingSquare = -1; }
        if (shakeStart >= 0 && t - shakeStart > 450) shakeStart = -1;
        if (flipStart >= 0 && t - flipStart > 420) { flipStart = -1; flipped = flipTarget; }
        else if (flipStart >= 0 && t - flipStart > 210 && flipped != flipTarget) { flipped = flipTarget; hover = -1; } // swap at the edge-on moment
        if (bannerStart >= 0 && t - bannerStart > 1500) bannerStart = -1;
        for (int i = particles.size() - 1; i >= 0; i--) {
            particle p = particles.get(i);
            p.x += p.vx;
            p.y += p.vy;
            p.vy += p.star ? 0.12 : 0.05;
            p.vx *= 0.99;
            p.rot += p.vrot;
            p.life -= 0.015;
            if (p.life <= 0 || p.y > SIZE + 20) particles.remove(i);
        }
        boolean pulsing = (selected >= 0 && hintsOn()) || position.inCheck();
        boolean busy = moveStart >= 0 || shakeStart >= 0 || flipStart >= 0 || bannerStart >= 0 || !particles.isEmpty() || !pendingSparkles.isEmpty();
        if (!busy && !(pulsing && animationsOn()) && t - selectedAt > 200) ticker.stop();
        if (!isShowing()) ticker.stop();
        repaint();
    }

    // ---------- painting ----------

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        long t = now();
        boolean anim = animationsOn();
        double sc = scale();
        g.translate(offX(), offY());
        g.scale(sc, sc);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        // smooth flip: squash horizontally to edge-on, swap sides, open up again
        if (flipStart >= 0 && t >= flipStart) {
            double f = Math.min(1, (t - flipStart) / 420.0);
            double sx = Math.max(0.02, Math.abs(Math.cos(f * Math.PI)));
            double cx = SIZE / 2.0;
            g.translate(cx, 0);
            g.scale(sx, 1);
            g.translate(-cx, 0);
        }

        // wooden frame with the coordinate labels
        Shape frame = new RoundRectangle2D.Double(0, 0, SIZE, SIZE, 22, 22);
        g.setPaint(new GradientPaint(0, 0, style.current.frameTop(), 0, SIZE, style.current.frameBottom()));
        g.fill(frame);
        if (showCoordinates) {
            g.setColor(new Color(240, 225, 200));
            g.setFont(style.font(Font.BOLD, 14));
            FontMetrics fm = g.getFontMetrics();
            for (int i = 0; i < 8; i++) {
                String rank = String.valueOf(flipped ? i + 1 : 8 - i);
                g.drawString(rank, (MARGIN - fm.stringWidth(rank)) / 2, i * SQ + SQ / 2 + fm.getAscent() / 2 - 2);
                String file = String.valueOf((char) ('A' + (flipped ? 7 - i : i)));
                g.drawString(file, MARGIN + i * SQ + (SQ - fm.stringWidth(file)) / 2, SQ * 8 + MARGIN / 2 + fm.getAscent() / 2 - 2);
            }
        }

        // squares (clipped so the frame's rounded corner shows)
        Shape oldClip = g.getClip();
        g.clip(new RoundRectangle2D.Double(MARGIN, 0, SQ * 8, SQ * 8, 16, 16));
        boolean gray = configHandler.getConfigBoolean("visual.grayBoard");
        Image lightTex = gray ? grayLight : brownLight, darkTex = gray ? grayDark : brownDark;
        for (int sq = 0; sq < 64; sq++) {
            Point o = origin(sq);
            boolean light = (sq / 8 + sq % 8) % 2 == 0;
            Image tex = light ? lightTex : darkTex;
            if (tex != null) g.drawImage(tex, o.x, o.y, SQ, SQ, null);
            else { g.setColor(light ? new Color(181, 136, 99) : new Color(110, 70, 60)); g.fillRect(o.x, o.y, SQ, SQ); }
        }

        // last move, hover, selection, check
        if (lastMove != null) {
            g.setColor(new Color(255, 230, 80, 105));
            for (int sq : new int[]{lastMove.from(), lastMove.to()}) { Point o = origin(sq); g.fillRect(o.x, o.y, SQ, SQ); }
        }
        if (hover >= 0 && dragFrom < 0 && (canMove(hover) || isTarget(hover))) {
            Point o = origin(hover);
            g.setColor(new Color(255, 255, 255, 45));
            g.fillRect(o.x, o.y, SQ, SQ);
        }
        if (selected >= 0) {
            Point o = origin(selected);
            g.setColor(new Color(120, 220, 120, 140));
            g.fillRect(o.x, o.y, SQ, SQ);
        }
        if (position.inCheck()) {
            Point o = origin(position.kingSquare(position.sideToMove()));
            double pulse = anim ? 0.75 + 0.25 * Math.sin(t / 160.0) : 1;
            RadialGradientPaint glow = new RadialGradientPaint(new Point(o.x + SQ / 2, o.y + SQ / 2), (float) (SQ * 0.68 * pulse),
                    new float[]{0f, 1f}, new Color[]{new Color(255, 0, 0, 220), new Color(255, 0, 0, 0)});
            g.setPaint(glow);
            g.fillRect(o.x, o.y, SQ, SQ);
        }
        for (int sq : userCircles) {
            Point o = origin(sq);
            g.setColor(new Color(235, 120, 30, 200));
            g.setStroke(new BasicStroke(5));
            g.drawOval(o.x + 4, o.y + 4, SQ - 8, SQ - 8);
        }

        // pieces
        double moveT = moveStart >= 0 ? easeOut(Math.min(1, (t - moveStart) / (double) moveDuration)) : 1;
        Set<Integer> hidden = new LinkedHashSet<>();
        if (moveStart >= 0) for (slide s : slides) hidden.add(s.to);
        if (fadingSquare >= 0 && moveStart >= 0) {
            double ft = Math.min(1, (t - moveStart) / (double) moveDuration);
            double k = fadingIn ? ft : 1 - ft;  // 1 = fully there
            if (fadingIn) hidden.add(fadingSquare);
            Point o = origin(fadingSquare);
            drawPieceScaled(g, fadingPiece, o.x + SQ / 2.0, o.y + SQ / 2.0, 0.4 + 0.6 * k, (1 - k) * Math.PI * 0.8, (float) k);
        }
        int king = position.inCheck() ? position.kingSquare(position.sideToMove()) : -1;
        for (int sq = 0; sq < 64; sq++) {
            int p = position.pieceAt(sq);
            if (p == 0 || hidden.contains(sq) || (sq == dragFrom && dragged)) continue;
            Point o = origin(sq);
            double cx = o.x + SQ / 2.0, cy = o.y + SQ / 2.0;
            if (sq == king && shakeStart >= 0 && t >= shakeStart) {
                double st = (t - shakeStart) / 450.0;
                cx += Math.sin(st * 40) * 5 * (1 - st);
            }
            if (sq == selected && anim && !dragged) {
                double lift = Math.min(1, (t - selectedAt) / 120.0);
                shadow(g, cx, cy + SQ * 0.34, lift);
                drawPieceScaled(g, p, cx, cy - 6 * lift, 1 + 0.08 * lift, 0, 1f);
            } else {
                drawPieceScaled(g, p, cx, cy, 1, 0, 1f);
            }
        }
        for (slide s : slides) {
            if (moveStart < 0) break;
            Point a = origin(s.from), b = origin(s.to);
            double x = a.x + (b.x - a.x) * moveT + SQ / 2.0, y = a.y + (b.y - a.y) * moveT + SQ / 2.0;
            double hop = s.hop ? Math.sin(moveT * Math.PI) : 0;
            shadow(g, x, y + SQ * 0.34, 0.4 + hop * 0.6);
            drawPieceScaled(g, s.piece, x, y - hop * SQ * 0.45, 1 + 0.18 * hop + 0.04 * Math.sin(moveT * Math.PI), 0, 1f);
        }

        // legal targets: dots on empty squares, rings on captures
        if (hintsOn()) {
            double pulse = anim ? 0.5 + 0.5 * Math.sin(t / 220.0) : 0.5;
            for (Move m : targets) {
                Point o = origin(m.to());
                boolean capture = position.capturedBy(m) != 0;
                int alpha = (int) (85 + 40 * pulse);
                if (capture) {
                    g.setColor(new Color(30, 30, 30, alpha));
                    g.setStroke(new BasicStroke(7));
                    g.drawOval(o.x + 5, o.y + 5, SQ - 10, SQ - 10);
                } else {
                    g.setColor(new Color(30, 30, 30, alpha));
                    double d = SQ / 4.0 * (m.to() == hover ? 1.35 : 1.0);
                    g.fill(new java.awt.geom.Ellipse2D.Double(o.x + (SQ - d) / 2, o.y + (SQ - d) / 2, d, d));
                }
            }
        }

        // suggestion arrows (first is strongest), then the user's own arrows
        for (int i = arrows.size() - 1; i >= 0; i--) {
            Color c = i == 0 ? new Color(40, 160, 70, 190) : new Color(40, 110, 200, 140);
            drawArrow(g, arrows.get(i), c, i == 0 ? 14 : 10);
        }
        for (Move a : userArrows) drawArrow(g, a, new Color(235, 120, 30, 200), 12);

        g.setClip(oldClip);

        // dragged piece on top, a little bigger
        if (dragged && dragFrom >= 0 && dragPoint != null) {
            shadow(g, dragPoint.x, dragPoint.y + SQ * 0.38, 1);
            drawPieceScaled(g, position.pieceAt(dragFrom), dragPoint.x, dragPoint.y - 4, 1.12, 0, 1f);
        }

        // particles (sparkles and confetti)
        for (particle p : particles) {
            Graphics2D pg = (Graphics2D) g.create();
            pg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, p.life / p.maxLife * 1.5))));
            pg.translate(p.x, p.y);
            pg.rotate(p.rot);
            pg.setColor(p.color);
            if (p.star) pg.fill(star(p.size));
            else pg.fill(new java.awt.geom.Rectangle2D.Double(-p.size / 2, -p.size / 4, p.size, p.size / 2));
            pg.dispose();
        }
        g.dispose();

        // banner over the top of the board (not flipped)
        if (bannerStart >= 0 && bannerText != null) {
            Graphics2D bg = (Graphics2D) g0.create();
            bg.translate(offX(), offY());
            bg.scale(sc, sc);
            paintBanner(bg, t);
        }
    }

    private boolean isTarget(int sq) {
        for (Move m : targets) if (m.to() == sq) return true;
        return false;
    }

    private void paintBanner(Graphics2D g, long t) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double e = (t - bannerStart);
        double slide = Math.min(1, e / 220.0);
        double alpha = e > 1100 ? Math.max(0, 1 - (e - 1100) / 400.0) : 1;
        g.setFont(style.font(Font.BOLD, 26));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(bannerText) + 56, h = 48;
        int x = MARGIN + (SQ * 8 - w) / 2;
        int y = (int) (-h + (h + 12) * easeOut(slide));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
        g.setColor(new Color(0, 0, 0, 70));
        g.fillRoundRect(x + 3, y + 5, w, h, h, h);
        g.setColor(bannerColor);
        g.fillRoundRect(x, y, w, h, h, h);
        g.setColor(style.BORDER);
        g.setStroke(new BasicStroke(3));
        g.drawRoundRect(x, y, w, h, h, h);
        g.setColor(style.current.buttonText());
        g.drawString(bannerText, x + 28, y + (h + fm.getAscent()) / 2 - 5);
        g.dispose();
    }

    private static void shadow(Graphics2D g, double cx, double cy, double strength) {
        g.setColor(new Color(0, 0, 0, (int) (35 + 45 * strength)));
        double w = SQ * (0.55 + 0.1 * strength), h = SQ * 0.16;
        g.fill(new java.awt.geom.Ellipse2D.Double(cx - w / 2, cy - h / 2, w, h));
    }

    private static Shape star(double size) {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double r = i % 2 == 0 ? size : size * 0.42, a = Math.PI / 5 * i - Math.PI / 2;
            if (i == 0) p.moveTo(Math.cos(a) * r, Math.sin(a) * r); else p.lineTo(Math.cos(a) * r, Math.sin(a) * r);
        }
        p.closePath();
        return p;
    }

    private void drawPieceScaled(Graphics2D g, int p, double cx, double cy, double scale, double rot, float alpha) {
        Image img = pieceImages.get(p);
        Graphics2D pg = (Graphics2D) g.create();
        if (alpha < 1f) pg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, alpha)));
        pg.translate(cx, cy);
        if (rot != 0) pg.rotate(rot);
        if (scale != 1) pg.scale(scale, scale);
        if (img != null) {
            int w = img.getWidth(null) / HI, h = img.getHeight(null) / HI;
            pg.drawImage(img, -w / 2, -h / 2, w, h, null);
        } else { // fallback if an image is missing
            pg.setColor(p > 0 ? Color.WHITE : Color.BLACK);
            pg.fillOval(-SQ / 3, -SQ / 3, SQ * 2 / 3, SQ * 2 / 3);
            pg.setColor(p > 0 ? Color.BLACK : Color.WHITE);
            pg.setFont(style.font(Font.BOLD, 28));
            String s = String.valueOf(Position.pieceLetter(p));
            pg.drawString(s, -pg.getFontMetrics().stringWidth(s) / 2, 10);
        }
        pg.dispose();
    }

    private void drawArrow(Graphics2D g, Move m, Color c, int width) {
        Point a = origin(m.from()), b = origin(m.to());
        double x1 = a.x + SQ / 2.0, y1 = a.y + SQ / 2.0, x2 = b.x + SQ / 2.0, y2 = b.y + SQ / 2.0;
        double ang = Math.atan2(y2 - y1, x2 - x1), head = width * 2.4;
        double ex = x2 - Math.cos(ang) * head * 0.9, ey = y2 - Math.sin(ang) * head * 0.9;
        g.setColor(c);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Double(x1, y1, ex, ey));
        Path2D tip = new Path2D.Double();
        tip.moveTo(x2, y2);
        tip.lineTo(x2 - head * Math.cos(ang - 0.45), y2 - head * Math.sin(ang - 0.45));
        tip.lineTo(x2 - head * Math.cos(ang + 0.45), y2 - head * Math.sin(ang + 0.45));
        tip.closePath();
        g.fill(tip);
    }
}
