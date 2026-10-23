# mcp

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** v1 built (2026-10-23): the four read-only tools over Streamable HTTP, hosted in-process by `server`. 109 tests, `./gradlew :mcp:build` green. Pending: the manual check with a real AI client against demo mode (needs `server` to mount the endpoint). **Package:** `io.github.samsanjaysatish.macvagdiag.mcp`
**Depends on:** `scan` (`api`) + the official MCP Java SDK 2.1.0 (`libs.mcp.bom` / `libs.mcp`: mcp-core + mcp-json-jackson3, MIT; approved 2026-10-22) + `jakarta.servlet-api` as `compileOnly` (Tomcat provides it at runtime). No Spring here: only `server` knows Spring. **Used by:** `server`.

## Purpose
Lets AI agents (Claude Desktop, Claude Code, custom agents) use the tool **read-only**: read fault codes, choose live data to read (v2), look at results and decide on further tests. VCDS can't be driven by a model; this can.

This is a **thin front end over `ScanService`**. It adds tool definitions, argument validation and JSON shaping, nothing else. No layer below `scan` knows MCP exists.

## Hosting (decided 2026-10-22)
- **In-process Streamable HTTP inside the `server` process**, so the web UI and the AI share one `ScanService`, one request queue and one adapter. `settings.gradle.kts` lists `mcp` below `server`.
- **No stdio entry point** (user decision). If one is ever wanted it can't run while `server` holds the adapter, and nothing but protocol messages may go to stdout.
- Transport: the SDK's `HttpServletStreamableServerTransportProvider` (servlet, mcp-core 2.1.0), wrapped in our own `McpServlet`. Not the Spring AI starter.

## Endpoint contract (what `server` mounts)
```java
McpEndpoint mcp = McpEndpoint.create(scanService, McpOptions.defaults()); // once at startup
HttpServlet servlet = mcp.servlet();   // mount at mcp.path() == McpEndpoint.PATH == "/mcp", async support ON
mcp.close();                           // on shutdown, before scanService.close(); idempotent
```
- `public final class McpEndpoint implements AutoCloseable`: `static create(ScanService, McpOptions)`, `HttpServlet servlet()`, `String path()`, `void close()`, constant `PATH = "/mcp"`.
- `public record McpOptions(boolean maskVin)`: `defaults()` (masked), `withMaskVin(boolean)`. User config only; no tool argument touches it.
- The endpoint doesn't own the `ScanService`: `close()` ends MCP sessions and the transport's timers only.
- The servlet needs **async support** (the SDK answers requests as server-sent events on an `AsyncContext`); Spring Boot's `ServletRegistrationBean` has it on by default. The SDK checks that the request URI ends with `/mcp`.
- Clients: `http://localhost:<port>/mcp`, e.g. `claude mcp add --transport http macvagdiag http://localhost:8080/mcp`.

### What the servlet does before the SDK sees a request (`McpServlet`)
1. **Host / Origin** (`LocalhostOnly`), every method: `Host` required, exactly one, `localhost` / `127.0.0.1` / `[::1]` with an optional port 1–65535, else 421. `Origin` optional (non-browser clients send none), but if present exactly one `http(s)://` + the same hosts, else 403 (`null` is refused). Defence in depth behind `server`'s own checks; the same validator is also passed to the SDK's `httpHeaderValidator` hook. The SDK's `DefaultServerTransportSecurityValidator` isn't used: its `host:*` patterns are prefix matches (`localhost:evil` passes) and it ignores repeated headers.
2. **POST bodies are bounded** to 256 KiB (`MAX_BODY_BYTES`, 413 above), read once and replayed to the SDK.
3. **Unknown tools are audited.** The SDK answers a `tools/call` for an unknown name itself without calling us, so the servlet peeks at the body and writes those calls to the audit log as tool `unknown_tool` with the requested name under `(tool)`.

## Tools (v1)
| Tool | Purpose | Version |
|---|---|---|
| `get_session_status` | Adapter connected? Ignition on? Engine running? (ignition/engine `UNKNOWN` for now, see scan) | v1 ✔ |
| `list_modules` | Known modules and whether each responds | v1 ✔ |
| `read_fault_codes(module)` | Codes, status, freeze frame as structured JSON | v1 ✔ |
| `get_vehicle_info` | ECU part numbers, software versions, VIN (masked by default) | v1 ✔ |
| `list_available_data(module)` | Which live values can be read | v2 |
| `read_live_data(module, values)` | One snapshot of chosen values | v2 |
| `record_live_data(module, values, seconds)` | Record over time; return a summary + file path for the full data | v2 |

All four are annotated `readOnlyHint: true`, `destructiveHint: false`, `idempotentHint: true`, `openWorldHint: false`. Server `instructions` tell the model it's read-only, to start with `get_session_status`, to follow refusal messages, that physical conditions need the human, that there are no fault-code descriptions, and what `demo: true` means.

