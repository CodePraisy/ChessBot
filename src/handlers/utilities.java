package handlers;
import java.awt.Desktop;
import java.net.URI;

public class utilities {
    public static void openURL(String url) {
        try {
            System.out.println("Attempting to open: " + url);
            
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                System.out.println("Using Desktop.browse()");
                Desktop.getDesktop().browse(new URI(url));
            } else {
                System.out.println("Desktop.browse() not supported, using fallback");
                String os = System.getProperty("os.name").toLowerCase();
                System.out.println("OS: " + os);
                
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

