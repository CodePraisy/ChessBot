package main;
import javax.swing.*;

import handlers.audioHandler;
import handlers.configHandler;

import java.awt.*;
import java.awt.image.BufferedImage;

public class settingsPanel extends JPanel {
    private boolean canSave = false;
    private JButton saveButton;

    public settingsPanel(CardLayout cardLayout, JPanel cardPanel) {
        this.setBackground(new Color(180, 180, 180));
        this.setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(0, 100, 0, 100));
        centerPanel.setOpaque(false);

        JLabel settingsTitle = new JLabel("Settings", SwingConstants.CENTER);
        settingsTitle.setVerticalTextPosition(JLabel.TOP);
        settingsTitle.setHorizontalTextPosition(JLabel.CENTER);
        settingsTitle.setIcon(new ImageIcon("src/assets/settingsWrench.png"));
        settingsTitle.setFont(new Font("Comic Sans MS", Font.BOLD, 60));
        settingsTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        int boxSize = 24;

        // Save state upon closing
        JPanel saveStatePanel = new JPanel();
        saveStatePanel.setOpaque(false);
        saveStatePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        saveStatePanel.setLayout(new BoxLayout(saveStatePanel, BoxLayout.X_AXIS));

        JLabel saveStateLabel = new JLabel("Save State:");
        saveStateLabel.setFont(new Font("Comic Sans MS", Font.ITALIC, 25));
        saveStateLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JCheckBox saveStateCheckbox = new JCheckBox();
        saveStateCheckbox.setSelected(true);
        saveStateCheckbox.setFont(new Font("Comic Sans MS", Font.PLAIN, 22));
        saveStateCheckbox.setFocusPainted(false);
        saveStateCheckbox.setOpaque(false);
        saveStateCheckbox.setBorder(BorderFactory.createEmptyBorder(0, 100, 0, 0));
        saveStateCheckbox.setCursor(new Cursor(Cursor.HAND_CURSOR));
        saveStateCheckbox.setIcon(makeCheckBoxIcon(boxSize, false));
        saveStateCheckbox.setSelectedIcon(makeCheckBoxIcon(boxSize, configHandler.getConfigBoolean("saveState")));

        saveStateCheckbox.addActionListener(e -> {
            if (configHandler.getConfigBoolean("soundEffects")) {
                audioHandler.playClickSound();
            }
            // Logic to enable/disable save state can be added here

            canSave = true;
            updateSaveButtonColor();
        });

        saveStatePanel.add(Box.createHorizontalGlue());
        saveStatePanel.add(saveStateLabel);
        saveStatePanel.add(saveStateCheckbox);
        saveStatePanel.add(Box.createHorizontalGlue());

        // Use save state
        JPanel useSaveStatePanel = new JPanel();
        useSaveStatePanel.setOpaque(false);
        useSaveStatePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        useSaveStatePanel.setLayout(new BoxLayout(useSaveStatePanel, BoxLayout.X_AXIS));

        JLabel useSaveStateLabel = new JLabel("Use Save State:");
        useSaveStateLabel.setFont(new Font("Comic Sans MS", Font.ITALIC, 25));
        useSaveStateLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JCheckBox useSaveStateCheckbox = new JCheckBox();
        useSaveStateCheckbox.setSelected(true);
        useSaveStateCheckbox.setFont(new Font("Comic Sans MS", Font.PLAIN, 22));
        useSaveStateCheckbox.setFocusPainted(false);
        useSaveStateCheckbox.setOpaque(false);
        useSaveStateCheckbox.setBorder(BorderFactory.createEmptyBorder(0, 100, 0, 0));
        useSaveStateCheckbox.setCursor(new Cursor(Cursor.HAND_CURSOR));
        useSaveStateCheckbox.setIcon(makeCheckBoxIcon(boxSize, false));
        useSaveStateCheckbox.setSelectedIcon(makeCheckBoxIcon(boxSize, configHandler.getConfigBoolean("useSaveState")));

