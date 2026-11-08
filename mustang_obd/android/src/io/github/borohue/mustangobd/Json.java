package io.github.borohue.mustangobd;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Minimal pretty JSON writer (Map, List, String, Number, Boolean, null). */
public final class Json {
    private Json() {
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o, 0);
        return sb.append('\n').toString();
    }

    private static void indent(StringBuilder sb, int n) {
        sb.append('\n');
        for (int i = 0; i < n; i++) sb.append("  ");
    }

    private static void write(StringBuilder sb, Object o, int lvl) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                indent(sb, lvl + 1);
                str(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), lvl + 1);
                if (it.hasNext()) sb.append(',');
            }
            indent(sb, lvl);
            sb.append('}');
        } else if (o instanceof List) {
            List<?> l = (List<?>) o;
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                indent(sb, lvl + 1);
                write(sb, l.get(i), lvl + 1);
                if (i < l.size() - 1) sb.append(',');
            }
            indent(sb, lvl);
            sb.append(']');
        } else if (o instanceof Boolean) {
            sb.append(o.toString());
        } else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : Pids.fmt(d));
        } else if (o instanceof Number) {
            sb.append(o.toString());
        } else {
            str(sb, o.toString());
        }
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
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
