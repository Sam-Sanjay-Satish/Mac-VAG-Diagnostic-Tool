# scan

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** built provisionally against fakes, before the sniffing capture (95 tests, `./gradlew :scan:build` green). Every protocol and timing value from memory is marked **UNCONFIRMED** in named constants and listed below. **The capture exists now (2026-11-13); see "From the sniffing session 2026-11-13" below.** It answers the module list, the address → link mapping, discovery and VCDS's pacing, and shows `ModuleAddress` is mostly wrong for this car. Code not changed yet. Pending: correcting `ModuleAddress` and the limits, the gateway installation list (its `22 04 A1` request was allowlisted on 2026-11-15; the CAN ID `32E` still needs the user's approval), the end-to-end replay test and the `VcdsLogParser`. **Package:** `io.github.samsanjaysatish.macvagdiag.scan`
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
  - [ ] Primary: the gateway's installation list. **Not built.** The capture shows it is `22 04 A1` on the gateway's TP 2.0 channel, The allowlist entry (KWP2000 `22 xx xx`) was approved and added on 2026-11-15; the wider CAN ID set still needs the user's approval.
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

## From the sniffing session 2026-11-13
VCDS Auto-Scan and a 01 fault-code read captured listen-only on the target car (git-ignored `local/`; analysis in `local/analysis/2026-11-13-sniffing-analysis.md`). All 16 modules and all 19 fault codes in the VCDS log were found on the bus.

**Installed modules and links** (VCDS address → TP 2.0 logical address from the gateway list → what answered):

| VCDS | Name (VCDS) | TP 2.0 logical | Link that answered | Protocol |
|---|---|---|---|---|
| 01 | Engine | `01` | TP 2.0, tx `740` | KWP2000 |
| 02 | Auto Trans | `02` | TP 2.0, tx `760` | KWP2000 |
| 03 | ABS Brakes | `03` | TP 2.0, tx `790` | `10 89` + UDS-style `22` over TP 2.0 |
| 04 | Steering Angle | `00` | never contacted; "OK" from the gateway list only | – |
| 05 | Acc/Start Auth. | `31` (silent) | ISO-TP `732 → 79C` | UDS |
| 08 | Auto HVAC | `2C` | TP 2.0, tx `33C` | KWP2000 |
| 09 | Cent. Elect. | `20` | TP 2.0, tx `338` | `10 89` + `22` over TP 2.0 |
| 15 | Airbags | `05` (silent) | ISO-TP `715 → 77F` | UDS |
| 17 | Instruments | `07` (silent) | ISO-TP `714 → 77E` | UDS |
| 19 | CAN Gateway | `1F` | TP 2.0, tx `32E` | KWP2000 `18`/`12` + `22` |
| 25 | Immobilizer | `14` (silent) | ISO-TP `711 → 77B` | UDS |
| 2B | Steer. Col. Lock | `30` (not tried) | ISO-TP `731 → 79B` | UDS |
| 2E | Media Player 3 | `54` | nothing | cannot be reached |
| 44 | Steering Assist | `09` | TP 2.0, tx `7A8` | KWP2000 |
| 56 | Radio | `52` | nothing | cannot be reached |
| 77 | Telephone | `5A` (silent) | ISO-TP `76B → 7D5` | UDS |

