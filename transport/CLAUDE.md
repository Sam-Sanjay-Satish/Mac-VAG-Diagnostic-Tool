# transport

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too, and **this layer is where the read-only guarantee is enforced**.

**Status:** built provisionally against hand-written bytes and fakes, before the sniffing capture (320 tests, `./gradlew :transport:build` green). Every protocol detail below that came from memory is marked **UNCONFIRMED** in code and here, and is kept in named constants so the capture can correct it in one place. Still pending the capture: the replay tests, the hardware test, and confirming or correcting each UNCONFIRMED item. **Package:** `io.github.samsanjaysatish.macvagdiag.transport`
**Depends on:** `can-core`. **Used by:** `diag`.

## Purpose
Turns diagnostic messages (byte arrays of any length) into CAN frames and back, using **VW TP 2.0** and/or **ISO-TP (ISO 15765-2)**. It also holds the **read-only request guard**, the single checkpoint every diagnostic request must pass before reaching the bus.

The capture decides which modules use which transport. Expect TP 2.0 + KWP2000 for most PQ35-era modules, and possibly ISO-TP + UDS for some (e.g. the EDC17 engine ECU).

## Public API (the only things other projects can use)
`NoBypassTest` pins this exact set of public types; adding one fails the test until reviewed.
- [x] **`ApplicationProtocol`**: `KWP2000`, `UDS`.
- [x] **`DiagnosticChannel`** (interface, `AutoCloseable`): the request/response link to one module, used by `diag`:
  - `ModuleRoute route()`, `ApplicationProtocol protocol()`.
  - `Response request(byte[] payload, Duration timeout) throws InterruptedException`: guard check, then send, then wait. Responses left over from an earlier request (e.g. a late answer after a timeout) are discarded first. `timeout` runs from "request sent" to "whole response received", on the factory's `TimeSource`.
  - `Response awaitResponse(Duration timeout) throws InterruptedException`: wait for the next response without sending. This is for NRC `0x78` handling in `diag`.
  - `boolean keepAlive() throws InterruptedException`: TP 2.0 `A3`→`A1` channel test; a no-op (true while open) on ISO-TP. Returns false and closes the channel if the module doesn't answer. **`scan`'s session layer calls this while idle**; the interval is to be taken from the capture.
  - `boolean isOpen()`, `void close()` (TP 2.0 sends `A8`; never throws).
- [x] **`Response`** (sealed): `Received(byte[] payload)` (the whole message, positive or negative, copied in and out; `hex()`, `byteAt(i)`, `length()`), `TimedOut(Duration waited)`, `Failed(Kind kind, String detail)` with `Kind` = `CHANNEL_CLOSED`, `DISCONNECTED_BY_MODULE`, `TRANSPORT_TIMEOUT` (no TP 2.0 ACK after retries / no ISO-TP flow control / CF gap), `RECEIVER_OVERFLOW` (FC `32`), `PROTOCOL_ERROR`, `ADAPTER_ERROR`. **NRC interpretation stays in `diag`**: `7F xx yy` is a plain `Received`.
- [x] **`ModuleRoute`** (sealed): `Tp20(int moduleAddress)` (always KWP2000) and `IsoTp(int requestId, int responseId, ApplicationProtocol protocol)`.
- [x] **`ChannelFactory`**: `create(CanAdapter, TimeSource[, RequestAuditListener])` at app startup; `DiagnosticChannel open(ModuleRoute) throws InterruptedException`. **Every channel it returns is a `GuardedChannel`.** One open channel at a time per factory: channels read the adapter directly, so a second `open` throws `CHANNEL_ALREADY_OPEN` until the first is closed. The adapter must be open in `NORMAL` mode.
- [x] **`TransportException`** (`reason()`: `NO_RESPONSE`, `REFUSED_BY_MODULE`, `DISALLOWED_CAN_ID`, `CHANNEL_ALREADY_OPEN`, `ADAPTER_NOT_READY`, `PROTOCOL_ERROR`, `ADAPTER_ERROR`): `open` failures, and a refused CAN ID.
- [x] **`RequestGuard`** (`isAllowed`, `check`), **`ForbiddenRequestException`** (`protocol()`, `payload()`, `reason()`), **`RequestAuditListener`** (`sending(route, protocol, payload)` before each request is sent, `rejected(route, exception)`).
- Raw channels (`Tp20Channel`, `IsoTpChannel`, `RawChannel`), `BusGate`, `GuardedChannel` and `DiagnosticCanIds` are **package-private and final**. Raw channels never hold the `CanAdapter`; they reach the bus only through `BusGate`.
- **Test fixture for `diag`:** `testImplementation(testFixtures(project(":transport")))` gives `io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel`. It holds scripted canned responses (`on("19 02 FF", "7F 19 78", "59 02 ...")`: the first answer comes from `request`, the rest from `awaitResponse`; also `onTimeout`, `on(hex, Response...)`, and `keepAliveResult`). It records calls with their timeouts (`calls()`, `requestsHex()`), and **it applies the real `RequestGuard`**, so every request a `diag` test makes is allowlist-checked.

