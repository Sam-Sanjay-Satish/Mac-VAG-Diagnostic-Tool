# scan

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** not started; wait for `diag`. **Package:** `io.github.samsanjaysatish.macvagdiag.scan`
**Depends on:** `diag` (and through it `transport`, `can-core`). **Used by:** `server`, `mcp`.

## Purpose
The application core, and the **only API that front ends use**.
- It finds which modules are installed and runs an Auto-Scan across them.
- It manages diagnostic sessions and keep-alive.
- It enforces rate limits and budgets, and keeps the audit log.
- It serializes all access to the single adapter.

`server` and `mcp` are thin wrappers over this.

## What to build
- [ ] **`ScanService`**: the public façade. Thread-safe; callable from the web UI and MCP at the same time.
  - `sessionStatus()`: adapter connected? ignition on? engine running? (see open questions).
  - `listModules()`: installed modules and whether each responds.
  - `readFaultCodes(ModuleAddress)`, `vehicleInfo()`, `autoScan()` (with progress events so the UI can show "scanning 08 / HVAC...").
  - Returns `scan` model types only. Front ends never see `diag` or `transport` classes.
- [ ] **Single request queue:** one worker owns the adapter. All requests from all callers go through it in order. Only one process can own the serial port, and this is the one place that owns it inside the process.
- [ ] **Module discovery:**
  - Primary: the gateway's installation list, read the way VCDS does it (learn the requests from the capture).
  - Fallback: probe a fixed list of known addresses.
  - `ModuleAddress` = VW address (hex, e.g. `01`), name, protocol, CAN IDs.
  - Common VW addresses (confirm): 01 engine, 02 transmission, 03 ABS, 08 HVAC, 09 central electrics, 15 airbags, 16 steering wheel, 17 instruments, 19 CAN gateway, 25 immobilizer, 44 steering assist, 46 comfort, 56 radio.
  - Validation: only addresses from this known set are accepted from callers (MCP arguments are untrusted).
- [ ] **Session management:**
  - Open and close channels and diagnostic sessions per module.
  - Tester Present / TP 2.0 keep-alive runs on a schedule driven by `TimeSource`, at VCDS's timing from the capture. Callers never send it.
  - Always close sessions and channels, including after errors.
- [ ] **Rate limiting and budgets** (enforced here whatever the caller asks):
  - Minimum gap between requests.
  - Maximum requests per module per scan, and per minute overall.
  - Capped retries with exponential back-off on timeouts and NRC `0x21`.
  - Stop a module after repeated failures and report it as not responding.
  - Start from VCDS's measured pacing.
  - When a limit is hit, return a clear error naming the limit.
- [ ] **Audit log:**
  - Records every request sent to the car: time, module, request bytes, response or NRC/timeout, and the caller (`web`, `mcp:<tool>`, `cli`).
  - Records every rejected request too.
  - Append-only file (JSON lines) under the user's app data directory, not in the repo.
- [ ] **Auto-Scan result model:** vehicle info, then per module: address, name, part/software numbers, reachable or not, DTCs with status and freeze frames.
- [ ] **Text report:** close enough to the VCDS Auto-Scan layout that the two can be compared side by side, but more readable. The VIN is masked if requested.

## Tests (all without hardware)
- Discovery and Auto-Scan against a fake `diag` layer: some modules OK, some with faults, some not responding, one timing out partway.
- Queue: concurrent callers from several threads get correct, non-interleaved results, and requests reach the adapter one at a time.
- Rate limits, budgets and back-off using the fake `TimeSource`. Exceeding a limit fails cleanly and sends nothing.
- Tester Present fires at the configured interval during a long operation and stops when the session closes (fake time).
- The audit log records every sent and every rejected request, with the caller.
- Argument validation: unknown module addresses are rejected before any request is built.
- **End-to-end replay test:**
  - Run `autoScan()` on `ReplayAdapter` with the real capture.
  - Parse the matching VCDS Auto-Scan log with a `VcdsLogParser` (in this project's test fixtures).
  - Assert the same modules, codes and statuses.
  - This is the v1 acceptance test.

## Open questions
- How VCDS reads the installation list (from the capture).
- How to tell "ignition on" and "engine running" for `sessionStatus()`. Options: the gateway responds (ignition), and RPM from an engine identifier or measuring block. Any new request needs adding to the allowlist with user approval. This may slip to v2.
- Actual VCDS pacing and Tester Present intervals.

## Done when
The end-to-end replay test passes against the real capture and VCDS log. A real-car Auto-Scan (manual) matches a VCDS Auto-Scan run the same day.
