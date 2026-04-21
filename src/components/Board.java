package components;

import java.awt.*;
import javax.swing.*;

public class Board extends JPanel {
    private final Color edgeColor = new Color(60, 60, 60);

    public Board() {
        this.setPreferredSize(new Dimension(750, 750));
        this.setMaximumSize(new Dimension(750, 750));
        this.setOpaque(false); // Make JPanel transparent

        // Add a border to the board
        this.setBorder(BorderFactory.createLineBorder(edgeColor, 3));


    }
}