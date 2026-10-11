package ai;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import handlers.Json;

/**
 * Position evaluators for the native search. Scores are centipawns for the side to move.
 *  - mlp(folder): a TensorFlow model exported by engine/train_tf.py (model.json + weights.npz).
 *  - gym(file):   a value network checkpoint from the original self-play gym (engine/neural_model.py).
 *  - classic():   a hand-written material + piece-square evaluation (no training needed).
 * Each evaluator object is used by one search thread at a time.
 */
public abstract class neuralNet {
    public abstract int evaluate(fastBoard b);

    // ---------------------------------------------------------------- TensorFlow models

    public static neuralNet mlp(Path folder) throws IOException {
        Map<String, Object> meta = Json.parseObject(Files.readString(folder.resolve("model.json")));
        if (!"chessbot-mlp".equals(Json.str(meta, "format", "")) || Json.num(meta, "version", 0) != 2)
            throw new IOException("not a ChessBot model export: " + folder);
        Map<String, float[]> arrays = new HashMap<>();
        Map<String, int[]> shapes = new HashMap<>();
        readNpz(folder.resolve("weights.npz"), arrays, shapes);
        var layers = Json.list(meta, "layers");
        int count = layers.size() - 1;
        float[][] w = new float[count][], bias = new float[count][];
        int[] sizes = new int[count + 1];
        for (int i = 0; i <= count; i++) sizes[i] = ((Number) layers.get(i)).intValue();
        if (sizes[0] != 772 || sizes[count] != 1) throw new IOException("unexpected layer sizes in " + folder);
        for (int i = 0; i < count; i++) {
            w[i] = arrays.get("w" + i);
            bias[i] = arrays.get("b" + i);
            int[] s = shapes.get("w" + i);
            if (w[i] == null || bias[i] == null || s == null || s.length != 2 || s[0] != sizes[i] || s[1] != sizes[i + 1]
                    || bias[i].length != sizes[i + 1]) throw new IOException("weights do not match model.json in " + folder);
        }
        return new mlpNet(w, bias, sizes, meta.get("scale") instanceof Number n ? n.floatValue() : 400f);
    }

    private static final class mlpNet extends neuralNet {
        private final float[][] w, bias;
        private final int[] sizes;
        private final float scale;
        private final float[][] act;

        mlpNet(float[][] w, float[][] bias, int[] sizes, float scale) {
            this.w = w;
            this.bias = bias;
            this.sizes = sizes;
            this.scale = scale;
            act = new float[sizes.length][];
            for (int i = 1; i < sizes.length; i++) act[i] = new float[sizes[i]];
        }

        @Override public int evaluate(fastBoard b) {
            int side = b.side, h = sizes[1];
            float[] first = act[1], w0 = w[0];
            System.arraycopy(bias[0], 0, first, 0, h);
            for (int sq = 0; sq < 64; sq++) {
                int p = b.b[sq];
                if (p == 0) continue;
                int code = Math.abs(p) + ((p > 0 ? 1 : -1) == side ? 0 : 6);
                int f = ((side == fastBoard.WHITE ? sq : sq ^ 56) * 12 + code - 1) * h;
                for (int j = 0; j < h; j++) first[j] += w0[f + j];
            }
            int c = b.castling;
            int[] bits = side == fastBoard.WHITE ? new int[]{c & fastBoard.WK, c & fastBoard.WQ, c & fastBoard.BK, c & fastBoard.BQ}
                    : new int[]{c & fastBoard.BK, c & fastBoard.BQ, c & fastBoard.WK, c & fastBoard.WQ};
            for (int k = 0; k < 4; k++) if (bits[k] != 0) {
                int f = (768 + k) * h;
                for (int j = 0; j < h; j++) first[j] += w0[f + j];
            }
            float[] in = first;
            for (int layer = 1; layer < w.length; layer++) {
                int n = sizes[layer], m = sizes[layer + 1];
                float[] out = act[layer + 1], wl = w[layer];
                System.arraycopy(bias[layer], 0, out, 0, m);
                for (int i = 0; i < n; i++) {
                    float x = in[i];
                    if (x <= 0) continue; // ReLU
                    int row = i * m;
                    for (int j = 0; j < m; j++) out[j] += x * wl[row + j];
                }
                in = out;
            }
            return clamp(Math.round(in[0] * scale));
        }
    }

