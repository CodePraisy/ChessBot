package main;
import javax.swing.*;
import java.awt.*;

import handlers.audioHandler;
import handlers.configHandler;

public class gameMenu extends JPanel {
    public gameMenu(CardLayout cardLayout, JPanel cardPanel, boolean aiMode) {
        this.setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(0, 100, 0, 100));
        centerPanel.setOpaque(false);

        JButton backButton = new JButton("Back to Main Menu");
        backButton.setMaximumSize(new Dimension(200, 40)); // Enforce size in BoxLayout
        backButton.setBackground(new Color(122, 64, 64));
        backButton.setBorder(BorderFactory.createLineBorder(new Color(64, 64, 64), 3));
        backButton.setFont(new Font("Comic Sans MS", Font.ITALIC, 20));
        backButton.setFocusPainted(false);
        backButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        backButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        backButton.addActionListener(e -> {
            if (configHandler.getConfigBoolean("soundEffects")) {
                audioHandler.playClickSound();
            }
            
            cardLayout.show(cardPanel, "mainMenu");
        });
        
        centerPanel.add(Box.createVerticalStrut(15)); // Top padding
        centerPanel.add(backButton);        
        
        JPanel optionsPanel = new JPanel();
        optionsPanel.setOpaque(false);
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.X_AXIS));

        if (aiMode) {
            this.setBackground(new Color(64, 88, 115));

            JPanel playerVsAIPanel = new JPanel();
            playerVsAIPanel.setLayout(new BoxLayout(playerVsAIPanel, BoxLayout.Y_AXIS));
            playerVsAIPanel.setBackground(new Color(180, 180, 180));
            playerVsAIPanel.setOpaque(true);                // This makes the background paint
            playerVsAIPanel.setPreferredSize(new Dimension(400, 800));
            playerVsAIPanel.setMaximumSize(new Dimension(400, 800));
            playerVsAIPanel.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 4));

            JLabel playerVsAILabel = new JLabel("Player vs AI", SwingConstants.CENTER);
            playerVsAILabel.setFont(new Font("Comic Sans MS", Font.BOLD, 50));
            playerVsAILabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            playerVsAILabel.setIcon(new ImageIcon("src/assets/desktop_logo2.png"));
            playerVsAILabel.setVerticalTextPosition(JLabel.BOTTOM);
            playerVsAILabel.setHorizontalTextPosition(JLabel.CENTER);
            playerVsAILabel.setMaximumSize(new Dimension(400, 100)); // Limit label height

            JTextPane playerVsAIDesc = new JTextPane();
            playerVsAIDesc.setContentType("text/html");
            playerVsAIDesc.setText(
                "<html><div style='text-align: center; font-family: Comic Sans MS; color: rgb(56, 53, 44);'>"
                + "<div style='font-size: 18px; font-weight: bold;'>Challenge the Computer</div>"
                + "<div style='font-size: 14px; margin-top: 4px;'>"
                + "Play against an AI opponent.<br>"
                + "Great for practice, learning new strategies, or testing your skills against different difficulty levels."
                + "</div>"
                + "</div></html>"
            );
            playerVsAIDesc.setEditable(false);
            playerVsAIDesc.setOpaque(false);
            playerVsAIDesc.setFocusable(false);
            playerVsAIDesc.setAlignmentX(Component.CENTER_ALIGNMENT);
            playerVsAIDesc.setMaximumSize(new Dimension(300, 150)); // Limit vertical growth

            JButton playerVsAIButton = new JButton("Start");
            playerVsAIButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            playerVsAIButton.setPreferredSize(new Dimension(50, 50));
            playerVsAIButton.setMaximumSize(new Dimension(50, 50)); // Enforce size in BoxLayout
            playerVsAIButton.setBackground(new Color(140, 140, 140));
            playerVsAIButton.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));
            playerVsAIButton.setFont(new Font("Comic Sans MS", Font.ITALIC, 30));
            playerVsAIButton.setFocusPainted(false);
            playerVsAIButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            playerVsAIButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
            playerVsAIButton.setMaximumSize(new Dimension(250, 50)); // Make button wider and height fixed

            playerVsAIButton.addActionListener(e -> {
                if (configHandler.getConfigBoolean("soundEffects")) {
                    audioHandler.playClickSound();
                }
            });            

            playerVsAIPanel.add(Box.createVerticalStrut(80)); // spacing
            playerVsAIPanel.add(playerVsAILabel);
            playerVsAIPanel.add(Box.createVerticalStrut(30)); // spacing
            playerVsAIPanel.add(playerVsAIDesc);
            playerVsAIPanel.add(Box.createVerticalStrut(50)); // spacing
            playerVsAIPanel.add(playerVsAIButton);
            playerVsAIPanel.add(Box.createVerticalGlue()); // Push content upwards

            JPanel AIvsAIPanel = new JPanel();
            AIvsAIPanel.setLayout(new BoxLayout(AIvsAIPanel, BoxLayout.Y_AXIS));
            AIvsAIPanel.setBackground(new Color(182, 223, 240));
            AIvsAIPanel.setOpaque(true);
            AIvsAIPanel.setPreferredSize(new Dimension(400, 800));
            AIvsAIPanel.setMaximumSize(new Dimension(400, 800));
            AIvsAIPanel.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 4));

            JLabel AIvsAILabel = new JLabel("AI vs AI", SwingConstants.CENTER);
            AIvsAILabel.setFont(new Font("Comic Sans MS", Font.BOLD, 50));
            AIvsAILabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            AIvsAILabel.setIcon(new ImageIcon("src/assets/sever_logo.png"));
            AIvsAILabel.setVerticalTextPosition(JLabel.BOTTOM);
            AIvsAILabel.setHorizontalTextPosition(JLabel.CENTER);
            AIvsAILabel.setMaximumSize(new Dimension(400, 100)); // Limit label height

            JTextPane AIvsAIDesc = new JTextPane();
            AIvsAIDesc.setContentType("text/html");
            AIvsAIDesc.setText(
                "<html><div style='text-align: center; font-family: Comic Sans MS; color: rgb(56, 53, 44);'>"
                + "<div style='font-size: 18px; font-weight: bold;'>Watch the Bots Battle</div>"
                + "<div style='font-size: 14px; margin-top: 4px;'>"
                + "Let two AIs face off and observe their strategies in action.<br>"
                + "Great for learning, testing engine strength, or just enjoying an automated match."
                + "</div>"
                + "</div></html>"
            );
            AIvsAIDesc.setEditable(false);
            AIvsAIDesc.setOpaque(false);
            AIvsAIDesc.setFocusable(false);
            AIvsAIDesc.setAlignmentX(Component.CENTER_ALIGNMENT);
            AIvsAIDesc.setMaximumSize(new Dimension(300, 200)); // Limit vertical growth

            JButton AIvsAIButton = new JButton("Start");
            AIvsAIButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            AIvsAIButton.setPreferredSize(new Dimension(50, 50));
            AIvsAIButton.setMaximumSize(new Dimension(50, 50)); // Enforce size in BoxLayout
            AIvsAIButton.setBackground(new Color(125, 209, 245));
            AIvsAIButton.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));
            AIvsAIButton.setFont(new Font("Comic Sans MS", Font.ITALIC, 30));
            AIvsAIButton.setFocusPainted(false);
            AIvsAIButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            AIvsAIButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
            AIvsAIButton.setMaximumSize(new Dimension(250, 50)); // Make button wider and height fixed

            AIvsAIButton.addActionListener(e -> {
                if (configHandler.getConfigBoolean("soundEffects")) {
                    audioHandler.playClickSound();
                }

            });            

            AIvsAIPanel.add(Box.createVerticalStrut(80)); // spacing
            AIvsAIPanel.add(AIvsAILabel);
            AIvsAIPanel.add(Box.createVerticalStrut(30)); // spacing
            AIvsAIPanel.add(AIvsAIDesc);
            AIvsAIPanel.add(Box.createVerticalStrut(28)); // spacing
            AIvsAIPanel.add(AIvsAIButton);
            AIvsAIPanel.add(Box.createVerticalGlue()); // Push content upwards

            optionsPanel.add(playerVsAIPanel);
            optionsPanel.add(Box.createHorizontalStrut(120)); // Space between buttons
            optionsPanel.add(AIvsAIPanel);
        } else {
            this.setBackground(new Color(88, 102, 91));

            JPanel standardGameButtonPanel = new JPanel();
            standardGameButtonPanel.setLayout(new BoxLayout(standardGameButtonPanel, BoxLayout.Y_AXIS));
            standardGameButtonPanel.setBackground(new Color(176, 209, 183));
            standardGameButtonPanel.setOpaque(true);                // This makes the background paint
            standardGameButtonPanel.setPreferredSize(new Dimension(400, 800));
            standardGameButtonPanel.setMaximumSize(new Dimension(400, 800));
            standardGameButtonPanel.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 4));

            JLabel standardGameLabel = new JLabel("On-Device", SwingConstants.CENTER);
            standardGameLabel.setFont(new Font("Comic Sans MS", Font.BOLD, 50));
            standardGameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            standardGameLabel.setIcon(new ImageIcon("src/assets/desktop_logo.png"));
            standardGameLabel.setVerticalTextPosition(JLabel.BOTTOM);
            standardGameLabel.setHorizontalTextPosition(JLabel.CENTER);
            standardGameLabel.setMaximumSize(new Dimension(400, 100)); // Limit label height

            JTextPane standardGameDesc = new JTextPane();
            standardGameDesc.setContentType("text/html");
            standardGameDesc.setText(
                "<html><div style='text-align: center; font-family: Comic Sans MS; color: rgb(56, 53, 44)'>"
                + "<div style='font-size: 18px; font-weight: bold;'>Pass & Play</div>"
                + "<div style='font-size: 14px; margin-top: 4px;'>"
                + "Play chess locally with a friend using the same device. "
                + "The board automatically rotates after each move, giving each player "
                + "their own perspective during their turn."
                + "</div>"
                + "</div></html>"
            );
            standardGameDesc.setEditable(false);
            standardGameDesc.setOpaque(false);
            standardGameDesc.setFocusable(false);
            standardGameDesc.setAlignmentX(Component.CENTER_ALIGNMENT);
            standardGameDesc.setMaximumSize(new Dimension(300, 150)); // Limit vertical growth
          
            JButton standardGameButton = new JButton("Start");
            standardGameButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            standardGameButton.setPreferredSize(new Dimension(50, 50));
            standardGameButton.setMaximumSize(new Dimension(50, 50)); // Enforce size in BoxLayout
            standardGameButton.setBackground(new Color(151, 209, 163));
            standardGameButton.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));
            standardGameButton.setFont(new Font("Comic Sans MS", Font.ITALIC, 30));
            standardGameButton.setFocusPainted(false);
            standardGameButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            standardGameButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
            standardGameButton.setMaximumSize(new Dimension(250, 50)); // Make button wider and height fixed

            standardGameButton.addActionListener(e -> {
                if (configHandler.getConfigBoolean("soundEffects")) {
                    audioHandler.playClickSound();
                }
                
                cardLayout.show(cardPanel, "standardGame");
            });            

            standardGameButtonPanel.add(Box.createVerticalStrut(80)); // spacing
            standardGameButtonPanel.add(standardGameLabel);
            standardGameButtonPanel.add(Box.createVerticalStrut(30)); // spacing
            standardGameButtonPanel.add(standardGameDesc);
            standardGameButtonPanel.add(Box.createVerticalStrut(50)); // spacing
            standardGameButtonPanel.add(standardGameButton);
            standardGameButtonPanel.add(Box.createVerticalGlue()); // Push content upwards

            JPanel multiplayerGameButtonPanel = new JPanel();
            multiplayerGameButtonPanel.setLayout(new BoxLayout(multiplayerGameButtonPanel, BoxLayout.Y_AXIS));
            multiplayerGameButtonPanel.setBackground(new Color(182, 223, 240));
            multiplayerGameButtonPanel.setOpaque(true);
            multiplayerGameButtonPanel.setPreferredSize(new Dimension(400, 800));
            multiplayerGameButtonPanel.setMaximumSize(new Dimension(400, 800));
            multiplayerGameButtonPanel.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 4));

            JLabel multiplayerGameLabel = new JLabel("Multiplayer", SwingConstants.CENTER);
            multiplayerGameLabel.setFont(new Font("Comic Sans MS", Font.BOLD, 50));
            multiplayerGameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            multiplayerGameLabel.setIcon(new ImageIcon("src/assets/internet_logo.png"));
            multiplayerGameLabel.setVerticalTextPosition(JLabel.BOTTOM);
            multiplayerGameLabel.setHorizontalTextPosition(JLabel.CENTER);
            multiplayerGameLabel.setMaximumSize(new Dimension(400, 100)); // Limit label height

            JTextPane multiplayerGameDesc = new JTextPane();
            multiplayerGameDesc.setContentType("text/html");
            multiplayerGameDesc.setText(
                "<html><div style='text-align: center; font-family: Comic Sans MS; color: rgb(56, 53, 44);'>"
                + "<div style='font-size: 18px; font-weight: bold;'>Play Across Devices</div>"
                + "<div style='font-size: 14px; margin-top: 4px;'>"
                + "Play with friends or opponents over LAN or online.<br>"
                + "Challenge players nearby or across the world. Easily host or join local games, "
                + "or connect online for remote matches."
                + "</div>"
                + "</div></html>"
            );
            multiplayerGameDesc.setEditable(false);
            multiplayerGameDesc.setOpaque(false);
            multiplayerGameDesc.setFocusable(false);
            multiplayerGameDesc.setAlignmentX(Component.CENTER_ALIGNMENT);
            multiplayerGameDesc.setMaximumSize(new Dimension(300, 200)); // Limit vertical growth

            JButton multiplayerGameButton = new JButton("Start");
            multiplayerGameButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            multiplayerGameButton.setPreferredSize(new Dimension(50, 50));
            multiplayerGameButton.setMaximumSize(new Dimension(50, 50)); // Enforce size in BoxLayout
            multiplayerGameButton.setBackground(new Color(125, 209, 245));
            multiplayerGameButton.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));
            multiplayerGameButton.setFont(new Font("Comic Sans MS", Font.ITALIC, 30));
            multiplayerGameButton.setFocusPainted(false);
            multiplayerGameButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            multiplayerGameButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
            multiplayerGameButton.setMaximumSize(new Dimension(250, 50)); // Make button wider and height fixed

            multiplayerGameButton.addActionListener(e -> {
                if (configHandler.getConfigBoolean("soundEffects")) {
                    audioHandler.playClickSound();
                }

            });            

            multiplayerGameButtonPanel.add(Box.createVerticalStrut(80)); // spacing
            multiplayerGameButtonPanel.add(multiplayerGameLabel);
            multiplayerGameButtonPanel.add(Box.createVerticalStrut(30)); // spacing
            multiplayerGameButtonPanel.add(multiplayerGameDesc);
            multiplayerGameButtonPanel.add(Box.createVerticalStrut(28)); // spacing
            multiplayerGameButtonPanel.add(multiplayerGameButton);
            multiplayerGameButtonPanel.add(Box.createVerticalGlue()); // Push content upwards

            optionsPanel.add(standardGameButtonPanel);
            optionsPanel.add(Box.createHorizontalStrut(120)); // Space between buttons
            optionsPanel.add(multiplayerGameButtonPanel);
        }

        centerPanel.add(optionsPanel);
    
        this.add(centerPanel);
    }
}
