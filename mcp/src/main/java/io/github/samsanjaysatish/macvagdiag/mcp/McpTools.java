package io.github.samsanjaysatish.macvagdiag.mcp;

import io.github.samsanjaysatish.macvagdiag.scan.Caller;
import io.github.samsanjaysatish.macvagdiag.scan.InvalidLiveValueException;
import io.github.samsanjaysatish.macvagdiag.scan.InvalidModuleAddressException;
import io.github.samsanjaysatish.macvagdiag.scan.LiveDataCatalog;
import io.github.samsanjaysatish.macvagdiag.scan.LiveDataRecording;
import io.github.samsanjaysatish.macvagdiag.scan.LiveDataSnapshot;
import io.github.samsanjaysatish.macvagdiag.scan.LiveValueId;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAddress;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAvailability;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleResult;
import io.github.samsanjaysatish.macvagdiag.scan.ScanRefusedException;
import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import io.github.samsanjaysatish.macvagdiag.scan.SessionStatus;
import io.github.samsanjaysatish.macvagdiag.scan.ToolCallRecord;
import io.github.samsanjaysatish.macvagdiag.scan.VehicleInfo;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The v1 tools: a thin front end over {@link ScanService}. Each call goes:
 * <ol>
 *   <li><b>Validate</b> the untrusted arguments: exact key set, types, {@link ModuleAddress#parse}, then the input
 *       schema as a backstop. Anything wrong is refused <b>before</b> {@code ScanService} is called.</li>
 *   <li><b>Call</b> scan as {@link Caller#mcp(String) mcp:&lt;tool&gt;}. scan enforces the allowlist, rate limits,
 *       budgets and sessions whatever the model asks.</li>
 *   <li><b>Shape</b> the result as JSON ({@link ToolOutput}). There is no VIN to shape: it is never read.</li>
 *   <li><b>Audit</b> the call with {@link ScanService#recordToolCall}, whatever the outcome.</li>
 * </ol>
 *
 * <p><b>There is no raw-send, clear, write, coding, adaptation or actuator tool, and there must never be one</b>
 * (root {@code CLAUDE.md}). {@link #NAMES} is pinned by {@code ToolListTest}; adding a tool means changing that test
 * on purpose.
 *
 * <p>Errors come back as tool results with {@code isError: true} and a message that tells the model what to do next,
 * so it can correct itself (MCP: tool execution errors, not protocol errors).
 */
final class McpTools {

    private static final Logger log = LoggerFactory.getLogger(McpTools.class);

    static final String GET_SESSION_STATUS = "get_session_status";
    static final String LIST_MODULES = "list_modules";
    static final String READ_FAULT_CODES = "read_fault_codes";
    static final String GET_VEHICLE_INFO = "get_vehicle_info";
    static final String LIST_AVAILABLE_DATA = "list_available_data";
    static final String READ_LIVE_DATA = "read_live_data";
    static final String RECORD_LIVE_DATA = "record_live_data";

    /** Every tool, in the order they are listed. Pinned by {@code ToolListTest}. */
    static final List<String> NAMES = List.of(GET_SESSION_STATUS, LIST_MODULES, READ_FAULT_CODES, GET_VEHICLE_INFO,
            LIST_AVAILABLE_DATA, READ_LIVE_DATA, RECORD_LIVE_DATA);

    /** Audit-log name for a call to a tool that doesn't exist (the requested name goes in the arguments). */
    static final String UNKNOWN_TOOL = "unknown_tool";

    static final String MODULE_ARG = "module";
    static final String QUERY_ARG = "query";
    static final String GROUPS_ARG = "groups";
    static final String VALUES_ARG = "values";
    static final String SECONDS_ARG = "seconds";
    static final int MAX_QUERY_LENGTH = 40;
    static final int MAX_GROUPS_ARG = 20;

    private static final String READ_ONLY_NOTE = " Read-only: nothing here can clear codes or change anything in the car.";
    private static final String DEMO_NOTE = " \"demo\": true means the built-in simulated car: all data is made up.";

    private final ScanService scan;
    private final McpJsonMapper json = McpJsonDefaults.getMapper();
    private final JsonSchemaValidator schemaValidator = McpJsonDefaults.getSchemaValidator();
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** One tool: its definition, allowed argument names and what it does. */
    private record Tool(McpSchema.Tool definition, Set<String> arguments, Body body) {
    }

    @FunctionalInterface
    private interface Body {
        Outcome run(Map<String, Object> args) throws InterruptedException;
    }

    /** What a successful call returns: the structured result and a one-line summary for the audit log. */
    private record Outcome(Map<String, Object> structured, String summary) {
    }

    /** Bad arguments: refused before scan is called. */
    private static final class InvalidArguments extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidArguments(String message) {
            super(message);
        }
    }

    McpTools(ScanService scan) {
        this.scan = Objects.requireNonNull(scan, "scan");

        add(GET_SESSION_STATUS, "Session status",
                "Is the tool ready to talk to the car? Returns the CAN adapter state (CONNECTED / DISCONNECTED with "
                        + "detail) and the ignition and engine state (UNKNOWN for now, with the reason). Sends nothing "
                        + "to the car. Call this first." + DEMO_NOTE,
                ToolSchemas.noArguments(), ToolSchemas.sessionStatusOutput(), Set.of(), args -> {
                    SessionStatus s = scan.sessionStatus(Caller.mcp(GET_SESSION_STATUS));
                    return new Outcome(ToolOutput.sessionStatus(s), "adapter " + s.adapter() + ", ignition "
                            + s.ignition().state() + ", engine " + s.engine().state() + (s.demo() ? ", demo" : ""));
                });

        add(LIST_MODULES, "List modules",
                "Which of the known control modules answer, and over which protocol (KWP2000 over TP 2.0 or UDS over "
                        + "ISO-TP). Probes each module unless the last check is under a minute old (each entry has "
                        + "its timestamp). Use it before read_fault_codes to pick modules." + READ_ONLY_NOTE + DEMO_NOTE,
                ToolSchemas.noArguments(), ToolSchemas.moduleListOutput(), Set.of(), args -> {
                    List<ModuleAvailability> modules = scan.listModules(Caller.mcp(LIST_MODULES));
                    Map<String, Object> out = ToolOutput.moduleList(modules, scan.isDemo());
                    return new Outcome(out, modules.size() + " modules: " + out.get("counts"));
                });

        add(READ_FAULT_CODES, "Read fault codes",
                "Reads one module's stored fault codes with their status byte, status flags and freeze frame "
                        + "(environment data, raw records with the request they came from; not decoded yet). Codes "
                        + "are as the module reports them: VAG 5-digit for KWP2000, SAE (P0401) for UDS. No "
                        + "descriptions or labels are available: report codes as they are and don't guess what they "
                        + "mean." + READ_ONLY_NOTE + DEMO_NOTE,
                ToolSchemas.moduleArgument(), ToolSchemas.faultCodesOutput(), Set.of(MODULE_ARG), args -> {
                    ModuleAddress module = module(args);
                    ModuleResult r = scan.readFaultCodes(Caller.mcp(READ_FAULT_CODES), module);
                    return new Outcome(ToolOutput.faultCodes(r, scan.isDemo()),
                            "module " + module.hex() + " " + module.name() + ": " + r.status() + ", "
                                    + r.faults().size() + " fault(s)");
                });

        add(GET_VEHICLE_INFO, "Vehicle info",
                "The identification (part number, software version, hardware number, system name, coding, raw "
                        + "identification records) of the engine (01), instruments (17) and gateway (19), and the "
                        + "mileage: the odometer of the instruments (17), in km. The VIN is not read."
                        + READ_ONLY_NOTE + DEMO_NOTE,
                ToolSchemas.noArguments(), ToolSchemas.vehicleInfoOutput(), Set.of(), args -> {
                    VehicleInfo v = scan.vehicleInfo(Caller.mcp(GET_VEHICLE_INFO));
                    return new Outcome(ToolOutput.vehicleInfo(v),
                            "mileage " + v.mileage().map(m -> m.kilometres() + " km from " + m.source().hex())
                                    .orElse("not read") + ", " + v.modules().size() + " module(s) asked");
                });

        add(LIST_AVAILABLE_DATA, "List available live data",
                "Which live values (measuring blocks) a module can be read for, so you can choose what to read. "
                        + "Without filters: the group list (number, title, field count). With \"groups\" (e.g. [1, 11]) "
                        + "or a \"query\" (e.g. \"boost\", matched against titles and field names): the fields, each "
                        + "with its value ID (e.g. \"01:011.3\"), name and spec ranges from the label file. Names and "
                        + "specs come from the module's label file (labelSource); without one, groups are found by "
                        + "asking the module (source PROBE_INCOMPLETE: call again to continue) and fields only have the "
                        + "module's generic quantity and unit. Groups holding the VIN or immobilizer identity are "
                        + "withheld and can't be read. Only KWP2000 modules (01, 02, 03, 08, 09, 19, 44) have "
                        + "measuring blocks; UDS modules return NOT_SUPPORTED. Call this before read_live_data."
                        + READ_ONLY_NOTE + DEMO_NOTE,
                ToolSchemas.availableDataArguments(), ToolSchemas.availableDataOutput(),
                Set.of(MODULE_ARG, QUERY_ARG, GROUPS_ARG), args -> {
                    ModuleAddress module = module(args);
                    LiveDataCatalog c = scan.liveDataCatalog(Caller.mcp(LIST_AVAILABLE_DATA), module);
                    Map<String, Object> out = ToolOutput.availableData(c, query(args), groups(args), scan.isDemo());
                    return new Outcome(out, "module " + module.hex() + ": " + c.source() + ", " + c.groups().size()
                            + " group(s), " + c.fieldCount() + " field(s)" + c.labelSource().map(l -> ", labels " + l)
                            .orElse(""));
                });

        add(READ_LIVE_DATA, "Read live data",
                "Reads chosen live values once: 1 to " + ScanService.MAX_LIVE_VALUES + " value IDs from one "
                        + "module's list_available_data, from at most " + ScanService.MAX_LIVE_GROUPS + " groups (each "
                        + "group is one request; its four fields come together). Each value has its number, unit, "
                        + "name, the label's spec ranges and the raw bytes. Specs often depend on conditions (idle, "
                        + "warm, full load): ask the user to set those up first if needed. State EMPTY means the module "
                        + "sent no value for that field (it may not measure it)." + READ_ONLY_NOTE + DEMO_NOTE,
                ToolSchemas.readLiveDataArguments(), ToolSchemas.liveDataOutput(), Set.of(VALUES_ARG), args -> {
                    List<LiveValueId> ids = valueIds(args);
                    LiveDataSnapshot r = scan.readLiveData(Caller.mcp(READ_LIVE_DATA), ids);
                    return new Outcome(ToolOutput.liveData(r, scan.isDemo()), "module " + r.module().hex() + ": "
                            + r.status() + ", " + r.values().size() + " value(s), " + r.requests() + " request(s)");
                });

        add(RECORD_LIVE_DATA, "Record live data",
                "Reads chosen live values repeatedly for 1 to 120 seconds (same value rules as read_live_data; "
                        + "one group request every 250 ms or slower) and returns a summary per value: samples, "
                        + "min / max / average, first / last, when min and max were seen, and notable events (samples "
                        + "outside the label's range, empty or unread samples). Every sample is in a CSV file "
                        + "(\"file\") for later analysis. Blocks other tools for the whole duration. Use it while the "
                        + "user holds a condition (e.g. 2500 rpm, a test drive): ask them first and wait until they "
                        + "say it is set up." + READ_ONLY_NOTE + DEMO_NOTE,
                ToolSchemas.recordLiveDataArguments(), ToolSchemas.recordingOutput(), Set.of(VALUES_ARG, SECONDS_ARG),
                args -> {
                    List<LiveValueId> ids = valueIds(args);
                    Duration d = Duration.ofSeconds(((Number) args.get(SECONDS_ARG)).longValue());
                    LiveDataRecording r = scan.recordLiveData(Caller.mcp(RECORD_LIVE_DATA), ids, d);
                    return new Outcome(ToolOutput.recording(r, scan.isDemo()), "module " + r.module().hex() + ": "
                            + r.status() + ", " + r.rounds() + " round(s) in " + r.recorded().toMillis() + " ms, "
                            + r.requests() + " request(s)");
                });

        if (!List.copyOf(tools.keySet()).equals(NAMES)) {
            throw new IllegalStateException("tool set drifted from NAMES: " + tools.keySet());
        }
    }

    private void add(String name, String title, String description, Map<String, Object> input,
            Map<String, Object> output, Set<String> arguments, Body body) {
        McpSchema.Tool definition = McpSchema.Tool.builder(name, input)
                .title(title)
                .description(description)
                .outputSchema(output)
                .annotations(McpSchema.ToolAnnotations.builder()
                        .title(title)
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        tools.put(name, new Tool(definition, Set.copyOf(arguments), body));
    }

    /** The SDK registrations, one per tool. */
    List<SyncToolSpecification> specifications() {
        List<SyncToolSpecification> specs = new ArrayList<>();
        for (Tool t : tools.values()) {
            specs.add(SyncToolSpecification.builder()
                    .tool(t.definition())
                    .callHandler((exchange, request) -> call(request.name(), request.arguments()))
                    .build());
        }
        return specs;
    }

    /** The tool definitions, as {@code tools/list} shows them. */
    List<McpSchema.Tool> definitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }

    /**
     * Runs one tool call: validate, call scan, shape, audit. Never throws; every failure is an {@code isError}
     * result with a message for the model.
     */
    CallToolResult call(String name, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        Tool tool = tools.get(name);
        if (tool == null) {
            // The SDK answers unknown tools itself (McpServlet audits those); this is for direct callers.
            String message = "unknown tool " + Text.quote(name) + "; the tools are " + String.join(", ", NAMES)
                    + ". There is no tool to clear codes, send raw requests or change anything in the car.";
            audit(UNKNOWN_TOOL, withName(name, args), "rejected", message);
            return error(message);
        }
        try {
            validate(tool, args);
            Outcome outcome = tool.body().run(args);
            audit(name, args, "ok", outcome.summary());
            return CallToolResult.builder()
                    .addTextContent(json.writeValueAsString(outcome.structured()))
                    .structuredContent(outcome.structured())
                    .build();
        } catch (InvalidArguments e) {
            audit(name, args, "rejected", e.getMessage());
            return error("invalid arguments: " + e.getMessage() + " Nothing was sent to the car.");
        } catch (InvalidLiveValueException e) {
            // scan checked the IDs against the module's catalog before sending anything.
            audit(name, args, "rejected", e.getMessage());
            return error("invalid arguments: " + e.getMessage() + ". Nothing was sent to the car.");
        } catch (ScanRefusedException e) {
            String message = refusal(e);
            audit(name, args, "refused", e.reason() + ": " + e.getMessage());
            return error(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            audit(name, args, "error", "interrupted");
            return error("interrupted: macvagdiag is shutting down. Ask the user to restart it, then retry.");
        } catch (IOException | RuntimeException e) {
            log.warn("{} failed", name, e);
            audit(name, args, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return error("internal error in macvagdiag (" + e.getClass().getSimpleName() + "). Tell the user; "
                    + "the app log has the details. Don't retry in a loop.");
        }
    }

    // ------------------------------------------------------------------ validation

    private void validate(Tool tool, Map<String, Object> args) throws InvalidArguments {
        String name = tool.definition().name();
        Set<String> unknown = new TreeSet<>(args.keySet());
        unknown.removeAll(tool.arguments());
        if (!unknown.isEmpty()) {
            List<String> quoted = unknown.stream().limit(5).map(Text::quote).toList();
            throw new InvalidArguments("unknown argument(s) " + String.join(", ", quoted)
                    + (unknown.size() > 5 ? " and " + (unknown.size() - 5) + " more" : "") + "; " + usage(name));
        }
        if (name.equals(READ_FAULT_CODES) || name.equals(LIST_AVAILABLE_DATA)) {
            Object module = args.get(MODULE_ARG);
            if (module == null) {
                throw new InvalidArguments("missing \"module\"; " + usage(name));
            }
            if (!(module instanceof String text)) {
                throw new InvalidArguments("\"module\" must be a string of two hex digits like \"08\", not a "
                        + Text.jsonType(module) + "; " + usage(name));
            }
            try {
                ModuleAddress.parse(text);
            } catch (InvalidModuleAddressException e) {
                throw new InvalidArguments(e.getMessage() + ". Call list_modules to see which modules respond.");
            }
        }
        if (name.equals(LIST_AVAILABLE_DATA)) {
            Object q = args.get(QUERY_ARG);
            if (args.containsKey(QUERY_ARG) && !(q instanceof String text && !text.isBlank()
                    && text.length() <= MAX_QUERY_LENGTH)) {
                throw new InvalidArguments("\"query\" must be a non-empty string of at most " + MAX_QUERY_LENGTH
                        + " characters; " + usage(name));
            }
            if (args.containsKey(GROUPS_ARG)) {
                if (!(args.get(GROUPS_ARG) instanceof List<?> list) || list.isEmpty() || list.size() > MAX_GROUPS_ARG) {
                    throw new InvalidArguments("\"groups\" must be a list of 1 to " + MAX_GROUPS_ARG
                            + " group numbers; " + usage(name));
                }
                for (Object g : list) {
                    if (wholeNumber(g) == null || wholeNumber(g) < 1 || wholeNumber(g) > LiveValueId.MAX_GROUP) {
                        throw new InvalidArguments("\"groups\" entries must be whole numbers 1..255, not "
                                + Text.quote(String.valueOf(g)) + "; " + usage(name));
                    }
                }
            }
        }
        if (name.equals(READ_LIVE_DATA) || name.equals(RECORD_LIVE_DATA)) {
            Object values = args.get(VALUES_ARG);
            if (!(values instanceof List<?> list) || list.isEmpty() || list.size() > ScanService.MAX_LIVE_VALUES) {
                throw new InvalidArguments("\"values\" must be a list of 1 to " + ScanService.MAX_LIVE_VALUES
                        + " value IDs; " + usage(name));
            }
            for (Object v : list) {
                if (!(v instanceof String text) || LiveValueId.tryParse(text).isEmpty()) {
                    throw new InvalidArguments("value IDs look like \"01:011.3\" (module : group . field), not "
                            + Text.quote(String.valueOf(v)) + "; take them from list_available_data. " + usage(name));
                }
            }
        }
        if (name.equals(RECORD_LIVE_DATA)) {
            Long seconds = wholeNumber(args.get(SECONDS_ARG));
            if (seconds == null || seconds < ScanService.MIN_RECORDING.toSeconds()
                    || seconds > ScanService.MAX_RECORDING.toSeconds()) {
                throw new InvalidArguments("\"seconds\" must be a whole number from "
                        + ScanService.MIN_RECORDING.toSeconds() + " to " + ScanService.MAX_RECORDING.toSeconds() + "; "
                        + usage(name));
            }
        }
        // Backstop: the published input schema must accept it too (the hand checks above are stricter).
        var result = schemaValidator.validate(tool.definition().inputSchema(), args);
        if (!result.valid()) {
            throw new InvalidArguments("arguments don't match the input schema; " + usage(name));
        }
    }

    private static ModuleAddress module(Map<String, Object> args) {
        return ModuleAddress.parse((String) args.get(MODULE_ARG)); // validated before
    }

    /** A JSON whole number (an integer, or a number with no fraction), else null. */
    private static Long wholeNumber(Object o) {
        if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
            return ((Number) o).longValue();
        }
        if (o instanceof java.math.BigInteger b && b.bitLength() < 63) {
            return b.longValue();
        }
        return null;
    }

    private static java.util.Optional<String> query(Map<String, Object> args) {
        return java.util.Optional.ofNullable((String) args.get(QUERY_ARG)).map(String::strip);
    }

    private static List<Integer> groups(Map<String, Object> args) {
        Object g = args.get(GROUPS_ARG);
        if (!(g instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(x -> wholeNumber(x).intValue()).distinct().toList();
    }

    private static List<LiveValueId> valueIds(Map<String, Object> args) {
        return ((List<?>) args.get(VALUES_ARG)).stream()
                .map(v -> LiveValueId.tryParse((String) v).orElseThrow()).toList(); // validated before
    }

    private static String usage(String tool) {
        return switch (tool) {
            case READ_FAULT_CODES -> "read_fault_codes takes exactly {\"module\": \"<two hex digits>\"}, e.g. "
                    + "{\"module\": \"01\"}.";
            case LIST_AVAILABLE_DATA -> "list_available_data takes {\"module\": \"01\"} and optionally "
                    + "\"groups\": [1, 11] and/or \"query\": \"boost\".";
            case READ_LIVE_DATA -> "read_live_data takes {\"values\": [\"01:001.1\", \"01:011.3\"]}.";
            case RECORD_LIVE_DATA -> "record_live_data takes {\"values\": [\"01:011.3\"], \"seconds\": 30}.";
            default -> tool + " takes no arguments: call it with {}.";
        };
    }

    // ------------------------------------------------------------------ errors

    /** scan's refusal, plus what the model should do next. scan's messages already say what happened. */
    static String refusal(ScanRefusedException e) {
        String next = switch (e.reason()) {
            case ADAPTER_NOT_CONNECTED -> "Ask the user to plug in the CANable and switch the ignition on, then "
                    + "call get_session_status to check before retrying.";
            case LIMIT_EXCEEDED -> "Wait " + e.retryAfter().map(d -> "at least " + ToolOutput.ceilSeconds(d) + " s")
                    .orElse("a little") + " before calling a tool that talks to the car again. Don't retry in a loop.";
            case MODULE_NOT_RESPONDING -> "It may not be installed, or the ignition may be off. Call list_modules to "
                    + "see which modules respond; retry this one only after "
                    + e.retryAfter().map(d -> ToolOutput.ceilSeconds(d) + " s").orElse("a while") + ".";
            case SCAN_IN_PROGRESS -> "An Auto-Scan started from the web page is using the car; wait for it to "
                    + "finish, then retry.";
            case SERVICE_CLOSED -> "macvagdiag is shutting down. Ask the user to restart it.";
        };
        return "refused, nothing was sent to the car: " + e.getMessage() + ". " + next;
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    // ------------------------------------------------------------------ audit

    /** Writes the call to scan's audit log. A failure to write is logged; the call itself already happened. */
    void audit(String tool, Map<String, Object> args, String outcome, String summary) {
        try {
            scan.recordToolCall(new ToolCallRecord(tool, Text.auditArguments(args), outcome, summary));
        } catch (RuntimeException e) {
            log.error("could not audit-log MCP tool call {} ({})", tool, outcome, e);
        }
    }

    static Map<String, Object> withName(String name, Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("(tool)", name);
        out.putAll(args);
        return out;
    }
}
