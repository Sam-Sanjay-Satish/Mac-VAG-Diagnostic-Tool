package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One MCP tool call, for the audit log (root rule 8): {@link ScanService#recordToolCall}. The arguments come from
 * an AI and are untrusted: they are bounded here and escaped when written.
 *
 * @param tool the tool name, e.g. {@code read_fault_codes}
 * @param arguments the arguments as given (insertion order kept); at most {@value #MAX_ARGUMENTS}, each value
 *     cut to {@value #MAX_VALUE_LENGTH} characters
 * @param outcome short, e.g. {@code ok}, {@code error}, {@code rejected}
 * @param summary a one-line result summary, cut to {@value #MAX_SUMMARY_LENGTH} characters
 */
public record ToolCallRecord(String tool, Map<String, String> arguments, String outcome, String summary) {

    public static final int MAX_ARGUMENTS = 20;
    public static final int MAX_KEY_LENGTH = 64;
    public static final int MAX_VALUE_LENGTH = 200;
    public static final int MAX_SUMMARY_LENGTH = 1000;
    static final String TRUNCATED = "...(truncated)";

    public ToolCallRecord {
        if (tool == null || !Caller.TOOL_NAME.matcher(tool).matches()) {
            throw new IllegalArgumentException("tool name must match " + Caller.TOOL_NAME.pattern() + ": "
                    + Json.preview(tool));
        }
        Objects.requireNonNull(arguments, "arguments");
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : arguments.entrySet()) {
            if (copy.size() == MAX_ARGUMENTS) {
                copy.put("(more)", (arguments.size() - MAX_ARGUMENTS) + " further argument(s) not recorded");
                break;
            }
            copy.put(cut(String.valueOf(e.getKey()), MAX_KEY_LENGTH), cut(String.valueOf(e.getValue()), MAX_VALUE_LENGTH));
        }
        arguments = Collections.unmodifiableMap(copy);
        outcome = cut(Objects.requireNonNull(outcome, "outcome"), MAX_KEY_LENGTH);
        summary = summary == null ? "" : cut(summary, MAX_SUMMARY_LENGTH);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + TRUNCATED;
    }
}
