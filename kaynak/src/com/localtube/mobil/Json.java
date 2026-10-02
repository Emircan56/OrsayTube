package com.localtube.mobil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser + writer — bağımlılıksız, saf Java.
 * Amaç: aynı kod hem Android APK içinde hem masaüstü testlerinde çalışsın
 * (org.json android.jar'a özgü olduğu için kullanılmadı).
 *
 * Degerler: LinkedHashMap<String,Object>, ArrayList<Object>, String,
 * Long / Double, Boolean, null.
 */
public final class Json {

    private Json() {
    }

    /* ---------------- parse ---------------- */

    public static Object parse(String s) {
        if (s == null) {
            throw new IllegalArgumentException("null json");
        }
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.n) {
            throw new IllegalArgumentException("trailing data @ " + p.i);
        }
        return v;
    }

    private static final class P {
        final String s;
        final int n;
        int i;

        P(String s) {
            this.s = s;
            this.n = s.length();
        }

        void ws() {
            while (i < n) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (i >= n) {
                throw new IllegalArgumentException("eof @ " + i);
            }
            return s.charAt(i);
        }

        Object value() {
            char c = peek();
            switch (c) {
                case '{':
                    return obj();
                case '[':
                    return arr();
                case '"':
                    return str();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return num();
            }
        }

        void expect(String w) {
            if (!s.startsWith(w, i)) {
                throw new IllegalArgumentException("expected " + w + " @ " + i);
            }
            i += w.length();
        }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++;
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                if (peek() != '"') {
                    throw new IllegalArgumentException("key @ " + i);
                }
                String k = str();
                ws();
                if (peek() != ':') {
                    throw new IllegalArgumentException("colon @ " + i);
                }
                i++;
                ws();
                m.put(k, value());
                ws();
                char c = peek();
                if (c == ',') {
                    i++;
                    ws();
                } else if (c == '}') {
                    i++;
                    return m;
                } else {
                    throw new IllegalArgumentException("objsep @ " + i);
                }
            }
        }

        List<Object> arr() {
            List<Object> l = new ArrayList<Object>();
            i++;
            ws();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                char c = peek();
                if (c == ',') {
                    i++;
                    ws();
                } else if (c == ']') {
                    i++;
                    return l;
                } else {
                    throw new IllegalArgumentException("arrsep @ " + i);
                }
            }
        }

        String str() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < n) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= n) {
                        break;
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > n) {
                                throw new IllegalArgumentException("bad \\u @ " + i);
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            sb.append(e);
                            break;
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string @ " + i);
        }

        Object num() {
            int st = i;
            while (i < n) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+'
                        || c == '.' || c == 'e' || c == 'E') {
                    i++;
                } else {
                    break;
                }
            }
            if (i == st) {
                throw new IllegalArgumentException("num @ " + i);
            }
            String t = s.substring(st, i);
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                try {
                    return Long.valueOf(Long.parseLong(t));
                } catch (Exception ignored) {
                }
            }
            return Double.valueOf(Double.parseDouble(t));
        }
    }

    /* ---------------- write ---------------- */

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        w(sb, v);
        return sb.toString();
    }

    private static void w(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v instanceof String) {
            str(sb, (String) v);
            return;
        }
        if (v instanceof Boolean) {
            sb.append(v.toString());
            return;
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
            return;
        }
        if (v instanceof Number) {
            sb.append(v.toString());
            return;
        }
        if (v instanceof Map) {
            sb.append('{');
            boolean f = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!f) {
                    sb.append(',');
                }
                f = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                w(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof Iterable) {
            sb.append('[');
            boolean f = true;
            for (Object o : (Iterable<?>) v) {
                if (!f) {
                    sb.append(',');
                }
                f = false;
                w(sb, o);
            }
            sb.append(']');
            return;
        }
        str(sb, v.toString());
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String h = "0000" + Integer.toHexString(c);
                        sb.append(h.substring(h.length() - 4));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /* ---------------- yardimcilar ---------------- */

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v) {
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object v) {
        if (v instanceof List) {
            return (List<Object>) v;
        }
        return null;
    }

    public static String s(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static long lg(Map<String, Object> m, String key, long def) {
        if (m == null) {
            return def;
        }
        Object v = m.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (Exception ignored) {
            }
        }
        return def;
    }
}
