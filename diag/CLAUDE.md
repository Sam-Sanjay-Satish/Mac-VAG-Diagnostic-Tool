# diag

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** not started; wait for `transport` and the capture. **Package:** `io.github.samsanjaysatish.macvagdiag.diag`
**Depends on:** `transport` (only the `DiagnosticChannel` interface and `ChannelFactory`). **Used by:** `scan`.

## Purpose
Speaks the diagnostic application protocols, **KWP2000** and **UDS (ISO 14229)**, over a `DiagnosticChannel`. It builds read-only requests, parses responses into typed results, and decodes DTCs, status bytes, freeze frames and ECU identification. It never sees a CAN frame.

## What to build
- [ ] **`DiagnosticService`** interface, with `KwpDiagnosticService` and `UdsDiagnosticService` behind it:
  - `readIdentification()` → `EcuIdentification`: part number, software version, hardware number, system name, coding, VIN where the module provides it.
  - `readDtcs()` → `List<Dtc>`.
  - `readFreezeFrame(Dtc)` → `FreezeFrame`.
  - (v2) read measuring blocks / data identifiers.
  - Session start/stop belongs to `scan`, which calls into here. The services expose the request but don't decide when.
- [ ] **Result model** (records and sealed interfaces):
  - `Dtc`: protocol, raw bytes, display code (`P0401` style where applicable, VAG 5-digit for KWP), status.
  - `DtcStatus`: raw byte plus decoded flags.
  - `FreezeFrame`: a list of raw records, plus decoded fields where known. Mileage, date/time, priority, occurrence and reset counters are what VCDS shows.
  - `EcuIdentification`.
  - Results distinguish success, negative response (with the NRC) and timeout. Use a sealed result type or specific exceptions, consistently.
- [ ] **Unknown data stays raw.** Never drop bytes we can't decode. Keep them with their IDs (e.g. "record 0x01, bytes 1A 2B") so the report and MCP output can still show them. Descriptions/labels are a later problem.
- [ ] **Negative responses** `7F [SID] [NRC]`:
  - `0x78` response pending → wait for the real response (extended timeout via `TimeSource`). Don't resend.
  - `0x21` busy, repeat request → let `scan`'s back-off decide; return a retryable result.
  - `0x11` / `0x12` / `0x31` / `0x22` (not supported / sub-function not supported / out of range / conditions not correct) → a typed result with a readable message.

## Protocol notes (from memory; confirm everything against the capture and the VCDS log before relying on it)
- **UDS DTC status byte** (ISO 14229):
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
  - `19 04 [DTC] [record]` → snapshot (freeze frame).
  - `19 06 [DTC] [record]` → extended data (VW: counters, mileage, priority; confirm).
  - Identification via `22 F1 87` / `F1 89` / `F1 91` / `F1 97` / `F1 90` (VIN) etc.; confirm which DIDs VCDS reads.
- **KWP2000 (VW):**
  - `18 [status/group]` → `58 [count] ([DTC 2 bytes][status])...`.
  - `12 ...` freeze frame.
  - `1A 9B` ECU identification.
  - VAG 5-digit codes (e.g. 16684 for P0300) relate to P-codes by an offset scheme. Derive and test it from VCDS log pairs; don't hard-code it from memory.
- The EDC17 may return extended DTC formats. Check against the capture.

## Tests (all without hardware)
- Every parser driven by **hand-written response bytes**: zero DTCs, one, many; truncated responses; length mismatches; unknown status bits; raw fields preserved.
- Status byte decoding: every bit individually, plus combinations.
- DTC formatting: P/C/B/U prefixes, and VAG 5-digit ↔ P-code using real pairs from the VCDS log.
- Negative responses: each NRC above; `0x78` pending followed by the real response, using a fake `DiagnosticChannel` and the fake `TimeSource` (no sleeping).
- **Requests built here are allowlisted:** every request this layer can produce passes the `transport` `RequestGuard`. A test enumerates them and checks.
- Replay: the real DTC and freeze-frame exchanges from the capture decode to exactly what the VCDS log shows for the same modules.

## Done when
For every module in the capture, `diag` decodes the same codes, statuses and freeze-frame values that VCDS shows. Values VCDS labels but we can't decode yet are kept as raw.
