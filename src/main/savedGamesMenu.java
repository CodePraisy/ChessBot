package main;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ai.engineRegistry;
import components.style;
import rules.Position;
import rules.gameState;
import rules.notation;
import saves.saveManager;
import saves.savedGame;

/** Library of saved games: Resume / Rejoin, Review, Export PGN, Import PGN, Delete. */
public class savedGamesMenu extends JPanel {
    private static final String[] COLUMNS = {"Date", "Mode", "Opponent", "Result", "Moves"};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final mainWindow window;
    private final List<savedGame> games = new ArrayList<>();
    private final JTable table;
    private final Model model = new Model();
    private final JButton resume, review, export, delete;
    private final JLabel empty = style.label("No saved games yet. Finished games and games you save appear here.", Font.ITALIC, 20);

    public savedGamesMenu(mainWindow window) {
        this.window = window;
        setBackground(style.BACKGROUND);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(30, 80, 30, 80));

        JLabel title = new JLabel("Saved Games", SwingConstants.CENTER);
        title.setFont(style.titleFont(52));
        title.setForeground(style.TEXT);
        add(title, BorderLayout.NORTH);

        table = new JTable(model);
        table.setFont(style.font(Font.PLAIN, 18));
        table.setRowHeight(34);
        table.getTableHeader().setFont(style.font(Font.BOLD, 19));
        table.getTableHeader().setBackground(style.SIDE_PANEL);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(style.YELLOW);
        table.setSelectionForeground(style.current.buttonText());
        table.setBackground(style.current.list());
        table.setForeground(style.TEXT);
        table.getTableHeader().setForeground(style.TEXT);
        table.setGridColor(components.fancyButton.mix(style.current.list(), style.BORDER, 0.3f));
        DefaultTableCellRenderer r = new DefaultTableCellRenderer();
        r.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        table.setDefaultRenderer(Object.class, r);
        table.getColumnModel().getColumn(0).setPreferredWidth(170);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(280);
        table.getColumnModel().getColumn(3).setPreferredWidth(330);
        table.getColumnModel().getColumn(4).setPreferredWidth(70);
        table.getSelectionModel().addListSelectionListener(_ -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && selected() != null) review(); }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(style.BORDER, 3));
        scroll.getViewport().setBackground(style.current.list());

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        center.setBorder(BorderFactory.createEmptyBorder(25, 0, 20, 0));
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        center.add(empty, BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(1, 0, 14, 0));
        buttons.setOpaque(false);
        resume = style.button("Resume", style.GREEN, 22, this::resume);
        review = style.button("Review", style.BLUE, 22, this::review);
        export = style.button("Export PGN", style.PURPLE, 22, this::exportPgn);
        delete = style.button("Delete", style.RED, 22, this::delete);
        buttons.add(resume);
        buttons.add(review);
        buttons.add(export);
        buttons.add(style.button("Import PGN", style.YELLOW, 22, this::importPgn));
        buttons.add(delete);
        buttons.add(style.button("Back to Menu", style.GRAY, 22, window::showMenu));
        buttons.setPreferredSize(new Dimension(100, 54));
        add(buttons, BorderLayout.SOUTH);
        updateButtons();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        style.paintBackdrop((Graphics2D) g0, getWidth(), getHeight());
    }

    public void reload() {
        games.clear();
        games.addAll(saveManager.list());
        model.fireTableDataChanged();
        empty.setVisible(games.isEmpty());
        updateButtons();
        SwingUtilities.invokeLater(window::reportSaveProblems);
    }

    private savedGame selected() {
        int r = table.getSelectedRow();
        return r >= 0 && r < games.size() ? games.get(r) : null;
    }

    private void updateButtons() {
        savedGame s = selected();
        resume.setEnabled(s != null && !s.isFinished());
        resume.setText(s != null && savedGame.MULTIPLAYER.equals(s.mode) ? "Rejoin" : "Resume");
        review.setEnabled(s != null);
        export.setEnabled(s != null);
        delete.setEnabled(s != null);
    }

    // ---------- actions ----------

    private void resume() {
        savedGame s = selected();
        if (s == null || s.isFinished()) return;
        if (savedGame.MULTIPLAYER.equals(s.mode)) { multiplayerDialog.rejoin(window, s); return; }
        if (savedGame.AI.equals(s.mode) && !engineRegistry.hasMoveProvider()) { mainWindow.showNoEngineDialog(this); return; }
        window.startGame(gameSetup.resume(s));
    }

    private void review() {
        savedGame s = selected();
        if (s != null) window.openReview(s, "savedGames");
    }

    private void exportPgn() {
        savedGame s = selected();
        if (s == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("chessbot-" + s.gameId.substring(0, 8) + ".pgn"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            String date = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.systemDefault()).format(Instant.parse(s.created));
            String pgn = notation.toPgn(s.toGameState(), Map.of("White", s.whiteName, "Black", s.blackName, "Date", date, "Event", modeName(s)));
            Files.writeString(fc.getSelectedFile().toPath(), pgn, StandardCharsets.UTF_8);
            style.info(this, "Exported", "Saved " + fc.getSelectedFile().getName());
        } catch (Exception e) {
            style.info(this, "Export failed", "Could not export: " + e.getMessage());
        }
    }

    private void importPgn() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            notation.pgnGame pg = notation.parsePgn(Files.readString(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8));
            gameState g = pg.game();
            String res = pg.headers().getOrDefault("Result", "*");
            if (!g.isOver()) {
                if (res.equals("1-0")) g.forceResult(gameState.Status.RESIGNATION, Position.WHITE);
                else if (res.equals("0-1")) g.forceResult(gameState.Status.RESIGNATION, Position.BLACK);
                else if (res.equals("1/2-1/2")) g.forceResult(gameState.Status.DRAW_AGREED, 0);
            }
            savedGame s = savedGame.create(savedGame.LOCAL, g.startFen());
            s.whiteName = pg.headers().getOrDefault("White", "White");
            s.blackName = pg.headers().getOrDefault("Black", "Black");
            s.settings.put("importedFrom", fc.getSelectedFile().getName());
            s.syncFrom(g);
            saveManager.save(s);
            reload();
        } catch (Exception e) {
            style.info(this, "Import failed", "Could not import that PGN: " + e.getMessage());
        }
    }

    private void delete() {
        savedGame s = selected();
        if (s == null) return;
        if (style.choose(this, "Delete game", "Delete this saved game permanently?\n" + modeName(s) + " - " + opponent(s) + " - " + (s.plyCount() + 1) / 2 + " moves",
                "Delete", "Cancel") != 0) return;
        saveManager.delete(s.gameId);
        reload();
    }

    // ---------- table ----------

    static String modeName(savedGame s) {
        return switch (s.mode) {
            case savedGame.AI -> s.settingStr("whiteEngine", null) != null ? "AI vs AI" : "Player vs AI";
            case savedGame.MULTIPLAYER -> "Multiplayer";
            default -> s.settings.containsKey("importedFrom") ? "Imported" : "Pass & Play";
        };
    }

    static String opponent(savedGame s) {
        if (savedGame.MULTIPLAYER.equals(s.mode)) return "white".equals(s.mpStr("localColor", "white")) ? s.blackName : s.whiteName;
        if (savedGame.AI.equals(s.mode)) {
            if (s.settingStr("whiteEngine", null) != null) return s.whiteName + " vs " + s.blackName;
            return "white".equals(s.settingStr("humanColor", "white")) ? s.blackName : s.whiteName;
        }
        return s.whiteName + " vs " + s.blackName;
    }

    private static String resultText(savedGame s) {
        if (!s.isFinished()) return "In progress";
        return s.result + "  (" + s.terminationText() + ")";
    }

    private class Model extends AbstractTableModel {
        @Override public int getRowCount() { return games.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int c) { return COLUMNS[c]; }
        @Override public Object getValueAt(int r, int c) {
            savedGame s = games.get(r);
            return switch (c) {
                case 0 -> { try { yield DATE.format(Instant.parse(s.updated)); } catch (Exception e) { yield s.updated; } }
                case 1 -> modeName(s);
                case 2 -> opponent(s);
                case 3 -> resultText(s);
                default -> (s.plyCount() + 1) / 2;
            };
        }
    }
}
