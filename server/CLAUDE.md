# server

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** v1 built and tested without hardware (93 tests, `./gradlew :server:build` green; `bootRun --demo` checked by hand on 127.0.0.1:8080). MCP mounted at `/mcp`. Pending: a `ReplayAdapter` demo once a VIN-masked capture exists, and the manual hardware run. **Package:** `io.github.samsanjaysatish.macvagdiag.server`
**Depends on:** `scan` + Spring Boot 4.1.2 (`spring-boot-starter-webmvc`; approved by the user 2026-10-22; BOM via `platform(libs.spring.boot.bom)`, no dependency-management plugin). Tests: `spring-boot-starter-test`, `spring-boot-starter-webmvc-test`, scan's test fixtures. **Used by:** nobody; this is the app.

## Purpose
The desktop app. A Spring Boot process serves a local web page and a REST API on `127.0.0.1`, owns the one `ScanService` (and therefore the adapter), and exports reports. Nothing goes on the internet.

## How to run
```
./gradlew :server:bootRun --args=--demo                                   # simulated demo car (made-up data, DEMO banner)
./gradlew :server:bootRun                                                 # real car: CANable auto-detected by USB ID
./gradlew :server:bootRun --args=--macvagdiag.port=/dev/cu.usbmodem14101  # real car: CANable on this port
```
Then open http://localhost:8080. Other flags go in `--args` the same way (`--server.port=8090`).

