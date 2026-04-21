package main;

import javax.swing.*;
import java.awt.*;

import components.Board;

public class game extends JPanel {
    public game(CardLayout cardLayout, JPanel cardPanel, int gameType) {
        this.setLayout(new BorderLayout());

        if (gameType == 0) {
            this.setBackground(new Color(227, 220, 193));
            
            JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
            wrapper.setOpaque(false); // transparent background to show parent's color
            
            JPanel chessContainer = new JPanel();
            chessContainer.setLayout(new BoxLayout(chessContainer, BoxLayout.Y_AXIS));
            chessContainer.setMaximumSize(new Dimension(1000, 1000));
            chessContainer.setOpaque(false); // make transparent to show wrapper bg

            JLabel title = new JLabel("Game", SwingConstants.CENTER);
            title.setFont(new Font("Comic Sans MS", Font.BOLD, 60));
            title.setMaximumSize(new Dimension(Integer.MAX_VALUE, title.getPreferredSize().height));
            
            chessContainer.add(title);
            chessContainer.add(Box.createVerticalStrut(50));
            
            Board chessBoard = new Board();
            chessBoard.setMinimumSize(new Dimension(750, 750));
            chessBoard.setMaximumSize(new Dimension(750, 750));
       
            chessContainer.add(chessBoard);

            JPanel sidePanel = new JPanel();
            sidePanel.setLayout(new BoxLayout(sidePanel, BoxLayout.Y_AXIS));
            sidePanel.setBackground(new Color(214, 191, 163));
            sidePanel.setOpaque(true);                // This makes the background paint
            sidePanel.setPreferredSize(new Dimension(450, 950));
            sidePanel.setMaximumSize(new Dimension(450, 950));
            sidePanel.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));

            JPanel sideWrapper = new JPanel();
            sideWrapper.setLayout(new BoxLayout(sideWrapper, BoxLayout.Y_AXIS));
            sideWrapper.setOpaque(false);
            sideWrapper.add(Box.createVerticalGlue()); // push content to the top
            sideWrapper.add(Box.createVerticalStrut(5));
            sideWrapper.add(sidePanel);
            sideWrapper.add(Box.createVerticalGlue());

            wrapper.add(chessContainer);
            wrapper.add(Box.createHorizontalStrut(160)); // space between board and side panel
            wrapper.add(sideWrapper);

            this.add(wrapper, BorderLayout.CENTER);
        } else {
            // AI game components
        }
    }
}
