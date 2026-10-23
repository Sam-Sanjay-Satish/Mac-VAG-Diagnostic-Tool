# diag

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** built provisionally against hand-written bytes and fakes, before the sniffing capture (128 tests, `./gradlew :diag:build` green). Every protocol detail below that came from memory is marked **UNCONFIRMED** in code and here, and is kept in named constants so the capture can correct it in one place. Still pending the capture: the replay tests, the VCDS-log tests, and confirming or correcting each UNCONFIRMED item. **Package:** `io.github.samsanjaysatish.macvagdiag.diag`
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

## UNCONFIRMED (correct from the capture / VCDS log, then update this list)
- **KWP DTC request:** status byte `02` (`KwpDiagnosticService.DTC_STATUS_PARAMETER`), and whether VW modules accept the one-byte `18 xx` form at all (see open questions).
- **KWP DTC record:** 3 bytes (`[hi][lo][status]`) after `58 [count]`.
- **KWP display code:** 5-digit decimal of the 16-bit value (`DtcCodes.vagFiveDigit`, `41 2C` → `16684`).
- **KWP status byte, every bit** (`KwpDtcStatus`): bit 7 = intermittent, bits 0–6 = symptom / elaboration code. This is from memory of VCDS's `35-10 ... Intermittent` display. ISO 14230-3 statusOfDTC has a different layout (storage state, warning lamp, readiness), and it isn't known which one VW follows. `raw()` is authoritative.
- **KWP identification:** options `9B` and `90` (`IDENTIFICATION_OPTIONS`); the `1A 9B` block layout (not decoded); `1A 90` = 17 ASCII characters.
- **UDS DTC request:** status mask `FF` (`UdsDiagnosticService.DTC_STATUS_MASK`).
- **UDS freeze frame:** record `FF` = all for both `19 04` and `19 06` (`SNAPSHOT_RECORD_ALL`, `EXTENDED_DATA_RECORD_ALL`); which records VCDS reads; where mileage, date/time, priority and counters live.
- **UDS identification DIDs:** `F187` / `F189` / `F191` / `F197` / `F190` (ISO 14229-1 names), and `0600` as VW coding (not ISO; from memory). Which DIDs VCDS actually reads.
- **UDS status bit layout** (ISO 14229-1, from memory) and how VCDS words the combinations.
- **Timings** (`DiagTimings`):
  - Request timeout 1000 ms.
  - Response-pending timeout 5000 ms (ISO P2*server).
  - At most 20 `78`s and 30 s total after the first `78`.
- **NRC names** in `Nrc`: from memory of ISO 14229-1 / 14230-3.

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
- **Needs the user's approval if the capture confirms it: KWP `18` long form.** ISO 14230 reads DTCs with `18 [status] [group hi] [group lo]`, e.g. `18 02 FF 00` (all groups). I believe VW modules expect this 4-byte form. The approved allowlist allows only `18 xx`, so `diag` sends `18 02`. If the capture shows VCDS sending `18 02 FF 00`, the allowlist would need that exact shape, and only with the user's explicit approval.
- **Needs the user's approval if the capture confirms it: KWP freeze frame.** ISO 14230 `12` is `12 [freeze frame number] [record access method] [...]`, so naming a DTC needs more than one byte. The allowlisted `12 xx` can't name a DTC, so `KwpDiagnosticService.readFreezeFrame` sends nothing and returns `NotAvailable`. The capture should show how VCDS reads KWP environment data (`12` long form, `18` with other parameters, or `17`). Any new shape needs the user's approval.
- **KWP `1A`:** whether VCDS uses other options (e.g. `1A 91`, `1A 86`), and the `1A 9B` field layout (part number, component name, coding, workshop code).
- **UDS:** which session VCDS uses before reading (`10 03`?). Whether `scan` should use `3E 80` (allowlisted, not produced here; it gets no response by design). Whether to decode P2/P2* from `50 03 [P2 hi] [P2 lo] [P2* hi] [P2* lo]` and feed them into `DiagTimings`.
- **UDS freeze frame:** VW record numbers and DID layouts, so records can be split and fields decoded.
- **EDC17:** extended DTC formats, if any.

## Done when
For every module in the capture, `diag` decodes the same codes, statuses and freeze-frame values that VCDS shows. Values VCDS labels but we can't decode yet are kept as raw.
