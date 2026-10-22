# server

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** not started; wait for `scan`. **Package:** `io.github.samsanjaysatish.macvagdiag.server`
**Depends on:** `scan` (+ Spring Boot; add it to the version catalog when this layer starts). **Used by:** nobody; this is the app.

## Purpose
The desktop app. A Spring Boot process serves a local web page and a REST API on `localhost`, owns the `ScanService` (and therefore the adapter), and exports reports. Nothing goes on the internet.

## What to build
- [ ] **Startup wiring:** the one place that creates the real `SlcanAdapter` (port from config or auto-detect) and builds `ScanService` on it.
  - `--demo` mode builds it on `ReplayAdapter` with a bundled, VIN-masked capture, so the app can be shown without a car. Useful for the portfolio and screenshots.
- [ ] **Bind to `127.0.0.1` only** (`server.address`), never `0.0.0.0`.
  - Reject requests whose `Host` header isn't `localhost` / `127.0.0.1` (protects against DNS-rebinding attacks from websites open in the browser).
  - No CORS. POST endpoints require a JSON content type, so a third-party page can't trigger scans with a simple form post.
- [ ] **REST API** (JSON; mirrors `ScanService`, adds nothing it doesn't offer):
  - `GET /api/status`: adapter, ignition, engine state.
  - `GET /api/modules`: installed modules and reachability.
  - `POST /api/scans` starts an Auto-Scan and returns its ID. `GET /api/scans/{id}` returns progress and results.
  - `GET /api/scans/{id}/report.txt`: text report download, with `?maskVin=true`.
  - `GET /api/modules/{address}/faults`: one module.
  - **No endpoint can send arbitrary requests or bytes. No clear/write endpoints.**
- [ ] **Web page** (plain HTML + CSS + JS in `src/main/resources/static`, no framework, no build step):
  - Status bar.
  - "Run Auto-Scan" button with live progress (polling or server-sent events).
  - Results grouped by module, with clear OK / fault / not reachable states.
  - Expandable fault codes showing status flags and freeze frames.
  - Raw values are shown with their IDs when unlabeled.
  - Goal: more readable than VCDS's wall of text.
- [ ] **MCP hosting:** decide when building `mcp`. The recommended option is to run MCP inside this same process over HTTP on localhost, so the web UI and AI share one `ScanService`. That means `server` depends on `mcp`, so move `mcp` above `server` in `settings.gradle.kts`.
- [ ] Logging: SLF4J via Spring's default Logback. The audit log stays in `scan`.

## Tests (all without hardware)
- Controller tests (`@WebMvcTest` with a fake `ScanService`): JSON shapes, error codes (adapter not connected → 503 with a readable message, unknown module → 400).
- Security: a wrong `Host` header is rejected, a cross-origin request gets no CORS headers, and the server binds to loopback.
- Report export: snapshot test of the text report for a fixed scan result.
- App test in demo mode: start on `ReplayAdapter`, run a scan through the API, and get the expected modules and codes.
- Hardware (`@Tag("hardware")`, manual): start against the real CANable and run a scan from the browser.

## Later (not v1)
- jpackage `.app` bundle, live gauges, measuring blocks UI (v2).
