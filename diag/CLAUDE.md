# diag

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** built provisionally against hand-written bytes and fakes, before the sniffing capture (128 tests, `./gradlew :diag:build` green). Every protocol detail below that came from memory is marked **UNCONFIRMED** in code and here, and is kept in named constants so the capture can correct it in one place. **The capture exists now (2026-11-13); see "From the sniffing session 2026-11-13" below.** All 19 fault codes VCDS reported decode from the bus. It **contradicts** the KWP status-byte layout and shows that KWP DTCs and freeze frames need longer request shapes; those were added to the allowlist on 2026-11-15 (user's approval), but `diag`'s code doesn't use them yet. Still pending: those `diag` changes, the replay tests and the VCDS-log tests. **Package:** `io.github.samsanjaysatish.macvagdiag.diag`
**Depends on:** `transport` (only the public channel API: `DiagnosticChannel`, `Response`, `ModuleRoute`, `ApplicationProtocol`, `RequestGuard`, `ForbiddenRequestException`) and `can-core`'s `TimeSource` and `Hex`. `NoBypassSourceTest` enforces this. **Used by:** `scan`.

## Purpose
Speaks the diagnostic application protocols, **KWP2000** and **UDS (ISO 14229)**, over a `DiagnosticChannel`. It builds read-only requests, parses responses into typed results, and decodes DTCs, status bytes, freeze frames and ECU identification. It never sees a CAN frame and never opens a channel.

## Public API (what `scan` uses)
- **`DiagnosticService`** (interface). One instance per open channel; not thread-safe (`scan`'s queue serialises). Every method returns a `DiagResult` and `throws InterruptedException`:
  - `static DiagnosticService forChannel(DiagnosticChannel, TimeSource[, DiagTimings])`: picks the implementation by `channel.protocol()`.
  - `ApplicationProtocol protocol()`
  - `DiagResult<RawBytes> startSession(DiagnosticSession)`: KWP `10 89`, UDS `10 01` / `10 03`. Returns the raw positive response. A session of the other protocol → `IllegalArgumentException`, nothing sent.
  - `DiagResult<RawBytes> testerPresent()`: KWP `3E` → `7E`, UDS `3E 00` → `7E 00`.
  - `DiagResult<EcuIdentification> readIdentification()`
  - `DiagResult<DtcList> readDtcs()`
  - `DiagResult<FreezeFrame> readFreezeFrame(Dtc)`: a DTC of the other protocol → `IllegalArgumentException`.
- **`KwpDiagnosticService(channel, time[, timings, VagCodeMapping])`**, **`UdsDiagnosticService(channel, time[, timings])`**. Both refuse a channel of the wrong protocol.
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
  - `Dtc` (sealed): `KwpDtc(code, KwpDtcStatus, Optional<String> obdCode)` and `UdsDtc(code, UdsDtcStatus)`. Both have `codeBytes()`, `record()` (code + status, as received), `displayCode()` and `status()`. `UdsDtc` adds `failureType()`.
  - `DtcStatus` (sealed): `UdsDtcStatus(raw)` (`Flag` enum, `flags()`, `has()`) and `KwpDtcStatus(raw)` (`intermittent()`, `symptomCode()`, UNCONFIRMED). Both have `raw()` and `flagNames()`.
  - `DtcList(dtcs, response, undecoded, statusAvailabilityMask, warnings)`, `complete()`.
  - `FreezeFrame(dtc, records, fields, responses, notes)`, with `FreezeFrame.Field(kind, value, sourceRecordId)`. Field kinds: `MILEAGE`, `DATE_TIME`, `PRIORITY`, `OCCURRENCE_COUNTER`, `RESET_COUNTER`, `OTHER`.
  - `EcuIdentification(protocol, Map<Field,String> fields, records, notes)`, with accessors `partNumber()`, `softwareVersion()`, `hardwareNumber()`, `systemName()`, `coding()` and `vin()`. The VIN is unmasked; masking is `scan`'s job.
  - `RawRecord(id, RawBytes data)`, `RawBytes` (immutable; `hex()`, `asciiText()`).
- **`DtcCodes`**: `saeJ2012(hi, lo)`, `vagFiveDigit(code16)`.
- **`VagCodeMapping`**: an extension point, deliberately `NONE` (see below).
- **Test fixture for `scan`:** `testImplementation(testFixtures(project(":diag")))` gives `io.github.samsanjaysatish.macvagdiag.diag.FakeDiagnosticService`:
  - Create with `kwp()` / `uds()`, then script results with `onStartSession`, `onTesterPresent`, `onReadIdentification`, `onReadDtcs` and `onReadFreezeFrame`. Each queues one `DiagResult`; results are used in order, and an unscripted call throws `IllegalStateException`.
  - It records `calls()` and offers `allScriptUsed()`.
  - Helpers: `ok(value)`, `dtcList(Dtc...)`, `identification(protocol, fields)`, `freezeFrame(dtc, RawRecord...)`.
  - Like the real services, a session or DTC of the other protocol throws `IllegalArgumentException`.
  - Alternatively, `scan` can run the real services over transport's `FakeDiagnosticChannel`.
- Package-private: `DiagRequest` (the only way to build a request), `Exchange` (the only code that calls the channel's `request` / `awaitResponse`), and `Reads`. Nothing above `diag` can build or send a request.

## What to build
- [x] **`DiagnosticService`** interface, with `KwpDiagnosticService` and `UdsDiagnosticService` behind it:
  - [x] `readIdentification()` → `EcuIdentification`. UDS decodes part number, software version, hardware number, system name, coding and VIN from DIDs. KWP decodes only the VIN (`1A 90`); `1A 9B` is kept raw until its layout is confirmed.
  - [x] `readDtcs()` → `DtcList` (the DTCs plus the raw response, left-over bytes and warnings).
  - [x] `readFreezeFrame(Dtc)` → `FreezeFrame`. UDS reads `19 04` + `19 06`, kept raw. KWP returns `NotAvailable` (see open questions).
  - [ ] (v2) read measuring blocks / data identifiers.
  - [x] Session start and Tester Present are exposed (`startSession`, `testerPresent`). `scan` decides when; `diag` never calls them on its own.
- [x] **Result model** (records and sealed interfaces): `Dtc`, `DtcStatus` (raw byte plus decoded flags; KWP separate from UDS), `FreezeFrame`, `EcuIdentification`, `DtcList`, `RawRecord`, `RawBytes`.
  - [x] Results distinguish success, negative response (with the NRC), busy, timeout, pending cap, transport failure, malformed and not available, consistently via the sealed `DiagResult`. Nothing the module or transport does throws.
- [x] **Unknown data stays raw.** Nothing is dropped:
  - DTC lists keep the whole response plus any trailing partial record (`undecoded`, with a warning).
  - Identification keeps every answer as a `RawRecord` with its request as the ID (e.g. `22 F1 87`).
  - Freeze frames keep each record block (ID `19 04 04 01 00 FF`) and every whole response.
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
| KWP2000 | `1A 9B`, `1A 90` | `readIdentification()` |
| KWP2000 | `18 02` | `readDtcs()` |
| UDS | `10 01`, `10 03` | `startSession(UDS_DEFAULT / UDS_EXTENDED)` |
| UDS | `3E 00` | `testerPresent()` |
| UDS | `22 F1 87`, `22 F1 89`, `22 F1 91`, `22 F1 97`, `22 06 00`, `22 F1 90` | `readIdentification()` |
| UDS | `19 02 FF` | `readDtcs()` |
| UDS | `19 04 [DTC] FF`, `19 06 [DTC] FF` | `readFreezeFrame(dtc)` |

Allowlisted but **not produced** by `diag` (no need yet): KWP `12 xx`, UDS `3E 80`, UDS `19 01 mm`.

## Protocol notes (from memory; confirm everything against the capture and the VCDS log before relying on it)
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
  - `18 [status]` → `58 [count] ([DTC 2 bytes][status])...`.
  - `1A 9B` ECU identification (kept raw); `1A 90` VIN.
  - VAG 5-digit codes (e.g. 16684 for P0300) relate to P-codes by an offset scheme. **Not hard-coded:** `VagCodeMapping` is an extension point, `NONE` by default. Implement it only from VCDS log pairs, with a test per pair.
- The EDC17 may return extended DTC formats. Check against the capture.

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

**KWP DTC codes and status (12 engine + 2 gateway pairs, all matched):**
- VCDS code = decimal of the 16-bit value (`2B A6` → 011174; gateway `05 18` → 01304). `DtcCodes.vagFiveDigit` is right (VCDS zero-pads to 6 digits for the engine).
- **P-code = `P` + the 16-bit value as 4 hex digits** for all 8 distinct engine codes (`2B A6` → P2BA6, `1A 58` → P1A58, `04 01` → P0401, `00 87` → P0087, …; all values < `0x4000`). The gateway codes have no P-code. This is what `VagCodeMapping` should implement for codes < `0x4000`, with these pairs as tests; the older offset scheme for codes ≥ `0x4000` (from memory) can't be checked on this car.
- **Status byte** (VCDS prints it raw as 8 bits): bits 0–4 = the "- 00x" number after the P-code (seen 0, 1, 2, 4, 6); **bit 6 clear = "Intermittent"**; **bit 7 = "MIL ON"**; bit 5 was set in every record (meaning unknown). **`KwpDtcStatus` is wrong** (it has bit 7 = intermittent, bits 0–6 = symptom).

**UDS, what VCDS sends and gets:**
- `10 03` first (never `10 01`); `50 03 00 32 00 C8` (P2 50 ms, P2* 2 s) or `50 03 00 32 01 F4` (P2* 5 s).
- Identification DIDs, one per request: `F19E` (ASAM dataset), `F1A2`, `F187` (part number), `F189` (software version), `F191` (hardware number), `F1A3` (hardware version), `F1A5`, `F1DF`, `F1A0`, `F1A1`, `F19A`, `F197` (system name), `0601`, `0600` (coding, e.g. 17: `37 0C 00` = VCDS `370C00`), `F1AA`, `F17E`, `F18C` (serial). `7F 22 31` for unsupported ones. **`F190` is never read.** Airbag subsystems: 39 DIDs in `06xx` / `07xx`.
- **DTCs: `19 02 AE`** (mask `AE`, not `FF`). 17: `59 02 99 90 3E 12 08 90 59 1B 08`; 77: `59 02 19 90 4F 13 09 90 4E 13 09 00 00 16 09`; 15 answers `7F 19 78` first.
  - VCDS number = decimal of the 24-bit DTC (`90 3E 12` → 9453074, `00 00 16` → "0022"). SAE code = J2012 of the first two bytes (`90 3E` → B103E). VCDS's failure-type text doesn't always equal the third byte (`90 3E 12` shown as `1B`; `00 00 16` shown as `U1066 00`): it comes from its database. Show ours raw.
  - Status `08` = "Intermittent - Confirmed - Tested Since Memory Clear", `09` = "Confirmed - Tested Since Memory Clear": "Intermittent" ⇔ bit 0 clear, "Tested Since Memory Clear" ⇔ bit 4 clear.
- **Freeze frame: `19 06 [DTC] FF` only** (extended data); **no `19 04`**. Layout (hypothesis that fits all 5 records): `01 [priority] [frequency]` `02 [reset counter] [mileage km, 3 bytes] [time indication] [5 bytes date/time]`, `FF` where not available. Mileage and hour/minute/second match VCDS exactly. VCDS printed the **PC's (wrong) date**, so don't compare UDS freeze-frame dates with the VCDS log.

**Timing:** TP 2.0 request → first response 8–237 ms (median 44 ms); UDS 2–72 ms (median 24 ms). `78` is repeated every ≈ 500 ms (BCM, up to 2× per DID); longest wait seen after the first `78` ≈ 1 s. Our 1000 ms request timeout and 5000 ms pending timeout fit.

## UNCONFIRMED (correct from the capture / VCDS log, then update this list)
Items answered by the 2026-11-13 capture are above; what is left:
- **KWP DTC request:** our `18 02` (2 bytes) was not tried by VCDS; it uses `18 00 FF 00` / `18 02 FF 00`. Whether the short form works at all is unknown. Status byte `00` vs `02`: VCDS uses `00` for the engine only; why is unknown.
- **KWP status byte bit 5**, and the other bits never seen set in this car.
- **KWP freeze-frame header** bytes `6C 01` and the date bits.
- **UDS freeze-frame record layout** (hypothesis above), and what VCDS does with `F1A5` / `F19A` / `F1DF`.
- **UDS failure-type mapping** as VCDS shows it (database, not bytes).
- **Timings** (`DiagTimings`) beyond what the capture shows: the 20-`78` / 30 s caps (never approached).
- **NRC names** in `Nrc`: from memory of ISO 14229-1 / 14230-3 (`11`, `12`, `31`, `78` seen).

## Tests (all without hardware)
- [x] Every parser driven by **hand-written response bytes** (`KwpReadDtcsTest`, `UdsReadDtcsTest`, `IdentificationTest`, `FreezeFrameTest`, `SessionTest`): zero DTCs, one, many; truncated responses; count/length mismatches; wrong echoes; unknown status bits; raw fields preserved.
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
  - Every factory's full parameter range (all bytes, all 65536 DIDs, sampled 24-bit DTCs × all records; >70 000 requests) passes `RequestGuard.isAllowed`.
  - A full run of every operation on both services sends only the expected allowlisted requests.
- [x] **No bypass** (`NoBypassSourceTest`): scans `diag/src/main/java` and fails on:
  - Any `io.github.samsanjaysatish.macvagdiag.*` reference other than `diag`, `can.TimeSource`, `can.Hex` and the six public transport types (wildcards included).
  - `CanFrame`, `CanAdapter` and `ChannelFactory` by simple name.
  - Reflection (`Class.forName`, `loadClass`, `setAccessible`, `MethodHandles`) and `keepAlive`.
  - Channel `request` / `awaitResponse` calls outside `Exchange`.

  A self-test checks the scanner catches each case. Mutation checks: adding a `can.CanAdapter` import, or disabling the `78` count cap, each made the build fail.
- [x] `FakeDiagnosticServiceTest`, `ValueTypesTest` (`RawBytes`, `DiagTimings` validation, `Nrc` table, defensive copies).
- [ ] **Pending the capture / VCDS log:**
  - **Replay:** the real DTC, identification and freeze-frame exchanges from the capture decode to exactly what the VCDS log shows for the same modules.
  - **VCDS log pairs:** VAG 5-digit ↔ P-code (then implement `VagCodeMapping`), the KWP status byte ↔ VCDS wording (`35-10`, Intermittent/Static), UDS DTC ↔ VCDS's VW number, and freeze-frame fields ↔ VCDS's mileage / date / priority / counters.

## Open questions (need the capture and/or the user)
- **KWP `18` long form (confirmed by the 2026-11-13 capture).** VCDS reads DTCs with `18 02 FF 00` (and `18 00 FF 00` on the engine). **Approved by the user and added to `RequestGuard` on 2026-11-15**: exactly `18 00 FF 00` and `18 02 FF 00`. `DiagRequest.kwpReadDtcsByStatus` still builds `18 xx`; switch it.
- **KWP freeze frame (confirmed by the capture).** VCDS sends `12 00 04 [hi] [lo]`. **Approved by the user and added to `RequestGuard` on 2026-11-15**: `12 00 04 xx xx` (5 bytes, `12 00 04` fixed, any DTC). `readFreezeFrame` still returns `NotAvailable` for KWP until it's built.
- **`22` on TP 2.0 channels.** 03 ABS, 09 BCM and the 19 gateway are on TP 2.0 (KWP2000 channel) but refuse `1A 9B` (`7F 1A 11`) and answer UDS-style `22 [DID]` (one DID per request works). **Approved by the user and added to `RequestGuard` on 2026-11-15**: KWP2000 `22 xx xx` (3 bytes, one DID). `KwpDiagnosticService` doesn't send it yet. Not allowed: VCDS's 13-byte multi-DID form.
- ~~KWP `1A` options and `1A 9B` layout~~: answered above (`1A 9B`, `91`, `97`, `86`; layout decoded). Adding `1A 91` / `1A 97` / `1A 86` needs no allowlist change.
- ~~UDS session~~: VCDS uses `10 03`. Whether `scan` should switch from `10 01` is a `scan` decision. VCDS's Tester Present is `3E 80` on the functional ID `700` (see `transport/CLAUDE.md`). P2/P2* are 50 ms / 2–5 s.
- **UDS freeze frame:** the record layout above is a hypothesis from 5 records; confirm before splitting records.
- ~~EDC17 extended DTC formats~~: none; the EDC17 here is plain KWP2000 with 3-byte DTC records.

## Done when
For every module in the capture, `diag` decodes the same codes, statuses and freeze-frame values that VCDS shows. Values VCDS labels but we can't decode yet are kept as raw.
