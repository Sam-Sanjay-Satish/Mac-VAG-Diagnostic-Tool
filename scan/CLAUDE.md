# scan

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** built provisionally against fakes, before the sniffing capture (95 tests, `./gradlew :scan:build` green). Every protocol and timing value from memory is marked **UNCONFIRMED** in named constants and listed below. Pending: the gateway installation list (needs the user's approval, see open questions), the end-to-end replay test, the `VcdsLogParser`, and correcting each UNCONFIRMED item from the capture. **Package:** `io.github.samsanjaysatish.macvagdiag.scan`
**Depends on:** `diag` as `implementation` (front ends can't even compile against diag or transport), `can-core` as `api` (`ScanServiceFactory` takes a `CanAdapter`). **Used by:** `server`, `mcp`.

## Purpose
The application core, and the **only API that front ends use**.
- It finds which modules are installed and runs an Auto-Scan across them.
- It manages diagnostic sessions and keep-alive.
- It enforces rate limits and budgets, and keeps the audit log.
- It serializes all access to the single adapter.

`server` and `mcp` are thin wrappers over this.

## Public API (what `server` and `mcp` use)
- **Create:** `ScanServiceFactory.create(CanAdapter, ScanOptions)` (system clock) or `create(CanAdapter, TimeSource, ScanOptions)`. App startup builds the real `SlcanAdapter` and passes it in; the service then owns it: opens it in NORMAL mode at `options.bitsPerSecond()` when first needed, closes it after an adapter failure so the next call reopens it, closes it in `close()`. One service per process, shared by the web UI and MCP.
  - **Demo:** `ScanServiceFactory.demo(ScanOptions)` / `demo(TimeSource, ScanOptions)`: the real scan/diag/transport stack on a `SimulatedAdapter` answering as `DemoCar` (made-up data, see below).
  - `ScanOptions.defaults()` (audit log at `ScanOptions.defaultAuditLogPath()` = `~/Library/Application Support/macvagdiag/audit.jsonl` on macOS, XDG data dir elsewhere; 500 kbit/s; `ScanLimits.DEFAULTS`), `withAuditLog(Path)`, `withLimits(ScanLimits)`, `withBitsPerSecond(int)`.
- **`ScanService`** (interface, thread-safe, `AutoCloseable`). Every call takes a `Caller` (`Caller.web()`, `Caller.cli()`, `Caller.mcp("read_fault_codes")`), written on every audit line:
  - `SessionStatus sessionStatus(Caller)`: adapter `CONNECTED` / `DISCONNECTED` with detail; ignition and engine `UNKNOWN` with a reason (open question); `demo` flag.
  - `List<ModuleAvailability> listModules(Caller)`: probes each known module (channel setup + session start + close); cached for `moduleListCacheTtl` (60 s), each entry has `checkedAt`.
  - `ModuleResult readFaultCodes(Caller, ModuleAddress)`: fault codes with status and freeze frames, no identification.
  - `VehicleInfo vehicleInfo(Caller)`: identification of 01, 17, 19 and the VIN from the first that reports one.
  - `AutoScanJob startAutoScan(Caller, ScanProgressListener)` (returns at once) and `AutoScanResult autoScan(Caller, ScanProgressListener)` (blocks). `AutoScanJob`: `id()`, `events()`, `lastEvent()`, `completedModules()`, `currentModule()`, `isDone()`, `result()`, `failure()`, `await()`, `await(Duration)`, `completion()`, `cancel()`. `ScanEvent` (sealed): `Started`, `ModuleStarted` ("scanning 08 / Auto HVAC... (4 of 13)"), `ModuleFinished`, `Finished`, `Failed`, each with `message()`.
  - `Optional<AutoScanJob> findAutoScan(String id)` (last 20 kept), `boolean isDemo()`, `List<ModuleAddress> knownModules()`.
  - **`void recordToolCall(ToolCallRecord)`**: MCP logs each tool call (tool, arguments map, outcome, summary) into the same audit log. Arguments are bounded (20 args, 200 chars each) and escaped.
  - `close()`: cancels a running scan, interrupts the worker (its session and channel are closed), closes the adapter and the audit log.
- **Errors:** `ScanRefusedException` = refused, **nothing sent**. `reason()`: `ADAPTER_NOT_CONNECTED`, `LIMIT_EXCEEDED`, `MODULE_NOT_RESPONDING`, `SCAN_IN_PROGRESS`, `SERVICE_CLOSED`; plus `limit()` (`Limit` enum), `retryAfter()`, `module()`. Messages are written for a user or an AI ("adapter not connected: ask the user to plug in the CANable ...", "rate limit hit: at most 400 requests per minute to the car; retry after 12 s", "module 16 (Steering Wheel) not responding: ... retry after 5 s"). What the car did (no answer, negative response) is data in the results, not an exception. Blocking methods throw `InterruptedException`.
- **Input validation:** `ModuleAddress.parse(String)` accepts exactly two ASCII hex digits, either case, naming a known module; anything else throws `InvalidModuleAddressException` (an `IllegalArgumentException`) whose message lists the known modules. `ModuleAddress` has no public constructor, so the service can only ever receive a known module. Also `tryParse`, `of(int)`, `known()`, constants `ENGINE`, `TRANSMISSION`, `INSTRUMENTS`, `GATEWAY`.
- **Model (records, scan types only; `PublicApiTest` enforces it):** `ModuleAddress`, `ModuleLink` (sealed: `Tp20(logicalAddress)`, `IsoTp(requestId, responseId, Protocol)`), `Protocol`, `SessionStatus` (+ `AdapterState`, `VehicleCondition`), `ModuleAvailability` (+ `Reachability`: `RESPONDING`, `NOT_RESPONDING`, `NOT_CHECKED`), `ModuleResult` (status `OK`, `FAULTS`, `NOT_RESPONDING`, `ERROR`, `INCOMPLETE`, `SKIPPED`; link, identification, faults, notes, `limitHit`, request count, times), `EcuInfo`, `RawValue(id, hex, text)`, `FaultCode` (code, record, status byte, status text and flags, failure type, freeze frame), `FreezeFrameData` (`READ`, `NOT_AVAILABLE`, `FAILED`, `SKIPPED`; raw records), `VehicleInfo`, `ModuleIdentity`, `AutoScanResult`, `LimitHit`. The VIN is raw in the model; `maskVin()` on `AutoScanResult` / `ModuleResult` / `VehicleInfo` masks it everywhere, including raw records (as text and as hex). `VinMask.mask(vin)` keeps the first 11 characters.
- **Report:** `ScanReport.text(AutoScanResult, boolean maskVin)` and `text(ModuleResult, boolean maskVin)`: VCDS-like layout ("Address 17: Instruments ... Status", part numbers, "1 Fault Found:", record, freeze frame raw), DEMO banner in demo mode.
- **Test fixtures for front ends:** `testImplementation(testFixtures(project(":scan")))`:
  - `FakeScanService`: scripted `ScanService`, no queue or car. Returns `SampleScanData` by default; `status(..)`, `modules(..)`, `faults(module, result)`, `vehicleInfo(..)`, `autoScanResult(..)`, `failWith(ScanRefusedException)`, `demo(bool)`; records `calls()` and `toolCalls()`. `startAutoScan` returns `AutoScanJob.completed(result, listener)` with the usual events.
  - `SampleScanData`: fixed sample results (VIN `SAMPLEVIN00000000`) for JSON-shape and report snapshot tests.
  - `TestScanServices.create([FakeTimeSource, ScanLimits[, Path auditFile]])`: a **real** `ScanService` on `FakeModuleConnector` (scripted `FakeDiagnosticService` per connection) with an in-memory or file audit log; `auditLines()`.
  - For the whole real stack without a car: `ScanServiceFactory.demo(FakeTimeSource.autoAdvancing(), options)`.

## Design
- **Seam:** package-private `ModuleConnector` opens a `ModuleConnection` (a `DiagnosticService` plus a transport keep-alive handle) per `ModuleLink`. Real: `ChannelConnector` (one `ChannelFactory` on the adapter, `DiagnosticService.forChannel`). Tests: `FakeModuleConnector`.
- **Wiring set:** `ScanServiceFactory`, `ChannelConnector`, `DemoCar` are the only scan classes that reference can-core types other than `TimeSource`, or `ChannelFactory` / `DiagnosticChannel` / `ModuleRoute` / `RequestAuditListener`. Nothing in scan main calls `.send(`, `.request(` or `.awaitResponse(`; `.keepAlive(` only in `ChannelConnector`. `NoBypassSourceTest` enforces this.
- **No payloads in scan:** scan only calls `DiagnosticService` methods and the channel's `keepAlive()`; the allowlist is untouched.
- **Queue:** `RequestQueue`, one worker thread (`macvagdiag-scan-worker`), FIFO. Each module is one job, so other callers are served between the modules of a long Auto-Scan, never inside one. The Auto-Scan orchestrator runs on its own thread and waits for its module jobs.
- **Sessions (`ModuleSession`):** pacing, keep-alive, budget, retries, giving up, always closed in `finally` (also after exceptions and interruption). While it waits (pacing gap, back-off) it sends TP 2.0 keep-alive (`A3`) after `channelKeepAliveInterval` idle and Tester Present after `testerPresentInterval` idle (only once the session started), scheduled on the `TimeSource`. Nothing outside the session can send either.
- **Link fallback:** each module's links are tried in order (the one that answered last first), `connectAttempts` setups each. A module counts as present once its session request is answered (for ISO-TP, which sends nothing at open, that is the first sign of life).
- **Budgets:** before each operation scan reserves its worst-case request count (`DiagOp.cost`, from diag's request table). The wiring's `RequestAuditListener` forwards every request to `ScanEngine` (as `RequestObserver`), which writes the audit line and refuses any request outside a reservation by throwing, so the transport sends nothing. `FullStackTest` checks the reservations equal what diag really sends.
- **Audit log (`AuditLog`):** JSON lines, written by hand (`Json`, every string escaped), opened only with `APPEND`, flushed per line. Events: `request` (written before sending; if it can't be written the request isn't sent), `rejected` (guard), `result` (operation, attempt, outcome, NRC, response hex), `connect`, `keep-alive`, `close`, `refused` (limit / back-off / adapter / unreserved, with `limit` and `retryAfterMs`), `tool-call`, `scan`, `service`. Every line has `time`, `event`, and `caller` where there is one; demo lines carry `"demo":true`. The file can contain the VIN (raw responses); it lives in the app data dir, never in the repo.

## Demo mode
`DemoCar` (main, wiring set) answers the real transport at frame level: TP 2.0 setup / `A0`-`A1` / data / ACK / `A3` / `A8`, and ISO-TP single / first / consecutive frames with flow control. **All data is made up** (VIN `DEMOVIN0000000000`) and follows the same UNCONFIRMED assumptions as transport and diag:
- 01 Engine: UDS over ISO-TP only (TP 2.0 01 silent, so the link fallback runs), P0401 and P2463 with raw freeze-frame records.
- 02, 03, 08, 09, 15, 17, 19: KWP2000 over TP 2.0. 03 has 01314, 19 has 01044 (intermittent), 08 answers its first `18 02` with `7F 18 21` to show the back-off, 17 reports the VIN.
- 16, 25, 44, 46, 56: not installed.
It clears the `SimulatedAdapter`'s sent-frame record every 20 000 frames so a long-running demo server doesn't grow. `server --demo` can use `ScanServiceFactory.demo(options)` until a VIN-masked capture exists for a `ReplayAdapter` demo.

## What to build
- [x] **`ScanService`**: the public façade. Thread-safe; callable from the web UI and MCP at the same time.
  - [x] `sessionStatus()`: adapter connected or not; ignition / engine `UNKNOWN` with a reason (open question).
  - [x] `listModules()`: installed modules and whether each responds.
  - [x] `readFaultCodes(ModuleAddress)`, `vehicleInfo()`, `autoScan()` with progress events.
  - [x] Returns `scan` model types only (`PublicApiTest`).
- [x] **Single request queue:** one worker owns the adapter.
- [ ] **Module discovery:**
  - [ ] Primary: the gateway's installation list. **Not built:** the requests are unknown and would likely need new allowlist entries (open question, needs the user's approval).
  - [x] Fallback: probe the fixed list of known addresses (each link in order).
  - [x] `ModuleAddress` = VW address, name, links (protocol, CAN IDs / TP 2.0 logical address).
  - [x] Validation: only the known set is accepted, parsed strictly.
- [x] **Session management:** open/close channels and sessions per module; Tester Present and TP 2.0 keep-alive on the `TimeSource`, never by callers; always closed, also after errors and interruption.
- [x] **Rate limiting and budgets:** minimum gap, per module per scan, per minute overall, capped retries with exponential back-off on timeouts and `21`, give up after repeated failures, not-responding back-off, one Auto-Scan at a time; refusals name the limit and when to retry. Values are UNCONFIRMED (not VCDS's yet).
- [x] **Audit log:** every request sent and rejected with the caller, results, refusals, MCP tool calls; append-only JSON lines in the app data dir.
- [x] **Auto-Scan result model.**
- [x] **Text report**, VIN masked on request.
- [x] Factory for front ends, demo car, test fixtures for front ends.

## Tests (all without hardware)
- [x] Discovery and Auto-Scan against fake diag: OK, faults, not responding, timing out partway, given up after repeated failures, cancel, one scan at a time, adapter missing, module list cache, vehicle info (`AutoScanTest`).
- [x] Queue: 8 threads × 6 reads on 3 modules get their own results; connections and calls never overlap; one worker thread; a direct read between Auto-Scan modules (`RequestQueueTest`).
- [x] Rate limits, budgets and back-off on the fake clock; exceeding a limit sends nothing; hard bounds on the limits; negative answers aren't retried (`RateLimitTest`).
- [x] Tester Present and keep-alive at the configured intervals during back-off, exact fake-clock timelines for KWP/TP 2.0 and UDS/ISO-TP, none after close, none while busy, failed keep-alive, session closed after an exception and after `close()` mid-operation (`KeepAliveTest`).
- [x] Audit log: escaping, tool calls with forged-looking arguments, result lines, append-only across services, default path, guard rejections of `14 FF FF FF`, `11 01`, `2E F1 90 00`, `31 01 02 03`, `27 01`, `10 02`, `28 00 01`, `85 02` audited with zero frames, unreserved requests stopped, audit failure stops the request (`AuditLogTest`).
- [x] Argument validation: malformed and unknown addresses rejected before any request (`ArgumentValidationTest`).
- [x] **Full stack** (`FullStackTest`): `ScanService` → real diag → real transport → `SimulatedAdapter` + `DemoCar`. Modules, codes, statuses and VIN; the requests decoded from the wire equal the audited requests in order and all pass `RequestGuard`; reservations equal real sends per module; every TP 2.0 channel closed with `A8`; never `7DF`; concurrent reads over the real transport; the public demo factory; an adapter that fails to open and then reopens.
- [x] `NoBypassSourceTest` (source scan, with a self-test), `PublicApiTest` (reflection: no diag/transport/can type reachable from `ScanService`; pinned method set; internals package-private), `ScanReportTest` (snapshot, masking), `FakeScanServiceTest`.
- Mutation checks: disabling the per-minute reservation, the keep-alive schedule or the reservation backstop each failed tests.
- [ ] **Pending the capture:** end-to-end replay test (`autoScan()` on `ReplayAdapter` with the real capture vs the VCDS log via a `VcdsLogParser` in test fixtures). This is the v1 acceptance test.

## UNCONFIRMED (correct from the capture / VCDS log, then update this list)
- **Module list and names** (`ModuleAddress`): 01 02 03 08 09 15 16 17 19 25 44 46 56.
- **Address → link mapping** (`ModuleAddress`): TP 2.0 logical address = VCDS address for every module; 01 and 02 also get ISO-TP UDS `7E0/7E8` and `7E1/7E9` as a second link. Which modules really use TP 2.0 vs ISO-TP.
- **Sessions** (`ModuleSession`): KWP `10 89`; UDS default session `10 01` (least state change; VCDS may use `10 03`).
- **Timing and pacing** (`ScanLimits`): min gap 100 ms, Tester Present after 2 s idle, TP 2.0 keep-alive after 1 s idle, 2 retries, back-off 500 ms doubling to 4 s, give up after 2 failed operations in a row, not-responding back-off 5 s, 2 channel setups per link.
- **Budgets** (`ScanLimits`): 60 requests per module per scan, 400 requests per minute.
- **VIN modules** (`DefaultScanService.VEHICLE_INFO_MODULES`): 01, 17, 19.
- **Operation costs** (`DiagOp`): follow diag's request table; change both together (`FullStackTest` catches a mismatch).
- **Bus speed** (`ScanOptions.DEFAULT_BITS_PER_SECOND`): 500 kbit/s.

## Open questions
- **Needs the user's approval: gateway installation list.** How VCDS reads it (from the capture). The requests are unknown and would likely need new allowlist entries, so discovery only probes the fixed list for now.
- **Ignition and engine state** for `sessionStatus()`: options are "a module answers" (ignition) and RPM from an engine identifier or measuring block. Any new request needs adding to the allowlist with the user's approval. Both are `UNKNOWN` with a reason until then; may slip to v2.
- Actual VCDS pacing, Tester Present and `A3` intervals, budgets (see UNCONFIRMED).
- Whether scan should use UDS `3E 80` (no response by design) instead of `3E 00`.
- Tester Present can't be sent during one long diag call (e.g. a module answering `7F xx 78` for 30 s); the `78`s keep the session alive, but confirm against the capture.
- Ownership: the service closes the adapter in `close()`. If `server` wants to keep it open across services, change that deliberately.

## Done when
The end-to-end replay test passes against the real capture and VCDS log. A real-car Auto-Scan (manual) matches a VCDS Auto-Scan run the same day.
