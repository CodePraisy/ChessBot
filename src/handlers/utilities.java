package handlers;

import java.awt.Desktop;
import java.net.URI;

public class utilities {
    public static void openURL(String url) {
        try {
            System.out.println("Attempting to open: " + url);

            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("linux")) {
                openOnLinux(url);
                return;
            }

            // Only use Desktop on non-Linux systems
            if (Desktop.isDesktopSupported() &&
                Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {

                Desktop.getDesktop().browse(new URI(url));
                return;
            }

            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", url});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", url});
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static void openOnLinux(String url) {
        try {
            new ProcessBuilder("firefox", url).start();
        } catch (Exception e1) {
            try {
                new ProcessBuilder("google-chrome", url).start();
            } catch (Exception e2) {
                try {
                    new ProcessBuilder("/usr/bin/xdg-open", url).start();
                } catch (Exception e3) {
                    e3.printStackTrace();
                }
            }
        }
    }
}