## Request guard (read-only allowlist)
- [x] **`RequestGuard`** checks every outgoing payload **before** segmentation:
  - `GuardedChannel` copies the payload once, checks the copy, and sends that same copy, so the caller can't change the bytes after the check.
  - Key = (application protocol of the route, exact leading bytes, exact total length). Allowed → passes through. Anything else → `ForbiddenRequestException`, logged at WARN, passed to `RequestAuditListener.rejected`. Nothing is sent.
  - Unknown/null protocol, null payload, empty payload, unknown service, unlisted sub-function and wrong length all default to reject.
  - The allowlist is the constant `RequestGuard.ALLOWLIST`: no config file, no setter, no environment variable. `RequestGuardTest.allowlistIsPinned` pins its exact contents.
  - If `RequestAuditListener.sending` throws, the request is not sent. This keeps the rule "no request reaches the car without an audit record".

### Approved allowlist
**Approved by the user in the conversation on 2026-10-22, provisional until checked against what VCDS sends in the capture.** The starting list from this guide was approved **minus KWP `21 xx` (measuring blocks)**, which is deliberately left out of v1. Every entry is UNCONFIRMED until the capture. Any addition or widening (including longer forms of an existing service) needs the user's explicit approval again.

| Protocol | Request | Exact length | What is checked |
|---|---|---|---|
| KWP2000 | `10 89` VW diagnostic session | 2 | SID and session byte exact |
| KWP2000 | `1A xx` read ECU identification | 2 | SID; any one option byte |
| KWP2000 | `18 xx` read DTCs by status | 2 | SID; any one status byte |
| KWP2000 | `12 xx` read freeze frame | 2 | SID; any one parameter byte |
| KWP2000 | `3E` tester present | 1 | SID only, nothing may follow |
| UDS | `10 01` default session | 2 | SID and sub-function exact |
| UDS | `10 03` extended session | 2 | SID and sub-function exact |
| UDS | `3E 00`, `3E 80` tester present | 2 | SID and sub-function exact |
| UDS | `19 01 mm`, `19 02 mm` | 3 | SID and sub-function exact; any mask |
| UDS | `19 04 dd dd dd rr`, `19 06 dd dd dd rr` | 6 | SID and sub-function exact; any DTC/record |
| UDS | `22 hh ll` read one DID | 3 | SID; any DID; only one DID per request |

Why the length checks are strict:
- **Session control** checks the sub-function, so `10 02` / `10 82` (UDS programming), `10 85` (KWP programming), `10 81`, `10 86`, `10 04`, a UDS `10 89` and a KWP `10 03` are all refused. No suppress-bit variants are allowed.
- **`3E`** allows only the listed forms (KWP `3E` alone; UDS `3E 00` / `3E 80`). `3E 01`, `3E 02` and `3E 81` are refused.
- **`19`** allows only sub-functions `01 02 04 06`, each at its ISO 14229 length.
- **`18`, `1A`, `12` accept any parameter byte, but exactly one.** Reason: none of these services has a sub-function that writes, resets or changes state; the byte only picks which status group, identification block or freeze frame is read. Which values VCDS uses is for the capture to tell, and narrowing to an unconfirmed subset wouldn't add safety. Pinning the length stops extra bytes from reaching an ECU with nonstandard parsing. **Note:** ISO 14230 also defines longer forms that `diag` may need, e.g. `18 02 FF 00` (status plus group) and `12` with a record-access method. These are refused today. If the capture shows VCDS uses them, they need the user's approval.
- **`22`**: any DID, but one DID per request.

Never allowed (each has a test, under both protocols): see the root `CLAUDE.md` list, plus programming sessions, plus anything not in the table. `GuardRejectionTest` sends each through `ChannelFactory` on a `SimulatedAdapter` and asserts **zero frames on the wire**. `RequestGuardTest.exhaustiveSweep` checks every SID × every second byte × lengths 1–8, for both protocols, against an independent restatement of the table.

