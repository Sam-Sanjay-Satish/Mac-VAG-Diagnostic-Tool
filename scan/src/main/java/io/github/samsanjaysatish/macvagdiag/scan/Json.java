package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.List;
import java.util.Map;

/**
 * Hand-written JSON for the audit log (no JSON library in the catalog). Only what the log needs: one flat object
 * per line with string, number, boolean, string-array and string-map values. Every string is escaped, including
 * control characters, lone surrogates and U+2028/U+2029, so untrusted text (MCP arguments, module bytes as text)
 * can never break a line or the JSON.
 */
final class Json {

    private Json() {
    }

    static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        b.append(c).append(s.charAt(++i));
                    } else if (c < 0x20 || c == 0x7F || c == ' ' || c == ' ' || Character.isSurrogate(c)) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** A short, printable, quoted preview of untrusted text for error messages. */
    static String preview(String untrusted) {
        if (untrusted == null) {
            return "(none)";
        }
        String cut = untrusted.length() > 32 ? untrusted.substring(0, 32) + "..." : untrusted;
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < cut.length(); i++) {
            char c = cut.charAt(i);
            b.append(c < 0x20 || c == 0x7F || Character.isSurrogate(c) || c == ' ' || c == ' ' ? '?' : c);
        }
        return b.append('"').toString();
    }

    static Obj object() {
        return new Obj();
    }

    /** One JSON object, keys in insertion order. */
    static final class Obj {
        private final StringBuilder b = new StringBuilder("{");
        private boolean empty = true;

        private StringBuilder key(String k) {
            if (!empty) {
                b.append(',');
            }
            empty = false;
            return b.append(quote(k)).append(':');
        }

        Obj put(String k, String v) {
            key(k).append(quote(v));
            return this;
        }

        Obj put(String k, long v) {
            key(k).append(v);
            return this;
        }

        Obj put(String k, boolean v) {
            key(k).append(v);
            return this;
        }

        Obj put(String k, List<String> values) {
            StringBuilder out = key(k).append('[');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(quote(values.get(i)));
            }
            out.append(']');
            return this;
        }

        Obj put(String k, Map<String, String> values) {
            StringBuilder out = key(k).append('{');
            boolean first = true;
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
            }
            out.append('}');
            return this;
        }

        @Override
        public String toString() {
            return b + "}";
        }
    }
}