- 16 and 46 (in today's `ModuleAddress`) are not installed; 04, 05, 2B, 2E and 77 are missing from it.
- **TP 2.0 logical address ≠ VCDS address** for most modules, and **01 / 02 never use ISO-TP** (`7E0/7E8` carried only an OBD-II VIN read). `ModuleAddress`'s links are wrong for 08, 09, 15, 17, 19, 25, 44, 56; the TP 2.0 CAN IDs `32E`/`338`/`33C` and the UDS pairs above also need `transport`'s CAN ID set widened (user approval).

**Discovery: VCDS reads the gateway installation list** with `22 04 A1` on a TP 2.0 channel to the gateway (`1F`), a UDS-style DID read over the KWP channel (needs an allowlist entry, see `diag/CLAUDE.md`). The answer is 4-byte entries `[VCDS address] [TP 2.0 logical address] [status] [?]`; status `00` = not installed, `01` = OK, `05` = faults, `19` = no communication. VCDS's 4-digit status column is bits 4..1 of that byte (`0000` OK, `0010` Malfunction, `1100` Cannot be reached). The UDS CAN IDs are **not** in the list; VCDS knows them, so we need a fixed table. VCDS then tries TP 2.0 at the listed logical address (10 setups, 77 ms apart) and falls back to UDS over ISO-TP.

**VCDS's order and pacing:**
- Order: gateway list, 17 first, then 01 02 03 05 08 09 15 19 25 2B 2E 44 56 77. Before **every** module VCDS opens a short gateway channel (`1A 9B`, `22 F1 87`, `1A 91`, `22 F1 91`, `A8`); we don't need to copy that.
- Session: KWP `10 89`; UDS `10 03`, never back to `10 01`.
- Keep-alive: TP 2.0 `A3` after ≈ 0.5–0.6 s idle; UDS `3E 80` on the functional ID `700`, 0.25–1.6 s apart, only in gaps. Long `78` sequences (BCM: up to 2 × 500 ms per DID) didn't need anything else; the module sent its own `A3`s.
- Gaps: TP 2.0 response → next request ≥ 22 ms (median 41 ms); UDS ≥ 58 ms (median 160 ms). After each unreachable module ≈ 15.3 s of silence.
- Volume: 405 requests in 3 min 17 s (+21 `3E 80`, +6 OBD); at most 168 in any 60 s; per module 6–94 (gateway 94, airbag 71, BCM 57, engine 21). Most are identification DIDs we don't all need.

## UNCONFIRMED (correct from the capture / VCDS log, then update this list)
- **Module list and names** (`ModuleAddress`): answered above; code still has 01 02 03 08 09 15 16 17 19 25 44 46 56 and needs correcting.
- **Address → link mapping** (`ModuleAddress`): answered above; code still assumes TP 2.0 logical = VCDS address plus `7E0`/`7E1` second links.
- **Sessions** (`ModuleSession`): KWP `10 89` confirmed. UDS: VCDS uses `10 03`; we use `10 01`. Whether `10 01` is enough to read DTCs on these modules is unknown (VCDS never tried it).
- **Timing and pacing** (`ScanLimits`): min gap 100 ms is slower than VCDS (fine). TP 2.0 keep-alive after 1 s idle: VCDS uses ≈ 0.55 s and never idled a channel longer, so the modules' channel timeout is unknown; consider 0.5 s. Tester Present after 2 s idle: VCDS sends `3E 80` more often (functional `700`). Retries and back-off: no timeouts or `21` happened, so nothing to compare. 2 channel setups per link: VCDS uses 10.
- **Budgets** (`ScanLimits`): 60 requests per module per scan and 400 per minute are above what our own (shorter) request sets need; VCDS's own counts are above.
- **VIN modules** (`DefaultScanService.VEHICLE_INFO_MODULES`): 01, 17, 19. VCDS read the VIN only via OBD `09 02`; no module was asked for `1A 90` or `F190`, so which modules can report it is still open.
- **Operation costs** (`DiagOp`): follow diag's request table; change both together (`FullStackTest` catches a mismatch).
- **Bus speed** (`ScanOptions.DEFAULT_BITS_PER_SECOND`): 500 kbit/s, confirmed.

## Open questions
- **Needs the user's approval: gateway installation list.** Answered how (2026-11-13): `22 04 A1` on the gateway's TP 2.0 channel. KWP2000 `22 xx xx` is on the allowlist since 2026-11-15; `32E` in transport's CAN ID set still needs approval. Until then discovery probes the fixed list.
- **Ignition and engine state** for `sessionStatus()`: options are "a module answers" (ignition) and RPM from an engine identifier or measuring block. Any new request needs adding to the allowlist with the user's approval. Both are `UNKNOWN` with a reason until then; may slip to v2.
- ~~Actual VCDS pacing, Tester Present and `A3` intervals, budgets~~: answered above (2026-11-13).
- Whether scan should use UDS `3E 80` (no response by design) instead of `3E 00`. VCDS uses `3E 80`, but on the functional ID `700`, which our transport doesn't have; physical `3E 80` / `3E 00` are both on the allowlist.
- Tester Present can't be sent during one long diag call (e.g. a module answering `7F xx 78` for 30 s); the `78`s keep the session alive. Capture: the BCM's `78` waits (≤ 1 s) needed nothing from VCDS, and the module itself sent `A3` during them, which `Tp20Channel` already answers. Longer waits weren't seen.
- Ownership: the service closes the adapter in `close()`. If `server` wants to keep it open across services, change that deliberately.

## Done when
The end-to-end replay test passes against the real capture and VCDS log. A real-car Auto-Scan (manual) matches a VCDS Auto-Scan run the same day.
