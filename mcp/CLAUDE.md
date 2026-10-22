# mcp

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** not started; wait for `scan`. **Package:** `io.github.samsanjaysatish.macvagdiag.mcp`
**Depends on:** `scan` + the official MCP Java SDK (`io.modelcontextprotocol.sdk`, MIT; add it to the version catalog when this layer starts). **Used by:** `server` (if MCP is hosted in-process; see below).

## Purpose
Lets AI agents (Claude Desktop, Claude Code, custom agents) use the tool **read-only**: read fault codes, choose live data to read (v2), look at results and decide on further tests. VCDS can't be driven by a model; this can.

This is a **thin front end over `ScanService`**. It adds tool definitions, argument validation and JSON shaping, nothing else. No layer below `scan` knows MCP exists.

## Hosting (decide at the start of this layer)
- **Recommended:** expose MCP over HTTP (Streamable HTTP) on localhost from the `server` process, so the web UI and AI share one `ScanService` and one adapter. Only one process can own the serial port.
- Also possible: a standalone stdio entry point for clients that only launch stdio servers. It can't run while `server` holds the adapter; fail with a clear "adapter in use" error.
- **stdio rule:** in stdio mode nothing but MCP protocol messages may go to stdout. All logging goes to stderr or a file. Test this.
- Spring AI has MCP server starters built on the same SDK; using them is fine if it keeps the code simpler.

## Tools
| Tool | Purpose | Version |
|---|---|---|
| `get_vehicle_info` | ECU part numbers, software versions, VIN (maskable) | v1 |
| `list_modules` | Installed modules and whether each responds | v1 |
| `read_fault_codes(module)` | Codes, status, freeze frame as structured JSON | v1 |
| `get_session_status` | Adapter connected? Ignition on? Engine running? | v1 |
| `list_available_data(module)` | Which live values can be read | v2 |
| `read_live_data(module, values)` | One snapshot of chosen values | v2 |
| `record_live_data(module, values, seconds)` | Record over time; return a summary + file path for the full data | v2 |

**Only build v1 now.** Labels/descriptions are out of scope; don't work on them.

## Output rules
- Always structured JSON, with an output schema per tool. Every value has an ID/name, a unit (or `null` if unknown) and a timestamp. Never bare numbers.
- Unlabeled values keep their raw IDs, e.g. `{"id": "group 011 field 2", "raw": "1A 2B"}`.
- Summaries, not floods. v2 recordings return min/max/avg and notable events (e.g. "actual fell X below specified at Y rpm, N times"), with the full data in a file the AI can query.
- Error messages tell the model what to do next: "adapter not connected: ask the user to plug in the CANable", "module 08 not responding", "rate limit hit: retry after 5 s".
- The VIN is masked unless the user has turned masking off in config. The model can't change that setting.

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

## Tests (all without hardware)
- Each tool handler against a fake `ScanService` and against a real `ScanService` on `ReplayAdapter`: correct JSON shape, units, timestamps, raw IDs.
- Argument validation: unknown module, malformed address, out-of-range duration, extra or unknown fields → a clear error, and `ScanService` is never called.
- **Tool list test:** the registered tool set is exactly the expected list. A new tool must update this test on purpose, so a raw-send tool can't slip in.
- Error mapping: adapter missing, module not responding, rate limit → the right messages.
- Audit: every tool call is logged.
- stdio mode (if built): a test captures stdout during a tool call and checks that it contains only protocol JSON.
- Manual: connect Claude Code / Claude Desktop to the demo-mode server and ask it to scan the car.

## Done when
An AI client connected to demo mode can list modules, read fault codes and get vehicle info. All validation and tool-list tests pass, and no tool can cause a non-allowlisted request.
