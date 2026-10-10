package components;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import handlers.audioHandler;
import handlers.configHandler;

/**
 * The look of the whole app: the active colour theme, the font, and helpers for buttons, labels and dialogs.
 * Themes and fonts are chosen in Settings (config "visual.theme" / "visual.font") and applied by {@link #init()}.
 */
public final class style {
    private style() {}

    /** One colour theme. Accent colours are used for buttons; text colours are picked to stay readable on them. */
    public record theme(String name, boolean dark, Color bg, Color panel, Color card, Color border, Color text, Color muted,
                        Color green, Color blue, Color lightBlue, Color gray, Color red, Color purple, Color yellow,
                        Color frameTop, Color frameBottom, Color list, Color listAlt, Color buttonText) {}

    public static final Map<String, theme> THEMES = new LinkedHashMap<>();
    static {
        THEMES.put("Light", new theme("Light", false,
                new Color(236, 230, 214), new Color(222, 207, 184), new Color(244, 237, 222), new Color(89, 83, 67), new Color(52, 48, 40), new Color(120, 110, 95),
                new Color(168, 230, 182), new Color(146, 198, 228), new Color(186, 222, 240), new Color(208, 204, 196), new Color(250, 128, 120), new Color(200, 160, 250), new Color(246, 222, 140),
                new Color(74, 47, 40), new Color(46, 29, 25), new Color(246, 240, 228), new Color(236, 228, 212), new Color(52, 48, 40)));
        THEMES.put("Dark", new theme("Dark", true,
                new Color(24, 26, 31), new Color(37, 40, 47), new Color(48, 52, 61), new Color(78, 84, 98), new Color(232, 234, 240), new Color(150, 157, 170),
                new Color(56, 158, 102), new Color(60, 128, 200), new Color(52, 96, 140), new Color(76, 82, 96), new Color(196, 74, 74), new Color(128, 92, 205), new Color(190, 148, 50),
                new Color(44, 40, 40), new Color(22, 20, 20), new Color(33, 36, 42), new Color(40, 43, 51), new Color(245, 246, 250)));
        THEMES.put("Midnight", new theme("Midnight", true,
                new Color(15, 19, 38), new Color(27, 33, 60), new Color(38, 46, 82), new Color(72, 84, 140), new Color(226, 230, 255), new Color(146, 154, 200),
                new Color(36, 160, 140), new Color(70, 110, 230), new Color(52, 76, 150), new Color(64, 72, 112), new Color(214, 72, 110), new Color(140, 90, 230), new Color(214, 160, 60),
                new Color(32, 30, 58), new Color(14, 12, 30), new Color(22, 27, 52), new Color(28, 34, 62), new Color(244, 246, 255)));
        THEMES.put("Forest", new theme("Forest", false,
                new Color(222, 232, 214), new Color(196, 214, 184), new Color(236, 243, 230), new Color(58, 84, 56), new Color(32, 46, 30), new Color(90, 110, 86),
                new Color(150, 214, 150), new Color(140, 196, 210), new Color(186, 222, 214), new Color(196, 206, 190), new Color(236, 136, 112), new Color(190, 170, 230), new Color(232, 214, 130),
                new Color(56, 66, 40), new Color(32, 38, 22), new Color(240, 246, 236), new Color(228, 238, 222), new Color(32, 46, 30)));
        THEMES.put("Rose", new theme("Rose", false,
                new Color(246, 230, 232), new Color(236, 206, 212), new Color(250, 240, 242), new Color(122, 74, 86), new Color(70, 40, 50), new Color(140, 100, 110),
                new Color(176, 226, 196), new Color(176, 196, 240), new Color(214, 222, 246), new Color(222, 208, 212), new Color(240, 120, 140), new Color(214, 166, 236), new Color(246, 210, 160),
                new Color(96, 50, 62), new Color(60, 28, 38), new Color(252, 244, 246), new Color(244, 232, 236), new Color(70, 40, 50)));
    }

    /** Bundled display fonts plus reliable system and Java logical fonts for more visual variety. */
    public static final String[] FONTS = {"Nunito", "Poppins", "Fredoka", "Segoe UI", "Trebuchet MS", "Georgia", "Verdana", "Tahoma", "Comic Sans MS", "SansSerif", "Serif", "Monospaced"};

    public static theme current = THEMES.get("Light");
    public static String FONT = "Nunito";

    // Colours used everywhere; refreshed from the theme by init().
    public static Color BACKGROUND, SIDE_PANEL, CARD, BORDER, TEXT, MUTED, GREEN, BLUE, LIGHT_BLUE, GRAY, RED, PURPLE, YELLOW;

    private static boolean fontsLoaded;

