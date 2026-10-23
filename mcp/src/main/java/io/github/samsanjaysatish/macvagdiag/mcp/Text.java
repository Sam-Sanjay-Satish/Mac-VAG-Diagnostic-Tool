package io.github.samsanjaysatish.macvagdiag.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Safe text for untrusted input (tool names, argument keys and values from an AI) in messages and the audit log. */
final class Text {

    static final int MAX_QUOTED = 40;

    private Text() {
    }

    /** {@code "abc"}, cut to {@value #MAX_QUOTED} characters, control and odd characters as {@code ?}. */
    static String quote(String untrusted) {
        if (untrusted == null) {
            return "(none)";
        }
        String cut = untrusted.length() > MAX_QUOTED ? untrusted.substring(0, MAX_QUOTED) + "..." : untrusted;
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < cut.length(); i++) {
            char c = cut.charAt(i);
            boolean odd = c < 0x20 || c == 0x7F || c == '"' || c == '\\' || Character.isSurrogate(c)
                    || Character.getType(c) == Character.FORMAT || Character.isWhitespace(c) && c != ' ';
            b.append(odd ? '?' : c);
        }
        return b.append('"').toString();
    }

    /** The JSON type of a decoded argument value, for messages. */
    static String jsonType(Object value) {
        return switch (value) {
            case null -> "null";
            case String s -> "string";
            case Boolean b -> "boolean";
            case Number n -> "number";
            case Map<?, ?> m -> "object";
            case List<?> l -> "array";
            default -> "value";
        };
    }

    /**
     * Arguments as strings for {@link io.github.samsanjaysatish.macvagdiag.scan.ToolCallRecord} (which bounds their
     * number and length; the audit log escapes them). Non-strings are written with their JSON type, e.g.
     * {@code (number) 8}.
     */
    static Map<String, String> auditArguments(Map<String, Object> args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            Object v = e.getValue();
            out.put(String.valueOf(e.getKey()), v instanceof String s ? s : "(" + jsonType(v) + ") " + v);
        }
        return out;
    }
}
