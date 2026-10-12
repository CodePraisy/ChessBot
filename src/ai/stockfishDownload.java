package ai;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fetches the official Stockfish release into engine/stockfish/ the first time the game starts without it,
 * so Stockfish is always there to play against. Runs in the background; the Stockfish opponents appear in
 * the menus once it is ready. Tries the fast AVX2 build first and falls back to the most compatible one if
 * this CPU can't run it. Turn off with -Dchessbot.stockfishDownload=false.
 */
public final class stockfishDownload {
    private stockfishDownload() {}

    private static final String BASE = "https://github.com/official-stockfish/Stockfish/releases/latest/download/";

    /** Downloads and checks Stockfish; returns its path, or null if that wasn't possible. */
    public static Path fetch() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean windows = os.contains("win");
        Path target = Path.of("engine/stockfish", windows ? "stockfish.exe" : "stockfish");
        String[] assets;
        if (windows) assets = new String[]{"stockfish-windows-x86-64-avx2.zip", "stockfish-windows-x86-64.zip"};
        else if (os.contains("mac")) assets = arch.contains("aarch64") || arch.contains("arm")
                ? new String[]{"stockfish-macos-m1-apple-silicon.tar"}
                : new String[]{"stockfish-macos-x86-64-avx2.tar", "stockfish-macos-x86-64.tar"};
        else assets = new String[]{"stockfish-ubuntu-x86-64-avx2.tar", "stockfish-ubuntu-x86-64.tar"};
        for (String asset : assets) {
            try {
                System.out.println("Downloading Stockfish (" + asset + ")...");
                byte[] archive = get(BASE + asset);
                byte[] exe = asset.endsWith(".zip") ? fromZip(archive) : fromTar(archive);
                if (exe == null) throw new IOException("no Stockfish program in " + asset);
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".part");
                Files.write(tmp, exe);
                tmp.toFile().setExecutable(true);
                if (!works(tmp)) { Files.deleteIfExists(tmp); System.out.println("  that build doesn't run on this CPU"); continue; }
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(target.resolveSibling("VERSION.txt"), asset + "\n");
                System.out.println("Stockfish ready: " + target);
                return target;
            } catch (Exception e) {
                System.err.println("Could not download Stockfish: " + e.getMessage()
                        + " (you can run: python engine/get_stockfish.py)");
                return null;
            }
        }
        return null;
    }

    private static byte[] get(String url) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "ChessBot")
                .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        return r.body();
    }

    /** The program is the biggest file named stockfish-* (the archives also hold source code and docs). */
    private static boolean isProgram(String name) {
        String file = name.substring(name.lastIndexOf('/') + 1);
        return file.startsWith("stockfish") && !name.contains("/src/") && !file.endsWith(".md") && !file.endsWith(".txt");
    }

    private static byte[] fromZip(byte[] data) throws IOException {
        byte[] best = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.isDirectory() || !isProgram(e.getName())) continue;
                byte[] b = zip.readAllBytes();
                if (best == null || b.length > best.length) best = b;
            }
        }
        return best;
    }

    /** Minimal POSIX tar reader: 512-byte headers, size in octal. */
    private static byte[] fromTar(byte[] data) {
        byte[] best = null;
        for (int pos = 0; pos + 512 <= data.length; ) {
            if (data[pos] == 0) break;
            String name = field(data, pos, 100), prefix = field(data, pos + 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            long size = Long.parseLong(field(data, pos + 124, 12).trim().isEmpty() ? "0" : field(data, pos + 124, 12).trim(), 8);
            char type = (char) data[pos + 156];
            int start = pos + 512;
            if ((type == '0' || type == 0) && isProgram(name) && start + size <= data.length && (best == null || size > best.length))
                best = java.util.Arrays.copyOfRange(data, start, (int) (start + size));
            pos = start + (int) ((size + 511) / 512 * 512);
        }
        return best;
    }

    private static String field(byte[] d, int off, int len) {
        int end = off;
        while (end < off + len && d[end] != 0) end++;
        return new String(d, off, end - off, StandardCharsets.US_ASCII);
    }

    /** True if the program answers the UCI handshake. */
    private static boolean works(Path exe) {
        try {
            Process p = new ProcessBuilder(exe.toAbsolutePath().toString()).redirectErrorStream(true).start();
            p.getOutputStream().write("uci\nquit\n".getBytes(StandardCharsets.US_ASCII));
            p.getOutputStream().flush();
            boolean done = p.waitFor(15, TimeUnit.SECONDS);
            try (InputStream in = p.getInputStream()) {
                String out = new String(in.readNBytes(1 << 16), StandardCharsets.UTF_8);
                if (!done) p.destroyForcibly();
                return out.contains("uciok");
            }
        } catch (Exception e) {
            return false;
        }
    }
}