### Config properties (`application.properties`, `--name=value`, or env `MACVAGDIAG_PORT=...`)
| Property | Default | Meaning |
|---|---|---|
| `macvagdiag.demo` | `false` | Demo car instead of the CANable. `--demo` does the same. |
| `macvagdiag.port` | empty | CANable serial port. Empty: auto-detect (`/dev/cu.usbmodem*` with USB ID `16D0:117E`), re-checked every 2 s on calls until one is plugged in. Other usbmodem devices are never picked automatically. |
| `macvagdiag.audit-log` | empty | Audit log path. Empty: `~/Library/Application Support/macvagdiag/audit.jsonl` (scan's default). |
| `macvagdiag.bits-per-second` | `0` | Diagnostic CAN speed; 0 = 500000 (UNCONFIRMED). |
| `server.address` | `127.0.0.1` | Must be loopback: startup fails otherwise (`LoopbackOnlyConfiguration`). |
| `server.port` | `8080` | |

## Design
- **Wiring (`ScanServiceConfiguration`)** is the only place that creates an `SlcanAdapter` (`onSerialPort`). The `ScanService` is a Spring bean, closed on shutdown (`destroyMethod = "close"`; scan closes the adapter and audit log). A `TimeSource` or `PortDiscovery` bean overrides the system ones (tests only).
  - Demo: `ScanServiceFactory.demo(time, options)`: the real scan/diag/transport stack on scan's simulated `DemoCar`. **Deviation from the original plan:** a `ReplayAdapter` demo on a bundled capture waits for a VIN-masked capture from the sniffing session.
  - Port configured: `ScanServiceFactory.create(SlcanAdapter.forPort(port))`. If the port isn't there the app still starts; scan reports `DISCONNECTED` with the open error and retries on the next call.
  - Auto-detect: `AdapterWaitingScanService`. No CANable → `/api/status` says `DISCONNECTED` with what was found, car calls are refused `ADAPTER_NOT_CONNECTED` (503). When one appears it builds the real service once and passes everything through. MCP tool calls made before that go to an audit-only service (adapter never opened, closed before the real one is built), so tool calls are always audit-logged.
  - Known limit: once bound, the port path is fixed. If the CANable is replugged into another USB socket (new `usbmodem` name), restart the app.
- **Network (`LocalhostOnlyFilter`, a `Filter` bean; Boot maps it on `/*`, highest precedence, also on async and error dispatches):** covers every path of the servlet context: API, static files, unknown paths, and any servlet mounted later (MCP).
  - `Host` must be `localhost` / `127.0.0.1` / `[::1]`, port optional, exactly one header → else 403 `FORBIDDEN_HOST` (DNS rebinding).
  - `Origin`, if sent, must be `http://` + one of those hosts, any port → else 403 `FORBIDDEN_ORIGIN` (also `Origin: null`). No CORS anywhere: no CORS headers are ever sent.
  - `POST`/`PUT`/`PATCH` must be `application/json` (parameters allowed) → else 415. `DELETE` and `GET` aren't body-checked (MCP Streamable HTTP uses both).
  - Every response: `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'; ...`, `nosniff`, `no-referrer`; `/api/**` also `Cache-Control: no-store`. Rejections log a fixed code only, nothing from the request.
- **Logging:** Boot's default Logback. Server code never logs results; the app log never holds the full VIN (`DemoAppTest` checks the captured output). The audit log stays in scan.

## REST API (`ScanController`, every call as `Caller.web()`; JSON shapes in `ApiJson`)
| Endpoint | Returns |
|---|---|
| `GET /api/status` | `adapter`, `adapterConnected`, `adapterDetail`, `ignition`/`engine` `{state, reason}`, `demo`, `checkedAt`. 200 also when not connected. |
| `GET /api/modules` | `{modules: [{address, name, reachability, link, message, checkedAt}]}` |
| `GET /api/modules/{address}/faults` | one module: `status`, `link {type, protocol, description}`, `identification` (with `raw [{id, hex, text}]`), `faults [{code, displayCode, record, status, statusHex, statusText, statusFlags, failureType, freezeFrame {availability, message, records, fields, responses, notes}}]`, `notes`, `limitHit`, `requests`, times |
| `POST /api/scans` | 202 `{id, href, reportHref}` + `Location`. Body must be empty or `{}`; anything else 400. |
| `GET /api/scans/{id}` | `state` (`RUNNING`/`FINISHED`/`CANCELLED`/`FAILED`), `totalModules`, `completedModules`, `currentModule`, `lastMessage`, `events`, `modules` (finished so far), `result` (summary with VIN) or `failure` |
| `GET /api/scans/{id}/report.txt[?maskVin=true]` | `ScanReport.text`, `text/plain;charset=UTF-8` attachment `macvagdiag-<id>[-demo][-vin-masked].txt` |

Nothing else is mapped (`EndpointSetTest`): no raw-request, clear, write or vehicle-info endpoint.

**Errors** (`ApiErrorHandler`; body always `{error, message, limit, retryAfterSeconds, module}`):
- `ADAPTER_NOT_CONNECTED`, `SERVICE_CLOSED`, interrupted → 503.
- `LIMIT_EXCEEDED` → 429 + `Retry-After` (seconds, rounded up).
- `MODULE_NOT_RESPONDING` *refusal* (scan's back-off, nothing sent) → 429 + `Retry-After`; it is a `Limit` in scan.
- `SCAN_IN_PROGRESS` → 409. Report of a running or failed scan → 409.
- Malformed or unknown module (`ModuleAddress.parse`) → 400 `INVALID_MODULE`, service never called. Bad `maskVin` → 400.
- Unknown scan ID → 404.
- **A module that doesn't answer is data, not an error: 200 with `"status": "NOT_RESPONDING"`** (decision: matches scan's model; no 504).
- A failed Auto-Scan is `GET /api/scans/{id}` 200 with `state: FAILED` and the refusal in `failure` (e.g. adapter not connected found by the scan thread).

## Web page (`src/main/resources/static`: `index.html`, `app.css`, `app.js`; no framework, no build step)
Status bar (adapter, demo, ignition/engine with reasons), "Run Auto-Scan" with live progress (polls every 500 ms), DEMO banner, summary with report downloads (plain and VIN-masked), one card per answering module with OK / Fault / Error / Incomplete badges, expandable fault codes (status byte, flags, record, freeze frame raw records with request IDs), identification with raw values and their IDs, and a "Not reachable" list. Light and dark via `prefers-color-scheme`. All API text is inserted with `textContent`; no inline scripts (CSP). The last scan ID is kept in `sessionStorage` so a reload resumes it.

## What to build
- [x] **Startup wiring:** one `SlcanAdapter` creation site; config port or auto-detect; starts without an adapter.
  - [x] `--demo` on `ScanServiceFactory.demo` (simulated car).
  - [ ] `--demo` on `ReplayAdapter` with a bundled VIN-masked capture: waits for the capture.
- [x] **Bind to `127.0.0.1` only**, enforced at startup.
  - [x] `Host` check on all paths; `Origin` check; no CORS; JSON-only POST.
- [x] **REST API** as above. No arbitrary-request, clear or write endpoint.
- [x] **Web page.**
- [x] **MCP hosting** (decided by the user 2026-10-22: in this process over Streamable HTTP). `McpHostingConfiguration` builds `McpEndpoint` over the **same `ScanService` bean** and registers its servlet at `/mcp` (async supported). The endpoint is closed before the `ScanService` on shutdown.
  - `LocalhostOnlyFilter` on `/*` covers `/mcp`, and the endpoint repeats the Host/Origin checks itself.
  - `macvagdiag.mcp-mask-vin` (default `true`) is the only switch for VIN masking in MCP results; no tool argument can change it.
  - Connect Claude Code: `claude mcp add --transport http macvagdiag http://localhost:8080/mcp` (start the app first, e.g. with `--demo`).
  - Runtime versions: Boot's BOM lifts the SDK's Jackson 3.1.4 → 3.1.7 and reactor-core 3.7.0 → 3.8.8. `DemoAppTest.mcpOverHttp` runs a full MCP session over real Tomcat on these versions.
- [x] Logging: Boot's Logback; no VIN in the app log.

## Tests (all without hardware; 93)
- [x] `ScanControllerTest` (`@WebMvcTest`, `FakeScanService` via `SwappableScanService`): JSON shapes of every endpoint; 503, 429 + `Retry-After` (limit and back-off), 409, 400 (10 malformed/unknown addresses, service not called), 404, 200 `NOT_RESPONDING`, failed scan, POST with parameters.
- [x] `SecurityTest`: 12 wrong Hosts, no Host, two Hosts → 403 with the service not called; allowed hosts; 8 cross-origin Origins → 403 with no CORS headers (GET and POST); preflight gets no CORS headers; same-origin POST works without CORS headers; form / multipart / text / no content type → 415; security headers; `server.address=127.0.0.1`; the loopback customizer refuses `0.0.0.0`, `::` and a LAN address.
- [x] `ReportExportTest`: report download vs `src/test/resources/snapshots/sample-report*.txt` (plain and VIN-masked), headers.
- [x] `DemoAppTest`: full app, demo mode, real Tomcat on a random port at 127.0.0.1, `FakeTimeSource.autoAdvancing()` (instant). Auto-Scan through HTTP: statuses of all 13 modules, codes P0401-00/P2463-00 (01), 01314 (03), 01044 (19), freeze frame, VIN, masked report with DEMO banner, audit lines `demo:true` from `web`, no full VIN in the log. Connector bound to 127.0.0.1. Raw-socket requests: wrong Host → 403 on `/api/status`, `/`, `/app.js`, `/mcp`, unknown paths, `/error`; cross-origin and form POST to `/mcp` rejected.
- [x] `EndpointSetTest`: the mapped endpoints are exactly the six above (plus Boot's `/error`); `ScanController` is the only controller.
- [x] `NoAdapterAppTest`: real wiring with no ports: starts, status `DISCONNECTED`, car endpoints 503.
- [x] `AdapterWaitingScanServiceTest`: refuse until found, non-CANables ignored, bind once after the re-discovery interval (fake clock), tool calls audited before and after, close.
- [x] `HardwareSmokeTest` (`@Tag("hardware")`, manual, never run by Claude): real CANable connected, Auto-Scan gets answers. Then run a scan from the browser by hand.

## Open items
- `ReplayAdapter` demo once a VIN-masked capture exists (until then the simulated `DemoCar`).
- Decide whether `/mcp` should also require a specific `Origin` (MCP spec: servers must validate Origin; the current rule allows any localhost origin).
- Re-detect the CANable after a replug into a different USB socket (currently: restart).
- The web page doesn't offer single-module reads or the module list yet (the API does); add if wanted.

## Later (not v1)
- jpackage `.app` bundle, live gauges, measuring blocks UI (v2).
