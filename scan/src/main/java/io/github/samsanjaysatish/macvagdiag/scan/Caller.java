package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Who asked for an operation. Every {@link ScanService} call takes one, and it is written on every audit-log line
 * as {@code web}, {@code cli} or {@code mcp:<tool>}.
 */
public final class Caller {

    public enum Kind { WEB, MCP, CLI }

    /** MCP tool names: a lower-case letter, then lower-case letters, digits or underscores; at most 64 characters. */
    static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private static final Caller WEB = new Caller(Kind.WEB, null);
    private static final Caller CLI = new Caller(Kind.CLI, null);

    private final Kind kind;
    private final String tool; // MCP only

    private Caller(Kind kind, String tool) {
        this.kind = kind;
        this.tool = tool;
    }

    /** The local web page / REST API. */
    public static Caller web() {
        return WEB;
    }

    /** A command-line tool. */
    public static Caller cli() {
        return CLI;
    }

    /**
     * An MCP tool call, e.g. {@code mcp("read_fault_codes")}.
     *
     * @throws IllegalArgumentException if {@code tool} isn't a valid tool name (see {@link #TOOL_NAME})
     */
    public static Caller mcp(String tool) {
        if (tool == null || !TOOL_NAME.matcher(tool).matches()) {
            throw new IllegalArgumentException("MCP tool name must match " + TOOL_NAME.pattern() + ": "
                    + Json.preview(tool));
        }
        return new Caller(Kind.MCP, tool);
    }

    public Kind kind() {
        return kind;
    }

    /** The MCP tool name, for {@link Kind#MCP}. */
    public Optional<String> tool() {
        return Optional.ofNullable(tool);
    }

    /** As written in the audit log: {@code web}, {@code cli}, {@code mcp:read_fault_codes}. */
    public String label() {
        return kind == Kind.MCP ? "mcp:" + tool : kind.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Caller c && kind == c.kind && Objects.equals(tool, c.tool);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, tool);
    }

    @Override
    public String toString() {
        return label();
    }
}