        useSaveStateCheckbox.addActionListener(e -> {
            if (configHandler.getConfigBoolean("soundEffects")) {
                audioHandler.playClickSound();
            }

            canSave = true;
            updateSaveButtonColor();
        });

        useSaveStatePanel.add(Box.createHorizontalGlue());
        useSaveStatePanel.add(useSaveStateLabel);
        useSaveStatePanel.add(useSaveStateCheckbox);
        useSaveStatePanel.add(Box.createHorizontalGlue());

        JPanel musicPanel = new JPanel();
        musicPanel.setOpaque(false);
        musicPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        musicPanel.setLayout(new BoxLayout(musicPanel, BoxLayout.X_AXIS));

        JLabel musicLabel = new JLabel("Background Music:");
        musicLabel.setFont(new Font("Comic Sans MS", Font.ITALIC, 25));
        musicLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JCheckBox musicCheckbox = new JCheckBox();
        musicCheckbox.setSelected(true);
        musicCheckbox.setFont(new Font("Comic Sans MS", Font.PLAIN, 22));
        musicCheckbox.setFocusPainted(false);
        musicCheckbox.setOpaque(false);
        musicCheckbox.setBorder(BorderFactory.createEmptyBorder(0, 100, 0, 0));
        musicCheckbox.setCursor(new Cursor(Cursor.HAND_CURSOR));
        musicCheckbox.setIcon(makeCheckBoxIcon(boxSize, false));
        musicCheckbox.setSelectedIcon(makeCheckBoxIcon(boxSize, configHandler.getConfigBoolean("backgroundMusic")));

        musicCheckbox.addActionListener(e -> {
            if (configHandler.getConfigBoolean("soundEffects")) {
                audioHandler.playClickSound();
            }

            canSave = true;
            updateSaveButtonColor();
        });

        musicPanel.add(Box.createHorizontalGlue());
        musicPanel.add(musicLabel);
        musicPanel.add(musicCheckbox);
        musicPanel.add(Box.createHorizontalGlue());

        JPanel soundEffectsPanel = new JPanel();
        soundEffectsPanel.setOpaque(false);
        soundEffectsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        soundEffectsPanel.setLayout(new BoxLayout(soundEffectsPanel, BoxLayout.X_AXIS));

        JLabel soundEffectsLabel = new JLabel("Sound Effects:");
        soundEffectsLabel.setFont(new Font("Comic Sans MS", Font.ITALIC, 25));
        soundEffectsLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JCheckBox soundEffectsCheckbox = new JCheckBox();
        soundEffectsCheckbox.setSelected(true);
        soundEffectsCheckbox.setFont(new Font("Comic Sans MS", Font.PLAIN, 22));
        soundEffectsCheckbox.setFocusPainted(false);
        soundEffectsCheckbox.setOpaque(false);
        soundEffectsCheckbox.setBorder(BorderFactory.createEmptyBorder(0, 100, 0, 0));
        soundEffectsCheckbox.setCursor(new Cursor(Cursor.HAND_CURSOR));
        soundEffectsCheckbox.setIcon(makeCheckBoxIcon(boxSize, false));
        soundEffectsCheckbox.setSelectedIcon(makeCheckBoxIcon(boxSize, configHandler.getConfigBoolean("soundEffects")));
        soundEffectsCheckbox.setIcon(makeCheckBoxIcon(boxSize, false));
        
        soundEffectsCheckbox.addActionListener(e -> {
            if (configHandler.getConfigBoolean("soundEffects")) {
                audioHandler.playClickSound();
            }

            canSave = true;
            updateSaveButtonColor();
        });

