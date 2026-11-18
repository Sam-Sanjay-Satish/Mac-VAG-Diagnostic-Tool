# diag

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** built against hand-written bytes and fakes, then **corrected from the 2026-11-13 capture** (2026-11-15; 240 tests since 2026-11-17, `./gradlew :diag:build` green). KWP2000 uses the request shapes the capture showed and the allowlist has since 2026-11-15 (`18 00 FF 00` / `18 02 FF 00`, `12 00 04 hh ll`, `22 hh ll` on TP 2.0). The KWP status byte, the `1A 9B` block, KWP freeze frames (all 14 captured records), the VAG → P-code mapping, the gateway installation list and the UDS `19 02 AE` / VCDS wording are decoded and tested against the real bytes and the VCDS log (`KwpCaptureTest`, `UdsCaptureTest`). **Replayed on 2026-11-16** (`CaptureReplayTest`): for every module VCDS reached, the real services over real transport channels decode the captured answers to exactly what the VCDS log shows. UDS freeze frames are read with `19 06` only since 2026-11-16 (VCDS never sent `19 04`). **2026-11-17 (user's decision): the VIN is never read** (`1A 90` and `22 F1 90` are gone, as is `EcuIdentification.vin()`), and **`readOdometer()`** (`22 22 03`) was added. Every request diag builds is now one VCDS sent on the capture. Still pending: decoding the UDS freeze-frame record into fields (the layout hypothesis fits all 5 records and is now checked by a test). What is still from memory is marked **UNCONFIRMED** in code and below. **Package:** `io.github.samsanjaysatish.macvagdiag.diag`
**Depends on:** `transport` (only the public channel API: `DiagnosticChannel`, `Response`, `ModuleRoute`, `ApplicationProtocol`, `RequestGuard`, `ForbiddenRequestException`) and `can-core`'s `TimeSource` and `Hex`. `NoBypassSourceTest` enforces this. **Used by:** `scan`.

## Purpose
Speaks the diagnostic application protocols, **KWP2000** and **UDS (ISO 14229)**, over a `DiagnosticChannel`. It builds read-only requests, parses responses into typed results, and decodes DTCs, status bytes, freeze frames and ECU identification. It never sees a CAN frame and never opens a channel.

## Public API (what `scan` uses)
- **`DiagnosticService`** (interface). One instance per open channel; not thread-safe (`scan`'s queue serialises). Every method returns a `DiagResult` and `throws InterruptedException`:
  - `static DiagnosticService forChannel(DiagnosticChannel, TimeSource[, DiagTimings[, KwpOptions]])`: picks the implementation by `channel.protocol()`. `KwpOptions` (default `KwpOptions.DEFAULT`) is used for KWP2000 only; `scan` should pass `KwpOptions.ENGINE` for 01.
  - `ApplicationProtocol protocol()`
  - `DiagResult<RawBytes> startSession(DiagnosticSession)`: KWP `10 89`, UDS `10 01` / `10 03`. Returns the raw positive response. A session of the other protocol → `IllegalArgumentException`, nothing sent.
  - `DiagResult<RawBytes> testerPresent()`: KWP `3E` → `7E`, UDS `3E 00` → `7E 00`.
  - `DiagResult<EcuIdentification> readIdentification()`
  - `DiagResult<DtcList> readDtcs()`
  - `DiagResult<FreezeFrame> readFreezeFrame(Dtc)`: a DTC of the other protocol → `IllegalArgumentException`. **KWP: one `12 00 04 hh ll` per call. Call it once per DTC list entry, in list order, on the same channel**: a code stored several times returns the next stored record on each repeat. Nothing is cached.
  - `DiagResult<InstallationList> readInstallationList()`: `22 04 A1`, one request. Only the gateway has it; others answer `7F 22 31` → `Negative`.
  - `DiagResult<Odometer> readOdometer()` (2026-11-17): `22 22 03`, one request, on either protocol. `Odometer(kilometres, response)`: `62 22 03 [hi] [lo]`, 16-bit big-endian × 10 km (`Odometer.KILOMETRES_PER_COUNT`); anything else is `Malformed`. On the capture only 17 was asked and answered `62 22 03 8F 14` = 366280 km, VCDS's header mileage. A module without it answers negatively. The 10 km unit is confirmed by a second sample: on the car 2026-11-18 our scan gave 366990 km, VCDS's header the same.
- **`KwpDiagnosticService(channel, time[, timings, KwpOptions])`**, **`UdsDiagnosticService(channel, time[, timings])`**. Both refuse a channel of the wrong protocol.
  - Request counts for `scan`'s budgets: `KwpDiagnosticService.MAX_IDENTIFICATION_REQUESTS` (6: `1A 9B` + the 5 fallback DIDs; the `1A` path sends 1), `KwpDiagnosticService.FREEZE_FRAME_REQUESTS` (1), `UdsDiagnosticService.IDENTIFICATION_DIDS.size()` (5 since 2026-11-17), `UdsDiagnosticService.FREEZE_FRAME_REQUESTS` (1, since 2026-11-16). DTC reads, session, tester present, the installation list and the odometer are 1 each.
- **`KwpOptions(DtcRequest, VagCodeMapping)`**: per-module choices `scan` makes. `DtcRequest.STATUS_00` (`18 00 FF 00`) / `STATUS_02` (`18 02 FF 00`). `KwpOptions.ENGINE` = `STATUS_00` + `VagCodeMapping.P_CODE_HEX` (what VCDS did for 01); `KwpOptions.DEFAULT` = `STATUS_02` + `NONE` (every other module and the gateway).
- **`DiagnosticSession`**: `KWP_VW_DIAGNOSTIC`, `UDS_DEFAULT`, `UDS_EXTENDED`. That is all; no programming session exists here.
- **`DiagResult<T>`** (sealed). Variants:
  - `Success(value)`
  - `Negative(NegativeResponse)`: any NRC except `21`/`78`
  - `Busy(NegativeResponse)`: NRC `21`, `retryable()` = true
  - `TimedOut(waited, pendingResponses)`
  - `PendingLimitExceeded(pendingResponses, limit)`
  - `TransportFailure(Response.Failed.Kind, detail)`
  - `Malformed(reason, RawBytes response)`
  - `NotAvailable(reason)`: nothing was sent
  - Helpers: `message()`, `isSuccess()`, `valueIfSuccess()`, `retryable()`, `map()`, `failure()`.
- **`NegativeResponse(serviceId, code, raw)`**, with `nrc()` → `Optional<Nrc>` and `message()`. **`Nrc`** enum: ISO names and readable descriptions for `10 11 12 13 21 22 24 31 33 78 7E 7F`. Unknown codes are kept raw.
- **`DiagTimings(requestTimeout, pendingTimeout, maxPendingResponses, maxTotalPendingWait)`**, `DEFAULTS`. There are hard ceilings (100 × `78`, 2 min per duration) that no caller can exceed.
- **Model:**
  - `Dtc` (sealed): `KwpDtc(code, KwpDtcStatus, Optional<String> obdCode)` and `UdsDtc(code, UdsDtcStatus)`. Both have `codeBytes()`, `record()` (code + status, as received), `displayCode()` and `status()`. `KwpDtc.displayCode()` is VCDS's decimal: 6 digits when there is an OBD code (`011174`), else 5 (`01304`). `UdsDtc` adds `failureType()` (the raw third byte) and `vagNumber()` (VCDS's number, the decimal of the 24 bits, e.g. `9453074`, `0022`).
  - `DtcStatus` (sealed): `UdsDtcStatus(raw)` (`Flag` enum, `flags()`, `has()`, and `vcdsWords()` / `vcdsText()`, e.g. "Intermittent - Confirmed - Tested Since Memory Clear") and `KwpDtcStatus(raw)` (`faultType()` / `faultTypeText()` = bits 0–4 as `002`, `intermittent()` = bit 6 clear, `milOn()` = bit 7, `bits()` as VCDS prints them, e.g. `01100001`). Both have `raw()` and `flagNames()` (KWP: VCDS's words `Intermittent`, `MIL ON`).
  - `DtcList(dtcs, response, undecoded, statusAvailabilityMask, warnings)`, `complete()`.
  - `FreezeFrame(dtc, records, fields, responses, notes)`, with `FreezeFrame.Field(kind, value, sourceRecordId)`. Field kinds: `STATUS`, `PRIORITY`, `OCCURRENCE_COUNTER` (VCDS "Fault Frequency"), `RESET_COUNTER`, `MILEAGE`, `TIME_INDICATION`, `TIME`, `DATE_TIME` (not produced yet), `ENVIRONMENT`, `OTHER`. Values are as VCDS prints them (`362256 km`, `09:13:31`, `RPM: 828 /min`). KWP fills `fields`; UDS keeps its records raw.
  - `InstallationList(entries, response, undecoded, warnings)` with `installed()`, `entry(address)` and `complete()`. `InstallationList.Entry(address, tp20LogicalAddress, status, group)` has:
    - `statusKind()`: `NOT_INSTALLED` (`00`), `OK` (`01`), `MALFUNCTION` (`05`), `CANNOT_BE_REACHED` (`19`), `OTHER`.
    - `installed()`, `vcdsStatusBits()` (bits 4..1, e.g. `0010`) and `tp20Address()` (empty for `00`).
  - `EcuIdentification(protocol, Map<Field,String> fields, records, notes)`, with accessors `partNumber()`, `softwareVersion()`, `hardwareNumber()`, `systemName()` and `coding()`. No VIN (never read since 2026-11-17).
  - `RawRecord(id, RawBytes data)`, `RawBytes` (immutable; `hex()`, `asciiText()`).
- **`DtcCodes`**: `saeJ2012(hi, lo)`, `vagFiveDigit(code16)`, `vagSixDigit(code16)`, `vagUdsNumber(code24)`.
- **`VagCodeMapping`**: `NONE`, and `P_CODE_HEX` (`P` + the 16-bit value as 4 hex digits, below `0x4000`; confirmed for all 8 engine codes). Which module gets which is `scan`'s choice, through `KwpOptions`.
- **Test fixture for `scan`:** `testImplementation(testFixtures(project(":diag")))` gives `io.github.samsanjaysatish.macvagdiag.diag.FakeDiagnosticService`:
  - Create with `kwp()` / `uds()`, then script results with `onStartSession`, `onTesterPresent`, `onReadIdentification`, `onReadDtcs`, `onReadFreezeFrame`, `onReadInstallationList` and `onReadOdometer`. Each queues one `DiagResult`; results are used in order, and an unscripted call throws `IllegalStateException`.
  - It records `calls()` and offers `allScriptUsed()`.
  - Helpers: `ok(value)`, `dtcList(Dtc...)`, `identification(protocol, fields)`, `freezeFrame(dtc, RawRecord...)`, `installationList(Entry...)`, `odometer(km)`.
  - Like the real services, a session or DTC of the other protocol throws `IllegalArgumentException`.
  - Alternatively, `scan` can run the real services over transport's `FakeDiagnosticChannel`.
- Package-private: `DiagRequest` (the only way to build a request), `Exchange` (the only code that calls the channel's `request` / `awaitResponse`), `Reads`, `IdentificationReader`, `KwpFreezeFrames` and `KwpMeasuringValue`. There is deliberately **no public "read any DID" method** (root rule 5): the installation list is its own typed operation. Nothing above `diag` can build or send a request.

## What to build
- [x] **`DiagnosticService`** interface, with `KwpDiagnosticService` and `UdsDiagnosticService` behind it:
  - [x] `readIdentification()` → `EcuIdentification`. UDS decodes part number, software version, hardware number, system name and coding from DIDs. KWP decodes the 46-byte `1A 9B` block (part number, software version, coding, component). The VIN is never read (`1A 90` / `22 F1 90` dropped 2026-11-17, user's decision). If `1A 9B` is answered negatively (03, 09 and 19 on the car), it reads `22 F1 87` / `F1 89` / `F1 91` / `F1 97` / `06 00` over the KWP channel instead.
  - [x] `readDtcs()` → `DtcList` (the DTCs plus the raw response, left-over bytes and warnings).
  - [x] `readFreezeFrame(Dtc)` → `FreezeFrame`. UDS reads `19 06 [DTC] FF` only, kept raw. KWP reads `12 00 04 hh ll` and decodes status, priority, frequency, reset counter, mileage, time indication, time (with VCDS's +1 s) and the environment triplets.
  - [x] `readInstallationList()` → `InstallationList` (`22 04 A1`, gateway).
  - [x] `readOdometer()` → `Odometer` (`22 22 03`, 17 Instruments), 2026-11-17.
  - [x] UDS: VCDS sends only `19 06` (no `19 04`), so since 2026-11-16 so do we (`DiagRequest.udsReadSnapshot` stays, allowlisted, unused).
  - [ ] UDS: decode the `19 06` record into fields. The hypothesis below matches VCDS for all 5 records (`CaptureReplayTest.udsFreezeFrames`); still kept raw.
  - [ ] (v2) read measuring blocks / data identifiers.
  - [x] Session start and Tester Present are exposed (`startSession`, `testerPresent`). `scan` decides when; `diag` never calls them on its own.
- [x] **Result model** (records and sealed interfaces): `Dtc`, `DtcStatus` (raw byte plus decoded flags; KWP separate from UDS), `FreezeFrame`, `EcuIdentification`, `DtcList`, `RawRecord`, `RawBytes`.
  - [x] Results distinguish success, negative response (with the NRC), busy, timeout, pending cap, transport failure, malformed and not available, consistently via the sealed `DiagResult`. Nothing the module or transport does throws.
- [x] **Unknown data stays raw.** Nothing is dropped:
  - DTC lists keep the whole response plus any trailing partial record (`undecoded`, with a warning).
  - Identification keeps every answer as a `RawRecord` with its request as the ID (e.g. `22 F1 87`).
  - Freeze frames keep each record block (ID `19 06 04 01 00 FF`) and every whole response.
  - `Malformed` and `NegativeResponse` keep the whole message.
  - Answers that don't echo correctly are kept whole, with a note.
- [x] **Negative responses** `7F [SID] [NRC]` (all in `Exchange`):
  - [x] `0x78` response pending:
    - Waits via `awaitResponse`, never resending. Each wait is `pendingTimeout` (`RESPONSE_PENDING_TIMEOUT`), clipped to what is left of `maxTotalPendingWait`, measured on the `TimeSource`.
    - **Capped:** more than `maxPendingResponses` (`MAX_RESPONSE_PENDING`) `78`s, or the total time running out, gives `PendingLimitExceeded`.
    - A `78` for a different SID is `Malformed`, not waited on.
  - [x] `0x21` busy → `Busy`, `retryable()`. It is not retried here; `scan`'s back-off decides.
  - [x] `0x11` / `0x12` / `0x31` / `0x22` (and any other NRC) → `Negative` with a typed `Nrc` and a readable message. Unknown NRCs are kept as their raw code.
- [x] **`ForbiddenRequestException`** is impossible by construction:
  - `DiagRequest`'s private constructor checks every request against `RequestGuard.isAllowed` as it is built.
  - The services refuse channels of the wrong protocol.
  - If it ever happens anyway, it propagates unchanged. `Exchange` has no try/catch, and a test asserts the same instance reaches the caller from every operation.
- [x] Operations made of several requests (identification, freeze frame) note a `Negative`/`Malformed` part and carry on. A `Busy`/`TimedOut`/`PendingLimitExceeded`/`TransportFailure` stops the operation and is returned, so `scan` retries it as a whole. If no part succeeded, the first `Negative`/`Malformed` is returned.

## Requests this layer can produce
Each one passes the approved allowlist unchanged (`RequestAllowlistTest` sweeps every factory's whole parameter range):

| Protocol | Request | Used by |
|---|---|---|
| KWP2000 | `10 89` | `startSession(KWP_VW_DIAGNOSTIC)` |
| KWP2000 | `3E` | `testerPresent()` |
| KWP2000 | `1A 9B`, then (only if refused) `22 F1 87`, `22 F1 89`, `22 F1 91`, `22 F1 97`, `22 06 00` | `readIdentification()` |
| KWP2000 | `18 00 FF 00` (`KwpOptions.ENGINE`), `18 02 FF 00` (default) | `readDtcs()` |
| KWP2000 | `12 00 04 [hi] [lo]` | `readFreezeFrame(dtc)` |
| KWP2000 | `22 04 A1` | `readInstallationList()` |
| KWP2000 | `22 22 03` | `readOdometer()` |
| UDS | `10 01`, `10 03` | `startSession(UDS_DEFAULT / UDS_EXTENDED)` |
| UDS | `3E 00` | `testerPresent()` |
| UDS | `22 F1 87`, `22 F1 89`, `22 F1 91`, `22 F1 97`, `22 06 00` | `readIdentification()` |
| UDS | `19 02 AE` | `readDtcs()` |
| UDS | `19 06 [DTC] FF` | `readFreezeFrame(dtc)` |
| UDS | `22 04 A1` | `readInstallationList()` |
| UDS | `22 22 03` | `readOdometer()` |

**Never produced:** the VIN reads `1A 90` and `22 F1 90` (user's decision, 2026-11-17; `IdentificationTest` and `RequestAllowlistTest` check they aren't sent).

Allowlisted but **not produced** by `diag` (no need yet): the 2-byte KWP `18 xx` and `12 xx` forms (VCDS never used them), UDS `3E 80`, UDS `19 01 mm`, UDS `19 04` (VCDS never sent it; dropped 2026-11-16).

## Protocol notes (written from memory before the capture; where they differ, "From the sniffing session" below and the code win)
- **UDS DTC status byte** (ISO 14229), in `UdsDtcStatus.Flag`:
  - bit0 testFailed
  - bit1 testFailedThisOperationCycle
  - bit2 pendingDTC
  - bit3 confirmedDTC
  - bit4 testNotCompletedSinceLastClear
  - bit5 testFailedSinceLastClear
  - bit6 testNotCompletedThisOperationCycle
  - bit7 warningIndicatorRequested
- **UDS DTC format:** 3 bytes. The first two bytes follow SAE J2012: the top 2 bits give P/C/B/U, then the digits, e.g. `04 01` → `P0401`. The third byte is the failure type. VCDS also shows a VW-specific number; work out the mapping from the VCDS log.
- **UDS requests:**
  - `19 02 [status mask]` → `59 02 [availability mask] ([DTC 3 bytes][status]...)`.
  - `19 04 [DTC] [record]` → `59 04 [DTC] [status] [records...]` snapshot. Kept raw: records can't be split without the DID lengths.
  - `19 06 [DTC] [record]` → `59 06 [DTC] [status] [records...]` extended data (VW: counters, mileage, priority; confirm). Kept raw.
  - Identification via `22 [DID]` → `62 [DID] [data]`.
- **KWP2000 (VW):**
  - `18 [status] FF 00` → `58 [count] ([DTC 2 bytes][status])...` (confirmed).
  - `1A 9B` ECU identification (decoded, see below). `1A 90` (VIN) is never sent.
  - VAG codes below `0x4000` map to `P` + 4 hex digits (`VagCodeMapping.P_CODE_HEX`, from the VCDS log). The older offset scheme for higher codes (e.g. 16684 for P0300, from memory) is **not** implemented.
- ~~The EDC17 may return extended DTC formats~~: it doesn't.

## From the sniffing session 2026-11-13
Capture and VCDS log of the target car (git-ignored, `local/`; full analysis in `local/analysis/2026-11-13-sniffing-analysis.md`). Protocol per module is in `scan/CLAUDE.md`. In short: **01 Engine is KWP2000 over TP 2.0** (not UDS); 02, 08, 44 are KWP2000 over TP 2.0; 03, 09 and the gateway 19 are on TP 2.0 but answer `1A 9B` with `7F 1A 11` and are read with UDS-style `22` DIDs over the KWP channel; 05, 15, 17, 25, 2B, 77 are UDS over ISO-TP.

**KWP2000, what VCDS sends and gets:**
- `10 89` → `50 89`.
- Identification: `1A 9B` (KWP modules), then `1A 91` (hardware number), `1A 97` (01: engine code `CJAA`), `1A 86` (serial / production data; the engine answers `7F 1A 78` first). `1A 90` is **never** sent; VCDS reads the VIN with OBD-II `09 02` on `7E0` instead (not on our allowlist, not needed).
  - `1A 9B` layout (engine, 46 bytes after `5A 9B`): part number 12 ASCII (`03L997030M  `), software version 4 ASCII (`9983`), 4 bytes `03 00 C3 9E` (coding `0x00C39E` = `0050078` as VCDS shows it), 6 bytes workshop code (`… 42` = WSC 00066), component name 20 ASCII (`R4 2.0l TDI G000AG  `).
- **DTCs: `18 00 FF 00`** (engine) and **`18 02 FF 00`** (every other KWP module and the gateway): the 4-byte ISO 14230 form, status byte then group `FF 00`. Response `58 [count] ([hi][lo][status])…` as assumed, e.g. engine `58 0C 2B A6 E0 1A 58 60 …` (12 codes), others `58 00`.
- **Freeze frames: `12 00 04 [hi] [lo]`** (freeze frame number `00`, record access method `04` = by DTC, the 2-byte code), one request per entry of the DTC list, in list order. **For a code stored several times, repeating the same request returns the next stored record** (the engine has `04 01` and `05 44` twice and `00 87` three times; each request got a different record). Response layout, all 13 records:
  `52 00 6C 01 [status] [priority] [frequency] [reset counter] [mileage km, 3 bytes] [time indication] [01 FF Bx xx xx date/time]` then (engine only) 7 environment values as `[formula] [a] [b]` triplets, then `04 [hi] [lo]`.
  - Environment triplets use the classic KWP measuring-value formulas: `01` RPM = 0.2·a·b, `07` km/h = 0.01·a·b, `21` % = 100·b/a, `05` °C = a·(b−100)·0.1, `31` mg/stroke = a·b/40, `14` % = a·(b−128)/128. Every value checked matches VCDS.
  - Date/time: low 17 bits = hour (5) · minute (6) · second (6); VCDS prints the KWP second **+ 1**. The date bits are near all-ones and VCDS prints the same date for every record, so the date can't be decoded from this car.
- VCDS also sends `31 B8 00 00` (routine control, never allowed) to every TP 2.0 module and `21 11` / `21 59` (measuring blocks) to the engine for readiness. Not needed for v1.
- **Measuring blocks (v2, from the 2026-11-18 capture; not on the allowlist, adding `21 xx` needs the user's approval):** request `21 <group>` (VCDS decimal group → hex), reply `61 <group>` + 8 triplets (24 bytes). **Only triplets 1–4 are VCDS's fields 1–4**; 5–8 are placeholders (`25 00 00`, `30 00 00`). Formula `25` with `00 00` = no value (VCDS shows a blank cell). Checked against VCDS's CSV at idle:
  - 007: fuel temp `05 09 8F` 38.7 °C, **oil temp `25 00 00` (empty: the engine has no oil temp)**, intake air `05 09 83` 27.9, coolant `05 09 AF` 67.5.
  - 099: RPM `01 73 24` 828, EGT before turbo `05 3C 7F` 162.0 °C, before ox. cat `05 3C 79` 126.0, after ox. cat `05 3C 78` 120.0.
  - 100: `05 3C 78` 120.0, `05 3C 74` 96.0, DPF differential pressure `32 27 82` / `32 27 80` (VCDS 5 / 0 mbar; formula `32` not decoded yet). 033: RPM, EGT pre-turbo `05 3C 7E`, two regulation factors `1F 50 20` (VCDS 1.00). 001: RPM, `27` mg/str, `64` bar (`64 50 29` = 328.0 ✓), coolant. 011: RPM, `60` mbar (`60 79 53` = 1004.3 ✓) ×2, duty cycle `21`.
  - VCDS polls round-robin, one `21 xx` every ~140 ms with 3 groups open, and keeps sending `A3` about every 0.55 s alongside.
- **OBD-II mode 01 (same capture):** the engine (`7E8`) supports 01 04 05 0C 0D 10 13 1C 1F 21 24 25 30 31 33 3C 41 42 46 49 4A 4C 4F 51 5D 5E 61 62 63 65 67 69 6B 6D 70 71 73 77 78 7A 7F 81 82 89 8B; **not `5C` (oil temp)**. VCDS sent these to functional `7DF`; the DSG also answered on `7E9`. Not used by macvagdiag (`7E0/7E8` were removed 2026-11-17).

**KWP DTC codes and status (12 engine + 2 gateway pairs, all matched):**
- VCDS code = decimal of the 16-bit value (`2B A6` → 011174; gateway `05 18` → 01304). `DtcCodes.vagFiveDigit` is right; VCDS zero-pads to 6 digits for the engine (`vagSixDigit`, used when there is a P-code).
- **P-code = `P` + the 16-bit value as 4 hex digits** for all 8 distinct engine codes (`2B A6` → P2BA6, `1A 58` → P1A58, `04 01` → P0401, `00 87` → P0087, …; all values < `0x4000`). The gateway codes have no P-code. Implemented as `VagCodeMapping.P_CODE_HEX` for codes < `0x4000`, with these pairs as tests; the older offset scheme for codes ≥ `0x4000` (from memory) can't be checked on this car.
- **Status byte** (VCDS prints it raw as 8 bits): bits 0–4 = the "- 00x" number after the P-code (seen 0, 1, 2, 4, 6); **bit 6 clear = "Intermittent"**; **bit 7 = "MIL ON"**; bit 5 was set in every record (meaning unknown). `KwpDtcStatus` was corrected to this on 2026-11-15 (it had bit 7 = intermittent, bits 0–6 = symptom).

**UDS, what VCDS sends and gets:**
- `10 03` first (never `10 01`); `50 03 00 32 00 C8` (P2 50 ms, P2* 2 s) or `50 03 00 32 01 F4` (P2* 5 s).
- Identification DIDs, one per request: `F19E` (ASAM dataset), `F1A2`, `F187` (part number), `F189` (software version), `F191` (hardware number), `F1A3` (hardware version), `F1A5`, `F1DF`, `F1A0`, `F1A1`, `F19A`, `F197` (system name), `0601`, `0600` (coding, e.g. 17: `37 0C 00` = VCDS `370C00`), `F1AA`, `F17E`, `F18C` (serial). `7F 22 31` for unsupported ones. **`F190` is never read.** Airbag subsystems: 39 DIDs in `06xx` / `07xx`.
- **DTCs: `19 02 AE`** (mask `AE`, not `FF`). 17: `59 02 99 90 3E 12 08 90 59 1B 08`; 77: `59 02 19 90 4F 13 09 90 4E 13 09 00 00 16 09`; 15 answers `7F 19 78` first.
  - VCDS number = decimal of the 24-bit DTC (`90 3E 12` → 9453074, `00 00 16` → "0022"). SAE code = J2012 of the first two bytes (`90 3E` → B103E). VCDS's failure-type text doesn't always equal the third byte (`90 3E 12` shown as `1B`; `00 00 16` shown as `U1066 00`): it comes from its database. Show ours raw.
  - Status `08` = "Intermittent - Confirmed - Tested Since Memory Clear", `09` = "Confirmed - Tested Since Memory Clear": "Intermittent" ⇔ bit 0 clear, "Tested Since Memory Clear" ⇔ bit 4 clear.
- **Freeze frame: `19 06 [DTC] FF` only** (extended data); **no `19 04`**. Layout (hypothesis that fits all 5 records): `01 [priority] [frequency]` `02 [reset counter] [mileage km, 3 bytes] [time indication] [5 bytes date/time]`, `FF` where not available. Mileage and hour/minute/second match VCDS exactly. VCDS printed the **PC's (wrong) date**, so don't compare UDS freeze-frame dates with the VCDS log.

**Timing:** TP 2.0 request → first response 8–237 ms (median 44 ms); UDS 2–72 ms (median 24 ms). `78` is repeated every ≈ 500 ms (BCM, up to 2× per DID); longest wait seen after the first `78` ≈ 1 s. Our 1000 ms request timeout and 5000 ms pending timeout fit.

## UNCONFIRMED (correct from the capture / VCDS log, then update this list)
Items answered by the 2026-11-13 capture are above and in the code. What is left:
- **KWP DTC request:** why VCDS uses status `00` for the engine and `02` for the rest. The 2-byte `18 xx` form is no longer built.
- **KWP status byte:** bit 5 (set in every record seen), and fault-type values other than 0, 1, 2, 4, 6.
- **KWP 6-digit display:** that the P-code is what makes VCDS print 6 digits (`011174`) instead of 5 (`01304`). Only one module of each kind was seen.
- **KWP freeze frame:**
  - the header bytes `6C 01` (checked, and noted if different);
  - the date bits (not decoded);
  - VCDS's +1 second at `:59` (we carry over);
  - measuring-value formulas other than `01 05 06 07 08 13 14 21 27 31 60 64` (kept raw as `formula XX: a b (not decoded)`).
- **KWP `1A 9B`:**
  - coding type bytes other than `03` (decimal) and `00 00 00 00` (no coding);
  - the 6-byte workshop code, kept raw. From 2 samples, `0A FF E0 00 08 15` = VCDS `WSC 02069 000 90108` fits "last 2 bytes = WSC, bits 31..11 of the first 4 = equipment number";
- **KWP `22` fallback:** single-DID `F1 87` / `F1 89` / `F1 91` on 03 and 09, and `F1 89` on 19 (VCDS asked them only inside its multi-DID request; the replay test answers them from that response). The gateway did answer single `F1 87`, `F1 91`, `F1 97` and `06 00`. **Confirmed on the car 2026-11-18:** 03, 09 and 19 answer every single-DID `22` we send (`F1 87`, `F1 89`, `F1 91`, `F1 97`, `06 00`; 03 and 09 with `7F 22 78` first), and the part numbers match VCDS's.
- **Installation list:** the 4th byte of each entry (bus or group?), and status values other than `00 01 05 19`.
- **UDS:**
  - the freeze-frame record layout (hypothesis above), and what VCDS does with `F1A5` / `F19A` / `F1DF`;
  - VCDS's wording for status bits other than 0, 3 and 4;
  - the failure-type mapping as VCDS shows it (database, not bytes);
  - `vagNumber()`'s 4-digit minimum (one sample, `0022`).
- **Timings** (`DiagTimings`) beyond what the capture shows: the 20-`78` / 30 s caps (never approached).
- **NRC names** in `Nrc`: from memory of ISO 14229-1 / 14230-3 (`11`, `12`, `31`, `78` seen).

## Tests (all without hardware)
- [x] Every parser driven by **hand-written response bytes** (`KwpReadDtcsTest`, `UdsReadDtcsTest`, `IdentificationTest`, `FreezeFrameTest`, `KwpFreezeFrameTest`, `InstallationListTest`, `SessionTest`):
  - zero DTCs, one, many; truncated responses; count/length mismatches; wrong echoes; unknown status bits; raw fields preserved;
  - the KWP `22` fallback (partial answers, all refused, a timeout stopping it) and an unknown coding type;
  - freeze frames with the wrong layout, code, status or header, bad time bits, the +1 s carry, and every measuring-value formula.
- [x] **Real bytes from the 2026-11-13 capture vs the VCDS log** (`CaptureBytes` holds no VIN-bearing message; `KwpCaptureTest`, `UdsCaptureTest`, `DtcCodesTest`, `DtcStatusTest`):
  - Engine `18 00 FF 00`: all 12 codes, P-codes, fault types, status bits and words. Gateway `18 02 FF 00`: 01304 / 02078, type 004.
  - All 12 engine freeze frames, read once per list entry in order (repeats for `04 01`, `05 44`, `00 87`), and both gateway ones: every header value and all 84 environment values as VCDS prints them.
  - `1A 9B` of 01, 02, 08, 44: part number, software version, coding (or none), component. Gateway `1A 9B` → `7F 1A 11`, then single `22` DIDs: part number, software version, hardware number, system name, coding `102200`.
  - Gateway `22 04 A1`: 35 entries; the 16 installed ones in VCDS's order with its 4-digit statuses and TP 2.0 addresses.
  - UDS 17 and 77 `19 02 AE`: VCDS numbers, SAE codes, VCDS status wording.
- [x] Status byte decoding (`DtcStatusTest`): every bit individually for UDS and KWP, plus combinations and a full 00..FF round trip.
- [x] DTC formatting (`DtcCodesTest`): P/C/B/U prefixes, KWP 5-digit decimal, and that `VagCodeMapping.NONE` maps nothing.
- [x] Negative responses (`NegativeResponseTest`, `FakeDiagnosticChannel` + `FakeTimeSource`, no sleeping):
  - `11 12 22 31` under both protocols, an unknown NRC, and extra bytes after the NRC.
  - `21` busy, not retried.
  - `78` then the real answer, and `78` ×2 then the answer.
  - `78` up to the cap, `78` past the count cap, and `78` forever on the default cap.
  - `78` past the total-time cap. `ClockAdvancingChannel` advances fake time per wait and checks the clipped wait durations.
  - `78` then silence; `78` for another SID.
  - Timeout, transport failure (also after a `78`), a closed channel, and malformed negatives.
  - `ForbiddenRequestException` propagating unchanged from every operation.
- [x] **Requests built here are allowlisted** (`RequestAllowlistTest`):
  - Reflection pins the set of `DiagRequest` factories, and the constructor is private.
  - Every factory's full parameter range (all bytes, all 65536 DIDs on both protocols, all 65536 KWP freeze-frame codes, both `18` forms, sampled 24-bit DTCs × all records; >200 000 requests) passes `RequestGuard.isAllowed`.
  - A full run of every operation on both services sends only the expected allowlisted requests.
- [x] **No bypass** (`NoBypassSourceTest`): scans `diag/src/main/java` and fails on:
  - Any `io.github.samsanjaysatish.macvagdiag.*` reference other than `diag`, `can.TimeSource`, `can.Hex` and the six public transport types (wildcards included).
  - `CanFrame`, `CanAdapter` and `ChannelFactory` by simple name.
  - Reflection (`Class.forName`, `loadClass`, `setAccessible`, `MethodHandles`) and `keepAlive`.
  - Channel `request` / `awaitResponse` calls outside `Exchange`.

  A self-test checks the scanner catches each case. Mutation checks: adding a `can.CanAdapter` import, or disabling the `78` count cap, each made the build fail.
- [x] `OdometerTest` (2026-11-17): the captured `62 22 03 8F 14`, the KWP form, 0 and `FF FF`, `7F 22 31`, malformed lengths and echoes.
- [x] `FakeDiagnosticServiceTest`, `ValueTypesTest` (`RawBytes`, `DiagTimings` validation, `Nrc` table, defensive copies).
- [x] **VCDS log pairs:** VAG number ↔ P-code (`VagCodeMapping.P_CODE_HEX`), KWP status byte ↔ VCDS bits and words, UDS DTC ↔ VCDS's number and wording, KWP freeze-frame fields ↔ VCDS's values (see above).
- [x] **Replay against the real capture** (`CaptureReplayTest`, 2026-11-16): the real services over real transport channels on a `SimulatedAdapter` with transport's `CaptureEcu` (answers from the VIN-masked capture), compared with `VcdsLog` (can-core test fixtures) of the same scan, per module as scan reads it (session, identification, DTCs, one freeze frame per entry):
  - **Identification**, all 13 reachable modules: part number = VCDS's "Part No" (spaces removed), VCDS's component starts with our system name and ends with our software version, hardware number = VCDS's "HW", coding = VCDS's (and none where VCDS shows none). On 03 and 09 (and `F189` on 19), `F187` / `F189` / `F191` come from VCDS's six-DID read (`CaptureEcu.derived()`): VCDS never asked them singly, so the replay can't show whether they answer a single-DID read. They do: confirmed on the car 2026-11-18 (see above).
  - **Fault codes**, all 19: VCDS number, P-code (KWP) / SAE code (UDS), KWP fault type and words, UDS status bits and words, failure type. Known differences: `90 3E 12` and `00 00 16` (VCDS's failure type and "U1066" come from its database).
  - **KWP freeze frames:** 01's 12 (84 environment values) from both captures against both logs, and 19's 2, field for field except the date.
  - **UDS freeze frames:** 17's and 77's `19 06` records fit the layout hypothesis (priority, frequency, reset counter, mileage, time; mileage/time absent where VCDS shows none).
  - **Installation list:** 35 entries, the 16 installed = VCDS's status table with its 4-digit bits.
  - **Nothing VCDS didn't send** (pinned since 2026-11-17: `CaptureEcu.unseen()` is empty; until then the VIN reads `1A 90` / `22 F1 90` were the only ones).
  - **Odometer:** 17's `22 22 03` → `62 22 03 8F 14` = 366280 km = VCDS's header "Mileage: 366280km" (`odometerFromTheInstruments`).
  - Mutation check: disabling transport's length-flag mask fails 2 of these.

## Open questions (need the capture and/or the user)
- ~~KWP `18` long form~~: built 2026-11-15. `DiagRequest.kwpReadDtcsByStatus(KwpOptions.DtcRequest)` builds exactly `18 00 FF 00` / `18 02 FF 00`.
- ~~KWP freeze frame~~: built 2026-11-15, `12 00 04 hh ll`.
- ~~`22` on TP 2.0 channels~~: built 2026-11-15, for the identification fallback and the installation list. VCDS's 13-byte multi-DID form stays not allowed.
- ~~KWP `1A` options and `1A 9B` layout~~: answered above (`1A 9B`, `91`, `97`, `86`; layout decoded). Adding `1A 91` / `1A 97` / `1A 86` needs no allowlist change.
- ~~UDS session~~: VCDS uses `10 03`. Whether `scan` should switch from `10 01` is a `scan` decision. VCDS's Tester Present is `3E 80` on the functional ID `700` (see `transport/CLAUDE.md`). P2/P2* are 50 ms / 2–5 s.
- **UDS freeze frame:** the record layout above is a hypothesis from 5 records (a replay test checks it against VCDS); confirm on more modules before splitting records into fields.
- ~~EDC17 extended DTC formats~~: none; the EDC17 here is plain KWP2000 with 3-byte DTC records.

## Done when
For every module in the capture, `diag` decodes the same codes, statuses and freeze-frame values that VCDS shows. Values VCDS labels but we can't decode yet are kept as raw.
