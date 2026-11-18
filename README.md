# macvagdiag

[![build](https://github.com/Sam-Sanjay-Satish/Mac-VAG-Diagnostic-Tool/actions/workflows/build.yml/badge.svg)](https://github.com/Sam-Sanjay-Satish/Mac-VAG-Diagnostic-Tool/actions/workflows/build.yml)

An open-source, **read-only** diagnostic tool for VW / VAG cars that runs natively on macOS. It talks to the car over CAN through a [CANable 2.0](https://canable.io/) USB adapter, written in Java, MIT licensed.

It reads fault codes, their status, freeze frames, module identification and mileage. It never writes, clears, codes, adapts or flashes anything, and that is enforced in code, not by convention.

> **Status:** v1 works on a real car. On 2026-11-18 its Auto-Scan of the target car (13 modules, 21 fault codes, in about 15 s) matched a VCDS Auto-Scan of the same car taken minutes later: the same modules and statuses, fault codes, freeze frames, part numbers and mileage. It has been tested on one car only, and it is not a VCDS replacement: no live data, no fault-code descriptions yet.

## Features (v1)
- **Auto-Scan of every module:** discovers installed modules through the gateway's installation list, then reads fault codes with status wording, KWP2000 freeze frames, part numbers and software versions, and the odometer.
- **Local web page** on `http://localhost:8080`: run a scan, watch progress, browse modules and fault codes, download a text report.
- **MCP server** for AI agents (Claude Code, Claude Desktop, any MCP client) with four read-only tools: `get_session_status`, `list_modules`, `read_fault_codes(module)`, `get_vehicle_info`. Structured JSON with raw IDs, units and timestamps.
- **Demo mode** with a simulated car, so everything can be tried without an adapter.
- **Listen-only CAN logger** (`can-log`) for sniffing sessions, and a sanitizer that masks the VIN in captures and VCDS logs.

Not done yet: live measuring blocks (the protocol is worked out from a capture, see the roadmap), fault-code descriptions / label files, `.app` packaging.

## Safety design
A diagnostic tool that an AI agent can drive has to be safe regardless of what the agent asks for. These rules are enforced in code and covered by tests:

- **Read-only allowlist in the transmit path.** Every request is checked against a fixed allowlist of (service ID, sub-function) pairs in the transport layer, the only route to the bus. Everything else is rejected and logged.
- **Never allowed**, with a test for each: ECU reset, clear DTCs, security access, write data / local IDs, IO control (actuator tests), routine control (basic settings), upload / download, memory read / write by address, communication control, DTC setting control, and programming sessions.
- **No "send raw bytes" API** anywhere above the transport layer, including the MCP tools and the REST API.
- **CAN IDs are fixed** to the ones the target car uses.
- **Rate limits, request budgets and back-off** in the scan layer; Tester Present is handled by the session layer, never by callers.
- **Audit log** of every request sent to the car and every MCP tool call.
- **Localhost only:** the server binds to `127.0.0.1` and checks `Host` / `Origin` headers (DNS-rebinding protection).
- **The VIN is never read or shown.**
- **The listen-only logger never transmits:** it opens the adapter in bus-monitoring mode, which needs the patched firmware below.

> **Disclaimer:** use at your own risk. Only use it with the car parked. This project comes with no warranty and has no affiliation with Volkswagen AG, Ross-Tech or VCDS.

## Hardware
- **CANable 2.0** (tested: Makerbase CANable V2.0 S, STM32G431) running the patched slcan firmware **`macvagdiag-silent-1`**, which adds a working listen-only mode. Build and flash instructions: [`firmware/canable2/README.md`](firmware/canable2/README.md).
- An OBD-II cable to the adapter: CAN high on pin 6, CAN low on pin 14. The adapter's 120 Ω termination must be **off**.
- Diagnostic CAN at 500 kbit/s.

### Supported cars
Developed against a **2013 VW Jetta TDI (CJAA, DSG)**. On that car most modules, including the engine (EDC17), use **VW TP 2.0 + KWP2000**; six use **ISO-TP + UDS**. Other VAG cars of the same era will probably partly work, but are untested, and the CAN ID set is currently limited to that car's.

## Getting started
Requirements: macOS and a JDK to run Gradle. The build uses a Java 25 toolchain, which Gradle downloads if needed. Always use the wrapper.

```sh
./gradlew build                                   # build and run all tests (no hardware needed)

./gradlew :server:bootRun --args=--demo           # demo car (simulated, DEMO banner)
./gradlew :server:bootRun                         # real car, CANable auto-detected by USB ID
./gradlew :server:bootRun --args=--macvagdiag.port=/dev/cu.usbmodem14101   # real car, explicit port
```

Then open <http://localhost:8080>.

### Connect an AI agent (MCP)
Start the server (demo mode is a good first try), then:

```sh
claude mcp add --transport http macvagdiag http://localhost:8080/mcp
```

Ask the agent to check the session status and scan the car. Physical conditions (ignition on, engine running) are always set up by a human.

### Listen-only logger
```sh
./gradlew :tools:run --args="list-ports"
./gradlew :tools:run --args="can-log --note 'VCDS auto-scan, engine off'"
```

Captures go to `local/captures/` (git-ignored) by default. Raw captures contain the VIN, so don't share them unmasked.

## Architecture
A Gradle multi-project, built bottom-up. Each layer talks to the one below through an interface, so each can be tested with a fake underneath.

| Project | Responsibility |
|---|---|
| [`can-core`](can-core/CLAUDE.md) | CAN frames, adapter interface, slcan adapter (jSerialComm), simulated and replay adapters, capture file format |
| [`tools`](tools/CLAUDE.md) | Listen-only CAN logger, capture / VCDS-log sanitizer |
| [`transport`](transport/CLAUDE.md) | VW TP 2.0, ISO-TP, the read-only request guard |
| [`diag`](diag/CLAUDE.md) | KWP2000 / UDS services: fault codes, status bytes, freeze frames, identification |
| [`scan`](scan/CLAUDE.md) | Module discovery, Auto-Scan, sessions, Tester Present, rate limits, audit log |
| [`server`](server/CLAUDE.md) | Spring Boot app: REST API, local web page, report export, hosts the MCP endpoint |
| [`mcp`](mcp/CLAUDE.md) | MCP server (official MCP Java SDK), a thin front end over `scan` |

Each project's `CLAUDE.md` has its design notes, protocol details and status.

## Testing
CI has no adapter and no car, so `./gradlew build` passes with nothing plugged in:

- **Hand-written bytes** for encoders and decoders (slcan, TP 2.0 / ISO-TP framing, fault-code records, freeze frames).
- **Simulated ECUs** that answer requests, including negative responses, timeouts and dropped frames.
- **Replays of a real car capture** (VIN masked). An end-to-end test runs an Auto-Scan against the replay and compares it with the VCDS Auto-Scan of the same session: modules, statuses, fault codes, freeze frames, part numbers and mileage.
- **Frames from real-car runs.** The first scan on the car showed that some modules need a pause of about 20 ms between our acknowledgement and our next request (VCDS waits about 18 ms). The replays answer instantly, so they couldn't show it. Those frames are now unit tests.
- Time is injectable, so timeouts and keep-alives are tested instantly.

Hardware tests are tagged `hardware`, excluded from the normal build, and run by hand at the car. All four have passed on the real car:

```sh
# VCDS connected and running an Auto-Scan (our adapter only listens):
MACVAGDIAG_EXPECT_FRAMES=1 ./gradlew :can-core:hardwareTest :tools:hardwareTest
# VCDS unplugged (these transmit):
./gradlew :transport:hardwareTest
./gradlew :server:hardwareTest
```

Never run `./gradlew hardwareTest` for everything, and never run the transmitting tests while another tester is connected. The hardware tasks take turns on the adapter, show their output live, and the server test writes an audit log and a frame trace of the run to `local/hardware-runs/`.

## Roadmap
1. ~~Hardware tests on the bench, then at the car.~~ Done 2026-11-17/18.
2. ~~A real Auto-Scan compared with a same-day VCDS Auto-Scan (v1 done).~~ Done 2026-11-18.
3. Live measuring blocks (v2), also as MCP tools. The request and response format and the value formulas are worked out from a listen-only capture of VCDS. Reading them needs one more allowlist entry (KWP `21`, measuring blocks), which is still to be approved.
4. Fault-code descriptions and label files.
5. A macOS `.app` package.

## License
[MIT](LICENSE) © 2026 Sam Sanjay Satish
