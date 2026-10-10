import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal stand-in for the future Python engine, used by test/runTests.java to exercise
 * ai.externalProcessProvider over a real process boundary. Run: java test/fixtures/fakeEngine.java
 * It ignores the position and always answers with legal-looking fixed moves for the test position (after 1.e4).
 */
public class fakeEngine {
    public static void main(String[] args) throws Exception {
        System.out.println("fake engine booting (this non-JSON line must be ignored)");
        Thread.sleep(300); // pretend to load a model
        System.out.println("{\"type\":\"ready\",\"name\":\"fake\"}");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        Pattern idP = Pattern.compile("\"id\":(\\d+)"), typeP = Pattern.compile("\"type\":\"(\\w+)\"");
        for (String line; (line = in.readLine()) != null; ) {
            Matcher t = typeP.matcher(line), id = idP.matcher(line);
            if (!t.find()) continue;
            String type = t.group(1);
            String rid = id.find() ? id.group(1) : "0";
            switch (type) {
                case "move" -> System.out.println("{\"type\":\"move\",\"id\":" + rid + ",\"move\":[[1,4],[3,4]]}"); // e7e5
                case "analyze" -> System.out.println("{\"type\":\"analysis\",\"id\":" + rid + ",\"lines\":["
                        + "{\"move\":[[1,4],[3,4]],\"centipawns\":-20,\"mateIn\":null,\"depth\":4,\"line\":[[[1,4],[3,4]],[[7,6],[5,5]]],\"comment\":\"fake\"},"
                        + "{\"move\":[[1,2],[3,2]],\"centipawns\":-30,\"mateIn\":null,\"depth\":4,\"line\":[],\"comment\":null}]}");
                case "quit" -> { return; }
                default -> { }
            }
            System.out.flush();
        }
    }
}