    /** Reference: the network input vector for b (used by tests to compare with Python). */
    public static float[] features(fastBoard b) {
        float[] x = new float[772];
        for (int sq = 0; sq < 64; sq++) {
            int p = b.b[sq];
            if (p == 0) continue;
            int code = Math.abs(p) + ((p > 0 ? 1 : -1) == b.side ? 0 : 6);
            x[(b.side == fastBoard.WHITE ? sq : sq ^ 56) * 12 + code - 1] = 1;
        }
        int c = b.castling;
        boolean white = b.side == fastBoard.WHITE;
        x[768] = (c & (white ? fastBoard.WK : fastBoard.BK)) != 0 ? 1 : 0;
        x[769] = (c & (white ? fastBoard.WQ : fastBoard.BQ)) != 0 ? 1 : 0;
        x[770] = (c & (white ? fastBoard.BK : fastBoard.WK)) != 0 ? 1 : 0;
        x[771] = (c & (white ? fastBoard.BQ : fastBoard.WQ)) != 0 ? 1 : 0;
        return x;
    }

    // ---------------------------------------------------------------- original gym checkpoints

    public static neuralNet gym(Path checkpoint) throws IOException {
        Map<String, float[]> arrays = new HashMap<>();
        Map<String, int[]> shapes = new HashMap<>();
        readNpz(checkpoint, arrays, shapes);
        float[] w1 = arrays.get("w1"), b1 = arrays.get("b1"), w2 = arrays.get("w2"), b2 = arrays.get("b2");
        if (w1 == null || b1 == null || w2 == null || b2 == null || w1.length != 782 * b1.length || w2.length != b1.length)
            throw new IOException("not a gym checkpoint: " + checkpoint);
        return new gymNet(w1, b1, w2, b2[0]);
    }

    private static final class gymNet extends neuralNet {
        private static final float[] VALUES = {0, 100, 320, 330, 500, 900, 0};
        private final float[] w1, b1, w2, hidden;
        private final float b2;
        private final int h;

        gymNet(float[] w1, float[] b1, float[] w2, float b2) {
            this.w1 = w1;
            this.b1 = b1;
            this.w2 = w2;
            this.b2 = b2;
            h = b1.length;
            hidden = new float[h];
        }

        private void add(int feature, float value) {
            int f = feature * h;
            for (int j = 0; j < h; j++) hidden[j] += value * w1[f + j];
        }

        @Override public int evaluate(fastBoard b) {
            System.arraycopy(b1, 0, hidden, 0, h);
            float material = 0;
            for (int sq = 0; sq < 64; sq++) {
                int p = b.b[sq];
                if (p == 0) continue;
                int type = Math.abs(p);
                // python-chess squares (a1 = 0) are this board's squares mirrored top-to-bottom
                add(((p > 0 ? 0 : 6) + type - 1) * 64 + (sq ^ 56), 1);
                material += (p > 0 ? 1 : -1) * VALUES[type] / 1200f;
            }
            add(768, b.side == fastBoard.WHITE ? 1 : -1);
            int c = b.castling;
            if ((c & fastBoard.WK) != 0) add(769, 1);
            if ((c & fastBoard.WQ) != 0) add(770, 1);
            if ((c & fastBoard.BK) != 0) add(771, 1);
            if ((c & fastBoard.BQ) != 0) add(772, 1);
            if (b.ep >= 0) add(773 + b.ep % 8, 1);
            add(781, Math.min(b.halfmove, 100) / 100f);
            double out = b2 + material;
            for (int j = 0; j < h; j++) out += Math.tanh(hidden[j]) * w2[j];
            double v = Math.max(-0.999, Math.min(0.999, Math.tanh(out)));
            int white = (int) (0.5 * Math.log((1 + v) / (1 - v)) * 600); // arctanh, truncated like Python's int()
            return clamp(white * b.side);
        }
    }

    // ---------------------------------------------------------------- hand-written evaluation

    public static neuralNet classic() { return new classicEval(); }

    /** Material plus simple piece-square bonuses, blended between middlegame and endgame. */
    private static final class classicEval extends neuralNet {
        private static final int[] MG = {0, 82, 337, 365, 477, 1025, 0}, EG = {0, 94, 281, 297, 512, 936, 0};
        private static final int[] PHASE = {0, 0, 1, 1, 2, 4, 0};
        private static final int[][] PST_MG = new int[7][64], PST_EG = new int[7][64];

