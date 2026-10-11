package handlers;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON parser/writer (standard library only).
 * Objects -> LinkedHashMap<String,Object>, arrays -> ArrayList<Object>,
 * numbers -> Long (integral), BigInteger (larger integers) or Double, plus String, Boolean and null.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    // ---------- parsing ----------

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("JSON root is not an object");
        return (Map<String, Object>) v;
    }

    private Object value() {
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw err("expected key");
            String k = string();
            ws(); expect(':'); ws();
            m.put(k, value());
            ws();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw err("expected , or }");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = next();
            if (c == ']') return l;
            if (c != ',') throw err("expected , or ]");
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("bad unicode escape");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.isEmpty()) throw err("unexpected character");
        try {
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            try { return Long.parseLong(n); }
            catch (NumberFormatException outsideLongRange) { return new BigInteger(n); }
        } catch (NumberFormatException ex) {
            throw err("bad number " + n);
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw err("expected " + w);
        i += w.length();
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private char peek() { if (i >= s.length()) throw err("unexpected end"); return s.charAt(i); }
    private char next() { char c = peek(); i++; return c; }
    private void expect(char c) { if (next() != c) throw err("expected '" + c + "'"); }
    private IllegalArgumentException err(String m) { return new IllegalArgumentException("JSON: " + m + " at " + i); }

    // ---------- writing ----------

    public static String write(Object v) { StringBuilder b = new StringBuilder(); write(b, v, -1, 0); return b.toString(); }

    public static String pretty(Object v) { StringBuilder b = new StringBuilder(); write(b, v, 4, 0); return b.toString(); }

    private static void write(StringBuilder b, Object v, int indent, int depth) {
        if (v == null) b.append("null");
        else if (v instanceof String str) quote(b, str);
        else if (v instanceof Boolean || v instanceof Long || v instanceof Integer || v instanceof Short
                || v instanceof BigInteger) b.append(v);
        else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) b.append((long) d); else b.append(d);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                nl(b, indent, depth + 1);
                quote(b, String.valueOf(e.getKey()));
                b.append(indent >= 0 ? ": " : ":");
                write(b, e.getValue(), indent, depth + 1);
            }
            nl(b, indent, depth);
            b.append('}');
        } else if (v instanceof Iterable<?> it) {
            if (!it.iterator().hasNext()) { b.append("[]"); return; }
            boolean simple = true;
            for (Object o : it) if (o instanceof Map || o instanceof Iterable) { simple = false; break; }
            b.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) b.append(simple && indent >= 0 ? ", " : ",");
                first = false;
                if (!simple) nl(b, indent, depth + 1);
                write(b, o, indent, depth + 1);
            }
            if (!simple) nl(b, indent, depth);
            b.append(']');
        } else if (v instanceof int[] arr) {
            List<Object> l = new ArrayList<>();
            for (int x : arr) l.add((long) x);
            write(b, l, indent, depth);
        } else quote(b, v.toString());
    }

    private static void nl(StringBuilder b, int indent, int depth) {
        if (indent < 0) return;
        b.append('\n');
        b.append(" ".repeat(indent * depth));
    }

    private static void quote(StringBuilder b, String str) {
        b.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        b.append('"');
    }

    // ---------- tolerant accessors (missing/wrong-typed fields return the default) ----------

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        return v == null ? def : v.toString();
    }

    public static long num(Map<String, Object> m, String k, long def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof BigInteger big) {
            try { return big.longValueExact(); }
            catch (ArithmeticException outsideLongRange) { return def; }
        }
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String str) { try { return Long.parseLong(str); } catch (NumberFormatException e) { return def; } }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Boolean b ? b : def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    /** Build an ordered object: Json.map("a", 1, "b", "x"). */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return m;
    }
}
