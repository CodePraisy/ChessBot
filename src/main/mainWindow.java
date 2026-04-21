package main;
import javax.swing.*;

import handlers.audioHandler;
import handlers.configHandler;

import java.awt.*;
import java.net.URI;

public class mainWindow extends JFrame {
    private CardLayout cardLayout;
    private JPanel cardPanel;
    
    public mainWindow(String tabTitle, String iconPath, boolean resizable) {
        super(tabTitle);
        
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setSize(1400, 1000);
        this.setLocationRelativeTo(null);
        this.setResizable(resizable);
        this.setIconImage(new ImageIcon(iconPath).getImage());

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        this.setContentPane(cardPanel);

        JPanel mainMenuPanel = new JPanel();
        mainMenuPanel.setBackground(new Color(227, 220, 193));
        mainMenuPanel.setLayout(new BoxLayout(mainMenuPanel, BoxLayout.Y_AXIS));
        
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setOpaque(false); // make transparent to show mainMenuPanel bg

        settingsPanel settings = new settingsPanel(cardLayout, cardPanel);
        gameMenu standardGameMenu = new gameMenu(cardLayout, cardPanel, false);
        gameMenu aiGameMenu = new gameMenu(cardLayout, cardPanel, true);
        game standardGame = new game(cardLayout, cardPanel, 0);

        cardPanel.add(mainMenuPanel, "mainMenu");
        cardPanel.add(settings, "settings");
        cardPanel.add(standardGameMenu, "standardGameMenu");
        cardPanel.add(aiGameMenu, "aiGameMenu");
        cardPanel.add(standardGame, "standardGame");

        JLabel title = new JLabel("Praise's Chess", SwingConstants.CENTER);
        title.setIcon(new ImageIcon("src/assets/frontPage.png"));
        title.setVerticalTextPosition(JLabel.BOTTOM);
        title.setHorizontalTextPosition(JLabel.CENTER);
        title.setFont(new Font("Comic Sans MS", Font.BOLD, 50));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton startButton = new JButton("Start Game");
        startButton.setPreferredSize(new Dimension(500, 50));
        startButton.setMaximumSize(new Dimension(750, 50)); // Enforce size in BoxLayout
        startButton.setBackground(new Color(175, 237, 189));
        startButton.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));
        startButton.setFont(new Font("Comic Sans MS", Font.ITALIC, 30));
        startButton.setFocusPainted(false);
        startButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        startButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        startButton.addActionListener(_ -> {
            if (configHandler.getConfigBoolean("soundEffects")) {
                audioHandler.playClickSound();
            }

            cardLayout.show(cardPanel, "standardGameMenu");
        });

        JButton aiModeButton = new JButton("AI Mode");
        aiModeButton.setPreferredSize(new Dimension(500, 50));
        aiModeButton.setMaximumSize(new Dimension(750, 50)); // Enforce size in BoxLayout
        aiModeButton.setBackground(new Color(150, 202, 224));
        aiModeButton.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));
        aiModeButton.setFont(new Font("Comic Sans MS", Font.ITALIC, 30));
        aiModeButton.setFocusPainted(false);
        aiModeButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        aiModeButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        aiModeButton.addActionListener(_ -> {
            if (configHandler.getConfigBoolean("soundEffects")) {
                audioHandler.playClickSound();
            }

            cardLayout.show(cardPanel, "aiGameMenu");
        });

        JButton settingsButton = new JButton("Settings");
        settingsButton.setPreferredSize(new Dimension(500, 50));
        settingsButton.setMaximumSize(new Dimension(750, 50)); // Enforce size in BoxLayout
        settingsButton.setBackground(new Color(199, 199, 199));
        settingsButton.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));
        settingsButton.setFont(new Font("Comic Sans MS", Font.ITALIC, 30));
        settingsButton.setFocusPainted(false);
        settingsButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        settingsButton.setCursor(new Cursor(Cursor.HAND_CURSOR));

        settingsButton.addActionListener(_ -> {
            if (configHandler.getConfigBoolean("soundEffects")) {
                audioHandler.playClickSound();
            }
            cardLayout.show(cardPanel, "settings");
        });


        JButton quitButton = new JButton("Quit");
        quitButton.setPreferredSize(new Dimension(500, 50));
        quitButton.setMaximumSize(new Dimension(750, 50)); // Enforce size in BoxLayout
        quitButton.setBackground(new Color(255, 120, 120));
        quitButton.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));
        quitButton.setFont(new Font("Comic Sans MS", Font.ITALIC, 30));
        quitButton.setFocusPainted(false);
        quitButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        quitButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        quitButton.addActionListener(_ -> {
            if (configHandler.getConfigBoolean("soundEffects")) {
                audioHandler.playClickSound();
            }
            
            int response = JOptionPane.showConfirmDialog(this, "Are you sure you want to quit?", "Confirm Exit", JOptionPane.YES_NO_OPTION);
            if (response == JOptionPane.YES_OPTION) {
                System.exit(0);
            }
        });

        centerPanel.add(Box.createVerticalStrut(100)); // Top padding
        centerPanel.add(title);
        centerPanel.add(Box.createVerticalStrut(100)); // Space between title and button
        centerPanel.add(startButton);
        centerPanel.add(Box.createVerticalStrut(25)); // Space between title and button
        centerPanel.add(aiModeButton);
        centerPanel.add(Box.createVerticalStrut(25)); // Space between buttons
        centerPanel.add(settingsButton);
        centerPanel.add(Box.createVerticalStrut(25)); // Space between buttons
        centerPanel.add(quitButton);
        centerPanel.add(Box.createVerticalStrut(110)); // Space between buttons

        centerPanel.add(Box.createVerticalGlue());

        JButton githubButton = new JButton("Support My GitHub Page :)");        
        githubButton.setPreferredSize(new Dimension(220, 30));
        githubButton.setMaximumSize(new Dimension(220, 30)); // Enforce size in BoxLayout
        githubButton.setBackground(new Color(202, 153, 255));
        githubButton.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));
        githubButton.setFont(new Font("Comic Sans MS", Font.BOLD, 15));
        githubButton.setFocusPainted(false);
        githubButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        githubButton.setCursor(new Cursor(Cursor.HAND_CURSOR));

        githubButton.addActionListener(e -> {
            if (configHandler.getConfigBoolean("soundEffects")) {
                audioHandler.playClickSound();
            }

            try {
                Desktop.getDesktop().browse(new URI("https://github.com/CodePraisy")); // Replace with real URL
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        JLabel versionLabel = new JLabel(configHandler.loadVersion(), SwingConstants.CENTER);
        versionLabel.setFont(new Font("Comic Sans MS", Font.ITALIC, 25));
        versionLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setOpaque(false);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 20));
        bottomPanel.add(githubButton, BorderLayout.WEST);
        bottomPanel.add(versionLabel, BorderLayout.EAST);

        mainMenuPanel.add(centerPanel, BorderLayout.CENTER);
        mainMenuPanel.add(bottomPanel, BorderLayout.SOUTH);

        if (configHandler.getConfigBoolean("audio.backgroundMusic")) {
            audioHandler.playRandomBackgroundMusic();
        }

        this.setContentPane(cardPanel);
        this.setVisible(true);
    }
}
    
 
