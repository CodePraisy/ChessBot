package components;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/** Scrollable "1. e4 e5" move list. Clicking a move reports its ply (1 = first move). */
public class moveListPanel extends JScrollPane {
    private final List<String> sans = new ArrayList<>();
    private final Map<Integer, String> marks = new HashMap<>();
    private final Map<Integer, Color> markColors = new HashMap<>();
    private boolean blackFirst;
    private int firstMoveNumber = 1;
    private int current = -1;
    private IntConsumer onSelect = _ -> {};
    private final JTable table;
    private final Model model = new Model();

    public moveListPanel(int width, int height) {
        table = new JTable(model);
        table.setFont(style.font(Font.PLAIN, 17));
        table.setRowHeight(26);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setTableHeader(null);
        table.setFillsViewportHeight(true);
        table.setBackground(style.current.list());
        getViewport().setBackground(style.current.list());
        table.setRowSelectionAllowed(false);
        table.setCursor(new Cursor(Cursor.HAND_CURSOR));
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint()), c = table.columnAtPoint(e.getPoint());
                int ply = plyAt(r, c);
                if (ply >= 1 && ply <= sans.size()) onSelect.accept(ply);
            }
        });
        setViewportView(table);
        setPreferredSize(new Dimension(width, height));
        setMaximumSize(new Dimension(width, height));
        setBorder(BorderFactory.createLineBorder(fancyButton.mix(style.BORDER, style.SIDE_PANEL, 0.4f), 2, true));
    }

    public void setMoves(List<String> list, boolean blackFirst, int firstMoveNumber) {
        sans.clear();
        sans.addAll(list);
        this.blackFirst = blackFirst;
        this.firstMoveNumber = firstMoveNumber;
        model.fireTableDataChanged();
        scrollToPly(current >= 0 ? current : sans.size());
    }

    /** Ply to highlight (0 = start position, nothing highlighted). */
    public void setCurrentPly(int ply) {
        current = ply;
        table.repaint();
        scrollToPly(ply);
    }

    /** Review annotations: symbol shown after the move (e.g. "?!") and its colour. */
    public void setMark(int ply, String symbol, Color color) {
        if (symbol == null) { marks.remove(ply); markColors.remove(ply); }
        else { marks.put(ply, symbol); markColors.put(ply, color); }
        table.repaint();
    }

    public void clearMarks() { marks.clear(); markColors.clear(); table.repaint(); }

    public void setOnSelect(IntConsumer c) { onSelect = c; }

    private int plyAt(int row, int col) {
        if (row < 0 || col < 1) return -1;
        return row * 2 + col - (blackFirst ? 1 : 0);
    }

    private void scrollToPly(int ply) {
        if (ply < 1) return;
        int row = (ply - 1 + (blackFirst ? 1 : 0)) / 2;
        SwingUtilities.invokeLater(() -> table.scrollRectToVisible(table.getCellRect(row, 0, true)));
    }

    private class Model extends AbstractTableModel {
        @Override public int getRowCount() { return (sans.size() + (blackFirst ? 1 : 0) + 1) / 2; }
        @Override public int getColumnCount() { return 3; }
        @Override public Object getValueAt(int r, int c) {
            if (c == 0) return (firstMoveNumber + r) + ".";
            int ply = plyAt(r, c);
            if (ply < 1 || ply > sans.size()) return c == 1 && blackFirst && r == 0 ? "..." : "";
            return sans.get(ply - 1) + marks.getOrDefault(ply, "");
        }
    }

    private class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, false, false, r, c);
            int ply = plyAt(r, c);
            l.setFont(style.font(c == 0 ? Font.ITALIC : Font.PLAIN, 17));
            l.setForeground(ply == current && ply >= 1 ? style.current.buttonText() : markColors.getOrDefault(ply, c == 0 ? style.MUTED : style.TEXT));
            l.setBackground(ply == current && ply >= 1 ? style.YELLOW : (r % 2 == 0 ? style.current.list() : style.current.listAlt()));
            l.setHorizontalAlignment(c == 0 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            l.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            return l;
        }
    }
}
