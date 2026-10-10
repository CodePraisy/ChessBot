import ai.engineBootstrap;
import main.mainWindow;

public class chessUI {
    public static void main(String[] args) {
        engineBootstrap.install(args); // connects engines (see src/ai/engineBootstrap.java)
        javax.swing.SwingUtilities.invokeLater(() -> new mainWindow("Praise's Chess", "src/assets/logo.png", true));
    }
}