- [x] **Defense in depth (`BusGate`, `DiagnosticCanIds`):** every frame is checked before `CanAdapter.send`. It must be 11-bit, it must be the one ID this channel was permitted, and that ID must be in the fixed set. Otherwise the frame is refused (`DISALLOWED_CAN_ID`, logged at ERROR) and nothing is sent. The fixed set (all **UNCONFIRMED**):
  - TP 2.0 setup `200`.
  - TP 2.0 IDs negotiated in the setup reply, accepted only in `740..7BF`.
  - The known UDS request IDs `7E0` (→`7E8`) and `7E1` (→`7E9`).

  The OBD functional ID `7DF` is never in the set. A TP 2.0 channel loses its permit for `200` once setup is done. An `IsoTp` route not in the known pairs is refused at `open`.

## VW TP 2.0 (all UNCONFIRMED: from memory, confirm every byte against the capture)
Constants live in `Tp20Channel` and `DiagnosticCanIds`.
- **Channel setup:** tester on `200`: `[addr] C0 00 10 00 03 01`. That proposes tester-rx `300`, lets the module choose our tx (`00 10` = invalid flag), and sets app type `01` = KWP. The module answers on `200 + addr`: `00 D0 [rx lo] [rx hi] [tx lo] [tx hi] 01`, e.g. `00 D0 00 03 40 07 01` = we receive on `300` and send on `740`. In the ID fields the low byte comes first, the high byte's bits 0–2 are ID bits 8–10, and `0x10` marks the ID as invalid. `D6..D8` mean refused. The setup timeout is 200 ms. Module addresses are accepted in `01..7F`.
- **Timing:** we send `A0 0F 8A FF 32 FF` (block size 15, T1 100 ms, T3 5 ms). From the module's `A1 [bs] [T1] [T2] [T3] [T4]` we use its block size (clamped 1..15) as our block size, T1 as our ACK timeout (100 ms if zero) and T3 as the minimum gap between our data packets within a message. Time byte: bits 7–6 are the unit (0.1 / 1 / 10 / 100 ms), bits 5–0 the count.
- **Data:** `[op|seq]`, with op `0` = more + ACK, `1` = last + ACK, `2` = more, `3` = last. ACKs are `B[seq+1]` (ready) and `9[seq+1]` (not ready). The first packet carries a 2-byte big-endian length plus 5 data bytes; later packets carry 7. Each side counts its own sequence numbers mod 16 from channel open, across messages. No padding.
- **Retries:** an unacknowledged block is resent up to 2 times with the same sequence numbers, then the result is `TRANSPORT_TIMEOUT` and `A8`. Up to 10 not-ready ACKs in a row are accepted. A wrong ACK sequence, a wrong receive sequence or a length mismatch gives `PROTOCOL_ERROR` and closes the channel with `A8`.
- **Keep-alive:** `A3` → `A1` within 200 ms. If the module sends `A3` (or `A0`), we answer `A1` with our timing.
- **Disconnect:** `close()` sends `A8` and waits up to 200 ms for `A8`. If the module sends `A8`, we answer `A8` and return `DISCONNECTED_BY_MODULE`.
- [x] Handled: sequence-number wraparound, ACK timeouts, block size, not-ready ACKs, the module disconnecting, capped retries, stale responses (ACKed, then discarded).

## ISO-TP
Constants live in `IsoTpChannel` and `IsoTpChannel.Parameters.DEFAULTS`.
- [x] Single frame `0L`, first frame `1L LL` (12-bit length, max 4095), consecutive frame `2N` (1..F, then 0), flow control `30 BS STmin` / `31` wait / `32` overflow. Frame formats follow ISO 15765-2.
- [x] We respect the module's BS and STmin when sending (`00..7F` ms, `F1..F9` 100–900 µs, reserved values → 127 ms). The first CF goes right after FC.
- [x] Our own FC when receiving: `30 00 00` (BS 0, STmin 0) **UNCONFIRMED**. If BS > 0 we send FC again after every BS frames.
- **UNCONFIRMED:** padding `AA` on every frame we send, N_Bs = N_Cr = 1000 ms (ISO defaults), at most 10 wait frames, IDs `7E0/7E8` and `7E1/7E9`.
- Errors: wrong CF sequence → `PROTOCOL_ERROR` (channel stays usable); no FC → `TRANSPORT_TIMEOUT`; CF gap > N_Cr → `TRANSPORT_TIMEOUT`; FC `32` → `RECEIVER_OVERFLOW`. Invalid SF/FF, stray FC/CF and frames on other IDs are ignored. A new SF or FF replaces a message in progress. Frames waiting before a request are discarded.
- UDS `3E 80` gets no response by design: `request` returns `TimedOut` after the caller's timeout. `scan` should use a short timeout for it.

