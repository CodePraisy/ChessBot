package components;

import java.awt.*;
import javax.swing.*;

public class SidePanel extends JPanel {
    public SidePanel() {
        this.setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        this.setBackground(new Color(214, 191, 163));
        this.setOpaque(true);
        this.setPreferredSize(new Dimension(400, 950));
        this.setMaximumSize(new Dimension(400, 950));
        this.setBorder(BorderFactory.createLineBorder(new Color(89, 83, 67), 3));

        // Add your side panel content here
        JLabel placeholder = new JLabel("Side Panel");
        placeholder.setFont(new Font("Comic Sans MS", Font.BOLD, 20));
        placeholder.setAlignmentX(Component.CENTER_ALIGNMENT);
        this.add(Box.createVerticalStrut(20));
        this.add(placeholder);
    }
}