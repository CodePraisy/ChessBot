package components;

import javax.swing.*;
import java.awt.*;

public class Board extends JPanel {
    private boolean showCoordinates = true;
    private final Color edgeColor = new Color(60, 60, 60);

    public Board() {
        this.setPreferredSize(new Dimension(750, 750));
        this.setMaximumSize(new Dimension(750, 750));
        this.setOpaque(false); // Make JPanel transparent

        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;

        for (int row = 0; row <= 8; row++) {
            for (int col = 0; col <= 8; col++) {
                gbc.gridx = col;
                gbc.gridy = row;

                if (row == 8 && col == 0) {
                    JPanel corner = new JPanel();
                    corner.setBackground(edgeColor);
                    gbc.weightx = 0.1;
                    gbc.weighty = 0.1;
                    add(corner, gbc);
                } else if (row == 8) {
                    JPanel cell = new JPanel(new BorderLayout());
                    cell.setBackground(edgeColor);
                    JLabel label = new JLabel(showCoordinates ? String.valueOf((char) ('A' + col - 1)) : "", SwingConstants.CENTER);
                    label.setFont(new Font("Comic Sans MS", Font.BOLD, 10));
                    label.setForeground(Color.WHITE);
                    cell.add(label, BorderLayout.CENTER);
                    gbc.weightx = 0.05;  // small horizontal weight for bottom labels
                    gbc.weighty = 0.025; // small vertical weight for bottom labels
                    add(cell, gbc);
                } else if (col == 0) {
                    JPanel cell = new JPanel(new BorderLayout());
                    cell.setBackground(edgeColor);
                    JLabel label = new JLabel(showCoordinates ? String.valueOf(8 - row) : "", SwingConstants.CENTER);
                    label.setFont(new Font("Comic Sans MS", Font.BOLD, 10));
                    label.setForeground(Color.WHITE);
                    cell.add(label, BorderLayout.CENTER);
                    gbc.weightx = 0.025; // small horizontal weight for left labels
                    gbc.weighty = 0.05;  // small vertical weight for left labels
                    add(cell, gbc);
                } else {
                    // Interior cell, bigger weights to fill most space
                    JPanel cell = new JPanel();
                    cell.setBackground(new Color((int)(Math.random() * 256), 189, (int)(Math.random() * 256))); // or your board color
                    
                    
                    gbc.weightx = 1.0;
                    gbc.weighty = 1.0;
                    add(cell, gbc);
                }
            }
        }
    }
}