## Tests (all without hardware)
- [x] **Guard rejection tests** (`GuardRejectionTest`, `RequestGuardTest`): parameterized over every forbidden service and sub-function in the root list (UDS and KWP, each also under the other protocol), the programming sessions, and about 55 "unknown" shapes (including KWP `21 xx`). Each test checks that the request is rejected, **zero frames reach the `SimulatedAdapter`**, and the audit listener sees it. Allowed requests pass and reach the bus. Unknown/null protocol and empty/null payload are rejected. There is also an exhaustive sweep and an allowlist pin.
- [x] **No bypass** (`NoBypassTest`, reflection over the compiled main classes): the public types are exactly the reviewed set; raw classes are package-private and final with no public constructors; no public API member takes or returns a raw channel, `BusGate` or `CanFrame`; only `ChannelFactory.create` takes a `CanAdapter`; `GuardedChannel` is the only `DiagnosticChannel` in main; raw channels hold no `CanAdapter`; `ChannelFactory.open` returns a `GuardedChannel` for every `ModuleRoute` subtype. A mutation check (guard call disabled) failed 174 tests; disabling the CAN ID checks failed 11.
- [x] TP 2.0 with hand-written frames (`Tp20ChannelTest`): setup, parameter exchange, refusal, timeouts, disallowed negotiated IDs, single- and multi-packet messages both ways, block size, T3 pacing, ACK handling (not ready, retries, wrong sequence), wraparound in both directions, keep-alive, disconnect from both sides, response pending, stale responses, adapter failure. All on `FakeTimeSource`, no sleeping.
- [x] ISO-TP with hand-written frames (`IsoTpChannelTest`): SF, FF+CF with flow control, our and the module's block size, STmin (ms and µs), wait frames (and too many), overflow, sequence errors, wraparound both ways, FC / CF / response timeouts, response pending, noise, padding on and off.
- [x] `ChannelFactoryTest`: end to end on both transports, one channel at a time, adapter not ready, refused routes, the audit listener (and an audit failure blocking the send), payload copying, and the `BusGate` ID set.
- [ ] **Replay (pending the capture):** run real channel setups and message exchanges from the sniffing capture through `ReplayAdapter` in interactive mode. This needs the tolerant `FrameMatcher` noted in `can-core`. It should cover at least one TP 2.0 setup + `10 89` + `1A 9B` + `18 xx` exchange, one UDS exchange if the engine uses UDS, and a keep-alive. Then correct the UNCONFIRMED constants and hand-written frames to match.
- [ ] Hardware (`@Tag("hardware")`, manual): open one TP 2.0 channel to the gateway, keep it alive 10 s, disconnect cleanly. No diagnostic requests beyond tester present. Not written yet.

## UNCONFIRMED (correct from the capture, then update this list)
- **Allowlist:** every entry, and whether VCDS uses longer forms of `18` / `12` / `1A` or a parameter on KWP `3E`.
- **TP 2.0:** setup ID `200`, reply ID `200 + addr`, address range `01..7F`, setup layout and `D0`/`D6..D8` opcodes, app type `01`, proposed tester-rx `300`, the negotiated-tx range `740..7BF`, the `A0`/`A1`/`A3`/`A8` opcodes, our timing bytes `0F 8A FF 32 FF`, the time-byte encoding and which of the module's T1/T3 we should honour, the data opcodes and ACK numbering, the big-endian 2-byte length (and whether its top bits are flags), the 200 ms setup/control timeouts, 100 ms default ACK timeout, 2 block retries and 10 not-ready ACKs.
- **ISO-TP:** UDS IDs `7E0/7E8`, `7E1/7E9`, padding `AA`, our FC `30 00 00`, N_Bs/N_Cr 1000 ms, 10 wait frames.
- **Mapping:** how VCDS module numbers (01, 02, 03, 17, 19, ...) map to TP 2.0 logical addresses, and which modules use TP 2.0 or ISO-TP. That belongs to `scan`'s discovery; this layer only takes a `ModuleRoute`.

## Open questions (answer from the capture, then update this file)
- Which modules use TP 2.0 vs ISO-TP. The module address → CAN ID mapping. Padding. The exact VCDS session and timing parameters. How often VCDS sends `A3` and Tester Present.
- `can-core` is an `api` dependency of this project, so `diag` and `scan` can see the public `CanAdapter.send` at compile time. The guard can only be bypassed by code that holds the adapter, and only app startup creates one (root rule: real hardware is created in exactly one place). If you want this enforced rather than left to convention, a later check in `diag` and `scan` could assert they never reference `CanAdapter`, or the dependency could become `implementation`. The second option would mean `ChannelFactory.create`'s signature can't mention can-core types.
