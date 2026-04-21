package main;
import components.Board;
import components.SidePanel;
import handlers.audioHandler;
import handlers.configHandler;
import java.awt.*;
import javax.swing.*;

public class game extends JPanel {
    public game(CardLayout cardLayout, JPanel cardPanel, int gameType) {
        this.setLayout(new BorderLayout());

        if (gameType == 0) {
            this.setBackground(new Color(227, 220, 193));

            // --- Title: full width, truly centered ---
            JLabel title = new JLabel("Game", SwingConstants.CENTER);
            title.setFont(new Font("Comic Sans MS", Font.BOLD, 60));
            title.setOpaque(false);
            title.setBorder(BorderFactory.createEmptyBorder(24, 0, 16, 0));
            this.add(title, BorderLayout.NORTH);

            // --- Center content ---
            JPanel centerPanel = new JPanel();
            centerPanel.setOpaque(false);
            centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.X_AXIS));

            // Board
            Board chessBoard = new Board();
            chessBoard.setPreferredSize(new Dimension(900, 900));
            chessBoard.setMinimumSize(new Dimension(900, 900));
            chessBoard.setMaximumSize(new Dimension(900, 900));
            chessBoard.setAlignmentY(Component.TOP_ALIGNMENT);

            // Side panel
            SidePanel sidePanel = new SidePanel();
            sidePanel.setAlignmentY(Component.TOP_ALIGNMENT);

            // Toggle button wrapped vertically so it centers alongside the board
            JButton toggleBtn = new JButton("◀");
            toggleBtn.setFont(new Font("Comic Sans MS", Font.BOLD, 8));
            toggleBtn.setBackground(new Color(89, 83, 67));
            toggleBtn.setForeground(Color.WHITE);
            toggleBtn.setFocusPainted(false);
            toggleBtn.setBorderPainted(false);
            toggleBtn.setOpaque(true);
            toggleBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            toggleBtn.setPreferredSize(new Dimension(40, 80));
            toggleBtn.setMaximumSize(new Dimension(40, 80));

            JPanel toggleWrapper = new JPanel();
            toggleWrapper.setOpaque(false);
            toggleWrapper.setLayout(new BoxLayout(toggleWrapper, BoxLayout.Y_AXIS));
            toggleWrapper.setAlignmentY(Component.TOP_ALIGNMENT);
            toggleWrapper.add(Box.createVerticalGlue());
            toggleWrapper.add(toggleBtn);
            toggleWrapper.add(Box.createVerticalGlue());

            toggleBtn.addActionListener(e -> {
                boolean show = !sidePanel.isVisible();
                
                if (configHandler.getConfigBoolean("soundEffects")) {
                    audioHandler.playClickSound();
                }
                
                sidePanel.setVisible(show);
                toggleBtn.setText(show ? "◀" : "▶");
                centerPanel.revalidate();
                centerPanel.repaint();
            });

            centerPanel.add(Box.createHorizontalGlue());
            centerPanel.add(chessBoard);
            centerPanel.add(Box.createHorizontalStrut(12));
            centerPanel.add(toggleWrapper);
            centerPanel.add(Box.createHorizontalStrut(12));
            centerPanel.add(sidePanel);
            centerPanel.add(Box.createHorizontalGlue());

            // Bottom padding
            JPanel bottomPad = new JPanel();
            bottomPad.setOpaque(false);
            bottomPad.setPreferredSize(new Dimension(0, 24));

            this.add(centerPanel, BorderLayout.CENTER);
            this.add(bottomPad, BorderLayout.SOUTH);

        } else {
            // AI game components
        }
    }
}