    /** Loads fonts once and applies the theme + font from config. Call before building any screen. */
    public static void init() {
        if (!fontsLoaded) {
            fontsLoaded = true;
            File[] files = new File("src/assets/fonts").listFiles((_, n) -> n.endsWith(".ttf"));
            if (files != null) for (File f : files) {
                try { GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(Font.createFont(Font.TRUETYPE_FONT, f)); }
                catch (Exception e) { System.err.println("Font " + f.getName() + ": " + e.getMessage()); }
            }
        }
        current = THEMES.getOrDefault(configHandler.getConfigString("visual.theme", "Light"), THEMES.get("Light"));
        FONT = configHandler.getConfigString("visual.font", "Nunito");
        theme t = current;
        BACKGROUND = t.bg(); SIDE_PANEL = t.panel(); CARD = t.card(); BORDER = t.border(); TEXT = t.text(); MUTED = t.muted();
        GREEN = soft(t.green()); BLUE = soft(t.blue()); LIGHT_BLUE = soft(t.lightBlue()); GRAY = t.gray();
        RED = soft(t.red()); PURPLE = soft(t.purple()); YELLOW = soft(t.yellow());
        applyUIDefaults();
    }

    static { init(); }

    /** Accent colours are muted a little (lower saturation) so buttons look calmer. */
    private static final float SATURATION = 0.72f;

    private static Color soft(Color c) {
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        return Color.getHSBColor(hsb[0], hsb[1] * SATURATION, hsb[2]);
    }

    public static boolean isDark() { return current.dark(); }

    /** House font. Bundled fonts have no italic face, so italic requests are drawn upright for a cleaner look. */
    public static Font font(int style, int size) {
        int s = isBundledFont(FONT) ? style & ~Font.ITALIC : style;
        return new Font(FONT, s, size);
    }

    private static boolean isBundledFont(String name) { return name.equals("Nunito") || name.equals("Poppins") || name.equals("Fredoka"); }

    /** Heavier face for big titles where the font has one. */
    public static Font titleFont(int size) {
        if (FONT.equals("Nunito")) return new Font("Nunito ExtraBold", Font.PLAIN, size);
        return new Font(FONT, Font.BOLD, size);
    }

    /** Swing defaults so dialogs, fields, lists and tool tips follow the theme. */
    private static void applyUIDefaults() {
        Color bg = current.card(), fg = TEXT;
        Map<String, Color> c = new LinkedHashMap<>();
        for (String k : new String[]{"OptionPane.background", "Panel.background", "CheckBox.background", "RadioButton.background", "Slider.background",
                "Viewport.background", "ScrollPane.background", "Button.background"}) c.put(k, bg);
        for (String k : new String[]{"OptionPane.messageForeground", "Label.foreground", "CheckBox.foreground", "RadioButton.foreground", "Slider.foreground",
                "Button.foreground", "TitledBorder.titleColor"}) c.put(k, fg);
        c.put("TextField.background", current.list()); c.put("TextField.foreground", fg); c.put("TextField.caretForeground", fg);
        c.put("TextField.selectionBackground", BLUE); c.put("TextField.selectionForeground", current.buttonText());
        c.put("ComboBox.background", current.list()); c.put("ComboBox.foreground", fg);
        c.put("ComboBox.selectionBackground", BLUE); c.put("ComboBox.selectionForeground", current.buttonText());
        c.put("List.background", current.list()); c.put("List.foreground", fg);
        c.put("Table.background", current.list()); c.put("Table.foreground", fg); c.put("Table.gridColor", fancyButton.mix(current.list(), BORDER, 0.35f));
        c.put("TableHeader.background", SIDE_PANEL); c.put("TableHeader.foreground", fg);
        c.put("ToolTip.background", current.card()); c.put("ToolTip.foreground", fg);
        c.put("ProgressBar.background", current.list()); c.put("ProgressBar.foreground", GREEN);
        c.put("ProgressBar.selectionForeground", fg); c.put("ProgressBar.selectionBackground", fg);
        for (Map.Entry<String, Color> e : c.entrySet()) UIManager.put(e.getKey(), new ColorUIResource(e.getValue()));
        for (String k : new String[]{"OptionPane.messageFont", "OptionPane.buttonFont", "Label.font", "TextField.font", "ComboBox.font", "CheckBox.font",
                "RadioButton.font", "Table.font", "TableHeader.font", "ToolTip.font", "List.font", "ProgressBar.font", "Slider.font"})
            UIManager.put(k, new FontUIResource(font(Font.PLAIN, 16)));
    }

    public static void click() {
        if (configHandler.getConfigBoolean("soundEffects")) audioHandler.playClickSound();
    }

    /** A button in the house style; the click sound plays before the action runs. */
    public static JButton button(String text, Color bg, int fontSize, Runnable action) {
        JButton b = new fancyButton(text);
        b.setBackground(bg);
        b.setFont(font(Font.BOLD, fontSize));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.setAlignmentX(Component.CENTER_ALIGNMENT);
        if (action != null) b.addActionListener(_ -> { click(); action.run(); });
        return b;
    }