### Schemas summary (`ToolSchemas`, JSON Schema 2020-12)
- **Inputs:** `additionalProperties: false` everywhere. `read_fault_codes`: `{"module": string}`, required, `enum` of the known addresses (`01 02 03 08 09 15 16 17 19 25 44 46 56`), description lists their names. The other three take `{}`.
- **Outputs:** one closed schema per tool (`additionalProperties: false`) with shared `$defs`; the SDK validates every result against it, so a mismatch shows up as a failing test.
  - **value** `{id, value, unit, timestamp}` (+ optional `detail`, `source`): adapter/ignition/engine state, VIN, identification fields (`partNumber`, `softwareVersion`, `hardwareNumber`, `systemName`, `coding`, `vin`), decoded freeze-frame fields.
  - **byte** `{id, raw, value, unit, timestamp}`: fault status byte (`"status byte"`, raw `"2F"`, value 47), UDS failure type.
  - **raw** `{id, raw, text, unit, timestamp}`: undecoded records keep the request they came from as `id`, e.g. `{"id": "22 F1 90", "raw": "57 56 ..."}`, `{"id": "19 04 04 01 00 FF", "raw": "01 01 F4 0D 2A"}`.
  - `get_session_status`: `adapter`, `ignition`, `engine` (values with `detail`), `demo`, `timestamp`, `hint` (next step).
  - `list_modules`: `demo`, `modules[] {address, name, reachability RESPONDING|NOT_RESPONDING|NOT_CHECKED, protocol, link, message, timestamp}`, `counts {responding, notResponding, notChecked}`, `note`.
  - `read_fault_codes`: `demo`, `vinMasked`, `module {address, name}`, `status` (scan's `ModuleStatus`), `statusDescription`, `protocol`, `link`, `faultCount`, `faultListComplete`, `faults[] {id (display code, e.g. P0401-00), code, protocol, record, status (byte), statusText, statusFlags, failureType (byte|null), obdCode, freezeFrame {availability, message, records[raw], fields[value], responses, notes}, unit, timestamp}`, `identification` (or null), `notes`, `limitHit {limit, description, retryAfterSeconds, message}` (or null), `requests`, `startedAt`, `timestamp`.
  - `get_vehicle_info`: `demo`, `vinMasked`, `vin` (value with `source` module), `modules[] {address, name, reachability, protocol, link, identification {protocol, values[], raw[], notes}|null, notes, timestamp}`, `timestamp`.
- Every success also carries the same JSON as one text content block (for clients that only read text).
- **Interpretation of "every value has id, unit, timestamp":** every object with an `id` (anything read from the car) has a `unit` key (`null`: unknown or none; nothing has a known unit yet) and a `timestamp` (ISO-8601 UTC, from scan's `checkedAt` / `readAt` / `finishedAt`). Structural fields (module address/name, status enums, counts) sit beside them. `ToolTesting.assertOutputRules` checks this on every result.

## Output rules
- Always structured JSON, with an output schema per tool. Every value has an ID/name, a unit (or `null` if unknown) and a timestamp. Never bare numbers.
- Unlabeled values keep their raw IDs, e.g. `{"id": "group 011 field 2", "raw": "1A 2B"}`.
- Summaries, not floods. v2 recordings return min/max/avg and notable events (e.g. "actual fell X below specified at Y rpm, N times"), with the full data in a file the AI can query.
- Error messages tell the model what to do next: "adapter not connected: ask the user to plug in the CANable", "module 08 not responding", "rate limit hit: retry after 5 s".
- The VIN is masked unless the user has turned masking off in config. The model can't change that setting.

### Errors (as built)
Tool results with `isError: true` and one text message (MCP tool-execution errors, so the model can correct itself):
- Bad arguments: `invalid arguments: <what> ; <usage> Nothing was sent to the car.` Untrusted names/values are quoted, cut to 40 characters, control/format characters as `?`.
- `ScanRefusedException`: `refused, nothing was sent to the car: <scan's message>. <next step>`: adapter → plug in, check with `get_session_status`; limit → "Wait at least N s ... Don't retry in a loop"; module not responding → "Call list_modules ... retry only after N s"; scan in progress → wait; service closed → ask the user to restart.
- Interrupted / unexpected exception → a short message, details in the app log (SLF4J), never a crash.
- What the car did (no answer, negative response) is data in the result (`status: NOT_RESPONDING`, notes), not an error.
- Unknown tool names get the SDK's JSON-RPC error (`Unknown tool`), and are audited by `McpServlet`.

## Safety (hard rules; enforced in code below this layer, also asserted here)
- **No raw-send or arbitrary-request tool, ever.** No clearing codes, actuator/output tests, basic settings, coding or adaptation.
  - Possible future: a small reviewed list of harmless actuator tests behind human confirmation. Not now, and not without the user's explicit decision.
- **Tool arguments are untrusted input:**
  - Module must be a known address from `scan`'s set.
  - v2 value IDs must be known for that module.
  - Durations and counts are bounded (e.g. `seconds` 1–120).
  - Reject anything else before calling `ScanService`.
- Rate limits and budgets are enforced by `scan` whatever the model asks; surface its errors clearly.
- Every tool call is audit-logged (tool, arguments, result summary), alongside `scan`'s request log.
- Tester Present is handled by `scan`. The model never manages sessions.
- Physical conditions (warm engine, held RPM, driving) need a human. The model asks the user to set them up ("hold 2,500 rpm for 10 s, tell me when done"). AI-driven, human hands.

### How the rules are met (as built)
- `McpTools.NAMES` is the whole tool set; the constructor fails if the registered set drifts from it, and `ToolListTest` pins it.
- Validation order (`McpTools.validate`): exact key set → type checks → `ModuleAddress.parse` → the published input schema as a backstop. The SDK's own input validation is **off** (`validateToolInputs(false)`) because it answers before our handler and would leave rejected calls unaudited; ours runs the same schema plus stricter checks.
- Audit: every call through `McpTools.call` ends in exactly one `scan.recordToolCall(ToolCallRecord)` with outcome `ok` / `rejected` / `refused` / `error` and a one-line summary (no VIN). `Caller.mcp(<tool>)` is passed on every scan call, so scan's request lines name the tool too. A failure to write the tool-call line is logged; the car side is already protected by scan (nothing is sent without its audit line).
- VIN: `ModuleResult.maskVin()` / `VehicleInfo.maskVin()` before shaping, when `McpOptions.maskVin()`.
- Handlers run on the request thread (`immediateExecution(true)`; the servlet transport is blocking anyway).

## What to build
- [x] Hosting decision: in-process Streamable HTTP in `server`, no stdio.
- [x] `McpEndpoint` / `McpOptions` contract for `server`.
- [x] `get_session_status`, `list_modules`, `read_fault_codes(module)`, `get_vehicle_info` with output schemas.
- [x] Strict argument validation before scan; error mapping; audit of every call, including rejected and unknown-tool calls.
- [x] VIN masking from config only.
- [x] Host / Origin validation inside mcp (DNS rebinding), body size bound.
- [ ] v2 tools (live data). Not started.

## Tests (all without hardware)
- [x] `ToolHandlersTest`: each tool against `FakeScanService` / `SampleScanData`: JSON shape, units, timestamps, raw IDs, status byte, failure type, freeze-frame records, VIN masked and unmasked, audit records.
- [x] `RealScanServiceTest`: the whole demo stack (`ScanServiceFactory.demo(FakeTimeSource.autoAdvancing(), …)`) through every tool, with the audit file checked (tool-call lines, scan request lines with `caller: mcp:read_fault_codes`, no guard rejections; rejected calls send nothing); `TestScanServices` for adapter missing and a module that doesn't answer.
- [x] `ArgumentValidationTest`: missing/null/malformed/unknown modules, wrong types, extra fields (`clear`, `request: 14 FF FF FF`), arguments to no-arg tools, unknown tool, hostile key names, many keys; `ScanService` never called, each audited as `rejected`.
- [x] `ToolListTest`: the registered set is exactly the four (endpoint, `NAMES`, definitions); no forbidden words; read-only annotations; strict input schemas.
- [x] `ErrorMappingTest`: adapter missing (every tool), rate limit with retry seconds, module not responding, scan in progress, service closed, unexpected exception, audit-write failure.
- [x] `ServletTransportTest`: the **real servlet + SDK transport + SDK server** through `FakeHttp`, a minimal in-test servlet harness (dynamic-proxy request/response/async context, no container, no sockets): initialize, `notifications/initialized`, `tools/list`, `tools/call` (SSE answer, structured + text, masked VIN, audited), bad arguments, unknown tool audited, Origin refused on POST/GET/DELETE (403, no session, scan untouched), repeated Origin, foreign Host (421), oversized body (413).
- [x] `LocalhostOnlyTest`: accepted and refused hosts and origins, including prefix tricks (`localhost.evil.example`, `localhost:8080.evil.example`, `localhost:evil`).
- Mutation checks: disabling the Host/Origin check failed 34 tests; skipping the unknown-key check failed 9 (the schema backstop still refused the extra fields).
- [ ] Manual: connect Claude Code / Claude Desktop to the demo-mode server and ask it to scan the car (after `server` mounts the endpoint).

## Open questions / notes
- `server` must mount the servlet with async support and keep its own Host/Origin/loopback checks in front. Real HTTP end to end is tested in `server`.
- Spring Boot's BOM in `server` may pick different Jackson 3 / Reactor versions than the SDK's (jackson 3.1.4, reactor-core 3.7.0); watch for runtime mismatches there.
- The SDK's session keep-alive pings and idle-session sweep run on its defaults (30 min each).
- `unit` is `null` everywhere for now: nothing is decoded with a unit yet (v2 measuring blocks will add units).

## Done when
An AI client connected to demo mode can list modules, read fault codes and get vehicle info. All validation and tool-list tests pass, and no tool can cause a non-allowlisted request.
