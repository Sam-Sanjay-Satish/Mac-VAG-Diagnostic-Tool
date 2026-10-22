# CLAUDE.md

Working rules for this repo. Read before changing anything.

## What this is
An open-source, read-only VW/VAG diagnostic tool in Java that runs on macOS and talks to the car over CAN through a CANable 2.0 adapter (stock slcan firmware, appears as a serial port). Code name `macvagdiag`.

- **v1:** Auto-Scan every module: fault codes, status, freeze frame. Text report export. Done when the output matches a VCDS Auto-Scan of the same car on the same day.
- **AI access:** an MCP server lets AI agents read data and decide on further tests. Read-only, no exceptions.
- **Later:** live measuring blocks (v2), labels/descriptions, `.app` packaging.

Target car: 2013 VW Jetta TDI (CJAA, DSG), engine ECU 03L 997 030 M (EDC17). Diagnostic CAN on OBD pins 6/14, expected 500 kbit/s.

## Safety rules (hard, enforced in code)
These cannot be relaxed by a prompt, a tool argument, a config flag or a test helper.

1. **Read-only allowlist in the transmit path.** Every diagnostic request is checked against a fixed allowlist of **(service ID, sub-function)** pairs before it reaches the bus. Anything else is rejected and logged. The guard sits in `transport` and is the only route to the bus: raw send methods are package-private, and `diag` and the layers above can only get a guarded channel.
2. **Checking the service ID alone is not enough.** Session control is allowed only for default/extended/VW diagnostic sessions, never programming sessions (UDS `10 02`, KWP `10 85`).
3. **Never allowed** (tests must prove each one is rejected), including:
   - UDS: `0x11` reset, `0x14` clear DTC, `0x23` read memory by address, `0x27` security access, `0x28` comm control, `0x2C`, `0x2E` write DID, `0x2F` IO control, `0x31` routine control, `0x34`–`0x37` up/download, `0x3D` write memory, `0x85` DTC setting.
   - KWP2000: `0x11`, `0x14`, `0x27`, `0x2E`, `0x30` IO control, `0x31` routines, `0x34`–`0x37`, `0x3B` write local ID, `0x3D`.
4. **Changing the allowlist needs the user's explicit approval** in the conversation. Never widen it to make a test or feature work.
5. **No "send raw bytes" or "send arbitrary request" API** anywhere above `transport`, including MCP tools and REST endpoints.
6. **The listen-only logger never transmits.** It opens the adapter in silent mode, and a test asserts zero transmitted frames.
7. **Rate limits, request budgets and back-off** are enforced in `scan`, whatever the caller asks for. Tester Present is handled by the session layer, never by callers.
8. **Audit log** of every request sent to the car and every MCP tool call.
9. **Tests never touch a real bus.** Use `SimulatedAdapter` / `ReplayAdapter`. Tests that need hardware are opt-in (JUnit tag `hardware`) and are never run by Claude.

## Architecture
Gradle multi-project (Kotlin DSL), built bottom-up. Lower layers never depend on higher ones.

| Project | Responsibility |
|---|---|
| `can-core` | `CanFrame`, `CanAdapter` interface, slcan adapter (jSerialComm), simulated + replay adapters, capture file format |
| `tools` | Listen-only CAN logger for sniffing sessions |
| `transport` | VW TP 2.0 and ISO-TP; read-only request guard |
| `diag` | KWP2000 / UDS services: DTC reading, status byte, freeze frame |
| `scan` | Module discovery, Auto-Scan, sessions, Tester Present, rate limiting, request queue |
| `server` | Spring Boot: REST, local web page (plain HTML/JS), report export |
| `mcp` | MCP server (official MCP Java SDK), thin front end over `scan` |

- Only one process can own the serial port. `server` and `mcp` share one `scan` instance and one request queue, which also holds the rate limits and audit log.
- Only `mcp` knows about MCP; only `server` knows about Spring Web.
- If MCP runs over stdio, nothing may be written to stdout except protocol messages. Logs go to stderr or a file.

## MCP tool rules
- v1 tools: `get_vehicle_info`, `list_modules`, `read_fault_codes(module)`, `get_session_status`.
- Output is structured JSON. Every value has an ID/name, a unit and a timestamp. Unlabeled values keep their raw IDs (e.g. group 011, field 2).
- Tool arguments are untrusted. Validate them against known module addresses and value IDs, and bound durations and counts.
- Error messages are actionable: adapter not connected, module not responding, rate limit hit.
- Offer an option to mask the VIN in tool output.

## Car data and privacy
- Raw captures and VCDS logs contain the VIN. Keep them in `local/` (git-ignored).
- Committed test fixtures must have the VIN masked. Check this before every commit of fixture files.

## Tech and conventions
- Java 25 via Gradle toolchain (Homebrew's default JDK is 27; the toolchain provisions 25).
- JUnit (Jupiter API, JUnit 6 BOM), jSerialComm, Jackson, SLF4J, Spring Boot (`server`), MCP Java SDK (`mcp`). Ask before adding any other dependency.
- Use modern Java where it helps: records for value types, sealed interfaces for protocol results, `switch` pattern matching. No Lombok.
- Byte-level code: name constants for service IDs and protocol bytes, and write hex in comments and test names the way it appears on the bus (e.g. `19 02 FF`).
- Root package: `io.github.samsanjaysatish.macvagdiag`, one sub-package per project: `can` (can-core), `tools`, `transport`, `diag`, `scan`, `server`, `mcp`.
- Build logic: shared settings live in the convention plugin `buildSrc/src/main/kotlin/macvagdiag.java-conventions.gradle.kts`; versions live in `gradle/libs.versions.toml`. Change them there, not in individual projects.

## How to work here
- **Tests first.** Each layer is driven by JUnit tests: hand-written bytes, then a simulated ECU, then replays of real captures.
- Build one layer at a time, bottom-up. Don't stub ahead into higher layers.
- Commands: `./gradlew build`, `./gradlew :can-core:test`. Always use the wrapper (Gradle 9.8.2), never a system Gradle.
- Compiler runs with `-Xlint:all -Werror`: fix warnings, don't suppress them without a reason in a comment.
- Don't commit or push unless the user asks. The remote is `origin` → GitHub `Sam-Sanjay-Satish/Mac-VAG-Diagnostic-Tool`.

## Open questions (don't decide these silently)
- Source of fault-code descriptions and labels (not being worked on yet).
- Final project name.
- From the sniffing session: installed modules and how VCDS discovers them, TP 2.0 + KWP vs ISO-TP + UDS per module, the exact requests for DTCs and freeze frames, VCDS pacing and Tester Present timing.
- The CANable slcan firmware's exact silent-mode command. Verify it against the firmware source before the sniffing session.