    public static JButton button(String text, Color bg, int fontSize, Dimension size, Runnable action) {
        JButton b = button(text, bg, fontSize, action);
        b.setPreferredSize(size);
        b.setMaximumSize(size);
        return b;
    }

    public static JLabel label(String text, int fontStyle, int size) {
        JLabel l = new JLabel(text);
        l.setFont(font(fontStyle, size));
        l.setForeground(TEXT);
        return l;
    }

    /** Hex for HTML snippets in labels. */
    public static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }

    /** A bordered box for grouping controls in side panels and dialogs. */
    public static JPanel box(Color bg) {
        JPanel p = new roundedPanel(bg, 16, false);
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 14));
        return p;
    }

    /**
     * Modal choice dialog with house-style buttons. Returns the chosen index, or -1 if closed.
     * message may be a String (HTML allowed) or any component.
     */
    public static int choose(Component parent, String title, Object message, String... options) {
        Color[] colors = {GREEN, BLUE, GRAY, PURPLE, YELLOW};
        Object[] buttons = new Object[options.length];
        for (int i = 0; i < options.length; i++) {
            String o = options[i];
            Color bg = o.matches("(?i)(resign|delete|leave)\\b.*") ? RED
                    : o.matches("(?i).*\\b(cancel|no|reject|decline|stay|later|not now)\\b.*") ? GRAY : colors[i % colors.length];
            JButton b = button(o, bg, 17, null);
            b.setPreferredSize(new Dimension(Math.max(110, b.getPreferredSize().width + 30), 44));
            b.addActionListener(_ -> {
                click();
                JOptionPane pane = (JOptionPane) SwingUtilities.getAncestorOfClass(JOptionPane.class, b);
                if (pane != null) pane.setValue(b);
            });
            buttons[i] = b;
        }
        Object body = message instanceof String s ? messageLabel(s) : message;
        if (body instanceof JComponent jc) themeTree(jc);
        int r = JOptionPane.showOptionDialog(parent, body, title, JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, buttons, buttons[0]);
        return r >= 0 && r < options.length ? r : -1;
    }

    public static void info(Component parent, String title, String message) { choose(parent, title, message, "OK"); }

    public static JLabel messageLabel(String text) {
        String html = text.startsWith("<html>") ? text : "<html><div style='width:380px'>" + text.replace("\n", "<br>") + "</div></html>";
        JLabel l = label(html, Font.PLAIN, 18);
        l.setBorder(BorderFactory.createEmptyBorder(6, 6, 12, 6));
        return l;
    }

    /** Makes plain panels/labels inside a dialog body follow the theme. */
    public static void themeTree(Component c) {
        if (c instanceof JLabel l) l.setForeground(TEXT);
        if (c instanceof JRadioButton || c instanceof JCheckBox) { c.setForeground(TEXT); ((JComponent) c).setOpaque(false); }
        if (c instanceof JPanel p && !(p instanceof roundedPanel)) p.setOpaque(false);
        if (c instanceof Container ct) for (Component ch : ct.getComponents()) themeTree(ch);
    }

    /** Screen backdrop: soft radial light on the theme colour with a faint chequer pattern. */
    public static void paintBackdrop(Graphics2D g0, int w, int h) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setPaint(new RadialGradientPaint(new Point(w / 3, h / 3), Math.max(w, h) * 0.9f, new float[]{0f, 1f},
                new Color[]{fancyButton.mix(BACKGROUND, isDark() ? new Color(90, 100, 130) : Color.WHITE, isDark() ? 0.12f : 0.3f),
                        fancyButton.mix(BACKGROUND, Color.BLACK, isDark() ? 0.25f : 0.07f)}));
        g.fillRect(0, 0, w, h);
        g.setColor(isDark() ? new Color(255, 255, 255, 6) : new Color(89, 83, 67, 10));
        int s = 60;
        for (int y = 0; y < h; y += s) for (int x = ((y / s) % 2) * s; x < w; x += 2 * s) g.fillRect(x, y, s, s);
        g.dispose();
    }

    /** Applies the house font to every component in a dialog/panel tree. */
    public static void deepFont(Component c, Font f) {
        c.setFont(f);
        if (c instanceof Container ct) for (Component ch : ct.getComponents()) deepFont(ch, f);
    }

    /** A panel that paints the themed backdrop behind its children. */
    public static JPanel backdropPanel(LayoutManager lm) {
        return new JPanel(lm) {
            @Override protected void paintComponent(Graphics g) { paintBackdrop((Graphics2D) g, getWidth(), getHeight()); }
        };
    }
}
