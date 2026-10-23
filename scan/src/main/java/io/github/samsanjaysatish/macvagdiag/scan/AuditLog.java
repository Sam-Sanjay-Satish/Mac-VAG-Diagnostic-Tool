package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.diag.DiagResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The audit log (root rule 8): an append-only JSON-lines file, one object per line, flushed per line. Records every
 * request sent to the car (written <b>before</b> it is sent: if the line can't be written the request isn't sent),
 * every request the guard rejected, every result, keep-alive, channel open/close, every refusal by a limit, and
 * every MCP tool call. Every line has {@code time}, {@code event} and, where there is one, {@code caller}.
 *
 * <p>The file contains raw responses, so it can contain the VIN; it lives in the user's app data directory,
 * never in the repo. The file is only ever opened with {@code APPEND}.
 */
final class AuditLog implements AutoCloseable {

    private final Writer out;
    private final TimeSource time;
    private final boolean demo;
    private final String where;
    private boolean closed;

    private AuditLog(Writer out, TimeSource time, boolean demo, String where) {
        this.out = Objects.requireNonNull(out, "out");
        this.time = Objects.requireNonNull(time, "time");
        this.demo = demo;
        this.where = where;
    }

    /** Opens {@code path} for appending, creating it and its directories if needed. */
    static AuditLog append(Path path, TimeSource time, boolean demo) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            return new AuditLog(w, time, demo, path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open the audit log " + path, e);
        }
    }

    /** A log written to {@code out} (tests). */
    static AuditLog to(Writer out, TimeSource time, boolean demo) {
        return new AuditLog(out, time, demo, "(writer)");
    }

    /**
     * Writes one line.
     *
     * @throws UncheckedIOException if it can't be written (or the log is closed); for a request record this
     *     stops the request from being sent
     */
    synchronized void write(String event, Consumer<Json.Obj> fields) {
        if (closed) {
            throw new UncheckedIOException(new IOException("the audit log " + where + " is closed"));
        }
        Json.Obj o = Json.object().put("time", time.now().toString()).put("event", event);
        if (demo) {
            o.put("demo", true);
        }
        fields.accept(o);
        try {
            out.write(o.toString());
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write to the audit log " + where, e);
        }
    }

    private static void context(Json.Obj o, ScanContext ctx, ModuleAddress module) {
        o.put("caller", ctx == null ? "unknown" : ctx.caller.label());
        if (ctx != null) {
            o.put("op", ctx.id);
        }
        if (module != null) {
            o.put("module", module.hex());
        }
    }

    void requestSent(ScanContext ctx, ModuleAddress module, String link, String protocol, String requestHex) {
        write("request", o -> {
            context(o, ctx, module);
            o.put("link", link).put("protocol", protocol).put("request", requestHex);
        });
    }

    void rejected(ScanContext ctx, ModuleAddress module, String link, String protocol, String requestHex, String reason) {
        write("rejected", o -> {
            context(o, ctx, module);
            o.put("link", link).put("protocol", protocol).put("request", requestHex).put("reason", reason);
        });
    }

    void result(ScanContext ctx, ModuleAddress module, DiagOp op, int attempt, DiagResult<?> r) {
        write("result", o -> {
            context(o, ctx, module);
            o.put("operation", op.label).put("attempt", attempt).put("outcome", DiagMapper.outcome(r));
            DiagMapper.nrc(r).ifPresent(n -> o.put("nrc", n));
            o.put("responses", DiagMapper.responses(r));
            if (!r.isSuccess()) {
                o.put("detail", r.message());
            }
        });
    }

    void connect(ScanContext ctx, ModuleAddress module, ModuleLink link, int attempt, String outcome, String detail) {
        write("connect", o -> {
            context(o, ctx, module);
            o.put("link", link.describe()).put("attempt", attempt).put("outcome", outcome);
            if (detail != null) {
                o.put("detail", detail);
            }
        });
    }

    void channelKeepAlive(ScanContext ctx, ModuleAddress module, boolean alive) {
        write("keep-alive", o -> {
            context(o, ctx, module);
            o.put("alive", alive);
        });
    }

    void closed(ScanContext ctx, ModuleAddress module) {
        write("close", o -> context(o, ctx, module));
    }

    /** A refusal by scan itself (limit, back-off, adapter, invalid state); nothing was sent. */
    void refused(Caller caller, String opId, ModuleAddress module, String reason, LimitHit hit, String message) {
        write("refused", o -> {
            o.put("caller", caller == null ? "unknown" : caller.label());
            if (opId != null) {
                o.put("op", opId);
            }
            if (module != null) {
                o.put("module", module.hex());
            }
            o.put("reason", reason);
            if (hit != null) {
                o.put("limit", hit.limit().name()).put("retryAfterMs", hit.retryAfter().toMillis());
            }
            o.put("message", message);
        });
    }

    void toolCall(ToolCallRecord call) {
        write("tool-call", o -> o.put("caller", "mcp:" + call.tool()).put("tool", call.tool())
                .put("arguments", call.arguments()).put("outcome", call.outcome()).put("summary", call.summary()));
    }

    void service(String what, String detail) {
        write("service", o -> o.put("what", what).put("detail", detail));
    }

    void scan(ScanContext ctx, String what, String detail) {
        write("scan", o -> {
            context(o, ctx, null);
            o.put("what", what).put("detail", detail);
        });
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot close the audit log " + where, e);
        }
    }
}