        saveButton = new JButton("Save Changes");
        saveButton.setMaximumSize(new Dimension(200, 40)); // Enforce size in BoxLayout
        saveButton.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));
        saveButton.setFont(new Font("Comic Sans MS", Font.ITALIC, 20));
        saveButton.setFocusPainted(false);
        saveButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        saveButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        saveButton.addActionListener(e -> {
            if (configHandler.getConfigBoolean("soundEffects")) {
                audioHandler.playClickSound();
            }
        });
        
        updateSaveButtonColor();

        saveButton.addActionListener(e -> {
            if (canSave) {
                // Save the settings to config.json
                
                configHandler.setConfigBoolean("saves.saveState", saveStateCheckbox.isSelected());
                configHandler.setConfigBoolean("saves.useSaveState", useSaveStateCheckbox.isSelected());
                configHandler.setConfigBoolean("audio.backgroundMusic", musicCheckbox.isSelected());
                configHandler.setConfigBoolean("audio.soundEffects", soundEffectsCheckbox.isSelected());

                if (audioHandler.isBackgroundMusicPlaying() && !musicCheckbox.isSelected()) {
                    audioHandler.stopBackgroundMusic();
                }

                if (!audioHandler.isBackgroundMusicPlaying() && musicCheckbox.isSelected()) {
                    audioHandler.playRandomBackgroundMusic();
                }

                canSave = false; // Reset canSave after saving

                updateSaveButtonColor();
            }
        });

        // Back Button

        JButton backButton = new JButton("Back to Main Menu");
        backButton.setMaximumSize(new Dimension(200, 40));
        backButton.setBackground(new Color(255, 120, 120));
        backButton.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));
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

        soundEffectsPanel.add(Box.createHorizontalGlue());
        soundEffectsPanel.add(soundEffectsLabel);
        soundEffectsPanel.add(soundEffectsCheckbox);
        soundEffectsPanel.add(Box.createHorizontalGlue());

        // Add components to the center panel
        centerPanel.add(Box.createVerticalStrut(80)); // Top padding
        centerPanel.add(settingsTitle);
        centerPanel.add(Box.createVerticalStrut(60)); // Space between title and music panel
        centerPanel.add(saveStatePanel);
        centerPanel.add(useSaveStatePanel);
        centerPanel.add(musicPanel);
        centerPanel.add(soundEffectsPanel);
        centerPanel.add(Box.createVerticalStrut(100)); // Space before back button
        centerPanel.add(saveButton);
        centerPanel.add(Box.createVerticalStrut(20)); // Space before back button
        centerPanel.add(backButton);

        this.add(centerPanel);
    }
    
    private static ImageIcon makeCheckBoxIcon(int size, boolean selected) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();

        // Enable anti-aliasing for smooth visuals
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Custom background color of the box (e.g., light gray)
        g2.setColor(new Color(230, 230, 230)); // Light gray background
        g2.fillRect(0, 0, size, size);

        // Thicker border with custom color
        g2.setColor(new Color(89, 83, 67)); // Dark blue border
        g2.setStroke(new BasicStroke(4)); // Thicker border
        g2.drawRect(1, 1, size - 3, size - 3);

        if (selected) {
            // Draw checkmark (✓)
            g2.setColor(new Color(0, 180, 0)); // Dark green checkmark
            g2.setStroke(new BasicStroke(3));
            g2.drawLine(4, size / 2, size / 3, size - 5);
            g2.drawLine(size / 3, size - 5, size - 5, 4);
        } else {
            // Draw X
            g2.setColor(new Color(200, 0, 0)); // Red X
            g2.setStroke(new BasicStroke(3));
            g2.drawLine(4, 4, size - 4, size - 4);
            g2.drawLine(size - 4, 4, 4, size - 4);
        }

        g2.dispose();
        return new ImageIcon(image);
    }

    private void updateSaveButtonColor() {
        if (canSave) {
            saveButton.setBackground(new Color(175, 237, 189));
            saveButton.setEnabled(true);
        } else {
            saveButton.setBackground(new Color(199, 199, 199));
            saveButton.setEnabled(false);
        }
    }

}

