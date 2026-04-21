package handlers;
import java.awt.Desktop;
import java.net.URI;

public class utilities {
    public static void openURL(String url) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                } else {
                    // Fallback for Linux without proper Desktop support
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("linux")) {
                        Runtime.getRuntime().exec(new String[]{"xdg-open", url});
                    } else if (os.contains("win")) {
                        Runtime.getRuntime().exec(new String[]{"start", url});
                    } else if (os.contains("mac")) {
                        Runtime.getRuntime().exec(new String[]{"open", url});
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