        static {
            for (int sq = 0; sq < 64; sq++) {
                int row = sq / 8, col = sq % 8; // from White's view; row 0 = rank 8
                int rank = 7 - row;
                double centre = 3.5 - Math.max(Math.abs(row - 3.5), Math.abs(col - 3.5)); // 0 edge .. 3 centre
                int middleFiles = col >= 2 && col <= 5 ? 1 : 0;
                PST_MG[1][sq] = rank == 0 || rank == 7 ? 0 : (rank - 1) * 4 + middleFiles * (rank >= 2 && rank <= 4 ? 18 : 6);
                PST_EG[1][sq] = rank == 0 || rank == 7 ? 0 : (rank - 1) * 14;
                PST_MG[2][sq] = (int) (centre * 14) - 20;
                PST_EG[2][sq] = (int) (centre * 10) - 15;
                PST_MG[3][sq] = (int) (centre * 7) - 8 + (row == col || row + col == 7 ? 6 : 0);
                PST_EG[3][sq] = (int) (centre * 6) - 6;
                PST_MG[4][sq] = (rank == 6 ? 18 : 0) + (col == 3 || col == 4 ? 6 : 0);
                PST_EG[4][sq] = rank == 6 ? 10 : 0;
                PST_MG[5][sq] = (int) (centre * 3) - 4;
                PST_EG[5][sq] = (int) (centre * 8) - 10;
                PST_MG[6][sq] = rank == 0 ? (col <= 2 || col >= 6 ? 20 : 0) : -12 * Math.min(rank, 4);
                PST_EG[6][sq] = (int) (centre * 12) - 18;
            }
        }

        @Override public int evaluate(fastBoard b) {
            int mg = 0, eg = 0, phase = 0, wBishops = 0, bBishops = 0;
            for (int sq = 0; sq < 64; sq++) {
                int p = b.b[sq];
                if (p == 0) continue;
                int t = Math.abs(p), s = p > 0 ? sq : sq ^ 56, sign = p > 0 ? 1 : -1;
                mg += sign * (MG[t] + PST_MG[t][s]);
                eg += sign * (EG[t] + PST_EG[t][s]);
                phase += PHASE[t];
                if (p == fastBoard.BISHOP) wBishops++;
                if (p == -fastBoard.BISHOP) bBishops++;
            }
            int pair = (wBishops >= 2 ? 30 : 0) - (bBishops >= 2 ? 30 : 0);
            phase = Math.min(phase, 24);
            int white = (mg * phase + eg * (24 - phase)) / 24 + pair;
            return clamp((white + 10 * b.side) * b.side); // small bonus for having the move
        }
    }

    static int clamp(int cp) { return Math.max(-20000, Math.min(20000, cp)); }

    // ---------------------------------------------------------------- .npz reading

    private static final Pattern SHAPE = Pattern.compile("'shape':\\s*\\(([^)]*)\\)"), DESCR = Pattern.compile("'descr':\\s*'([^']*)'");

    /** Reads every float32 array in an .npz (zip of .npy files); other arrays are skipped. */
    static void readNpz(Path file, Map<String, float[]> arrays, Map<String, int[]> shapes) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().endsWith(".npy")) continue;
                try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(zip.getInputStream(e)))) {
                    readNpy(e.getName().substring(0, e.getName().length() - 4), in, arrays, shapes);
                }
            }
        }
    }

    private static void readNpy(String name, DataInputStream in, Map<String, float[]> arrays, Map<String, int[]> shapes) throws IOException {
        byte[] magic = new byte[6];
        in.readFully(magic);
        if (magic[0] != (byte) 0x93 || !new String(magic, 1, 5, StandardCharsets.US_ASCII).equals("NUMPY")) throw new IOException("bad .npy: " + name);
        int major = in.readUnsignedByte();
        in.readUnsignedByte();
        int headerLength = major == 1 ? Short.toUnsignedInt(Short.reverseBytes(in.readShort())) : Integer.reverseBytes(in.readInt());
        byte[] header = new byte[headerLength];
        in.readFully(header);
        String h = new String(header, StandardCharsets.ISO_8859_1);
        Matcher d = DESCR.matcher(h), s = SHAPE.matcher(h);
        if (!d.find() || !s.find() || h.contains("'fortran_order': True")) throw new IOException("unsupported .npy header: " + name);
        if (!d.group(1).equals("<f4")) return; // metadata strings etc.
        String[] dims = s.group(1).split(",");
        int[] shape = java.util.Arrays.stream(dims).map(String::trim).filter(x -> !x.isEmpty()).mapToInt(Integer::parseInt).toArray();
        int n = 1;
        for (int v : shape) n *= v;
        byte[] raw = new byte[n * 4];
        in.readFully(raw);
        float[] out = new float[n];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        for (float v : out) if (!Float.isFinite(v)) throw new IOException("non-finite weights in " + name);
        arrays.put(name, out);
        shapes.put(name, shape);
    }
}
