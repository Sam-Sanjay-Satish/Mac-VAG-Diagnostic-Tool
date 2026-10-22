# transport

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too, and **this layer is where the read-only guarantee is enforced**.

**Status:** not started; wait for the sniffing-session capture. **Package:** `io.github.samsanjaysatish.macvagdiag.transport`
**Depends on:** `can-core`. **Used by:** `diag`.

## Purpose
Turns diagnostic messages (byte arrays of any length) into CAN frames and back, using **VW TP 2.0** and/or **ISO-TP (ISO 15765-2)**. It also holds the **read-only request guard**, the single checkpoint every diagnostic request must pass before reaching the bus.

The capture decides which modules use which transport. Expect TP 2.0 + KWP2000 for most PQ35-era modules, and possibly ISO-TP + UDS for some (e.g. the EDC17 engine ECU).

## Public API (the only things other projects can use)
- [ ] **`DiagnosticChannel`** interface: the request/response link to one module, used by `diag`:
  - `Response request(byte[] payload, Duration timeout)` sends one message and returns the response message bytes (or a timeout/error result).
  - Also needs: wait for further responses without sending (needed for "response pending" handling in `diag`), `close()`, and the channel's `ApplicationProtocol` (`KWP2000` or `UDS`).
  - Tests in `diag` use a fake `DiagnosticChannel` with canned responses, so no CAN frames are involved there.
- [ ] **`ChannelFactory`**: opens a `DiagnosticChannel` to a module by address. **Every channel it returns is wrapped by the `RequestGuard`.**
- Raw TP 2.0 / ISO-TP channel classes are **package-private**. Nothing outside this package can send unguarded.

## Request guard (read-only allowlist)
- [ ] **`RequestGuard`** checks every outgoing payload **before** segmentation:
  - Key = (application protocol, service ID, sub-function or first parameter byte where it matters).
  - Allowed → passes through. Anything else → rejected with a specific exception, logged at WARN, and recorded in the audit log by the layer above. Nothing is sent.
  - The allowlist is a fixed constant in code: no config file, no setter, no environment variable.
- **Starting allowlist** (from memory; confirm each entry against what VCDS sends in the capture, and get user approval for every addition):
  - KWP2000: `10 89` (VW diagnostic session; confirm). `1A xx` read ECU identification. `18 xx` read DTCs by status. `12 xx` read freeze frame. `21 xx` read measuring block (v2). `3E` tester present.
  - UDS: `10 01` default session. `10 03` extended session. `3E 00` / `3E 80` tester present. `19 01` / `19 02` / `19 04` / `19 06` read DTC info. `22 xx xx` read data by identifier.
  - Never allowed: see the root `CLAUDE.md` list, plus programming sessions (`10 02` UDS, `10 85` KWP) and anything not on the allowlist.
- [ ] **Defense in depth:** the transport also refuses to put frames on CAN IDs outside the expected diagnostic set. That set is: TP 2.0 channel setup IDs, IDs negotiated during a TP 2.0 channel setup, and the known UDS request IDs.

## VW TP 2.0 (from memory; confirm every byte against the capture)
- **Channel setup:** the tester sends on `0x200`: `[module address] C0 00 10 [rx id lo] [rx id hi] 01`. The module replies on `0x200 + address` with `D0` and the CAN IDs to use for the channel.
- **Timing parameters:** `A0 ...` request, `A1 ...` response (block size, ACK timeout, minimum packet interval).
- **Data frames:** the first byte is an opcode nibble plus a 4-bit sequence number:
  - `0x0_` = more packets follow, expects ACK.
  - `0x1_` = last packet, expects ACK.
  - `0x2_` = more packets follow, no ACK.
  - `0x3_` = last packet, no ACK.
  - `0xB_` = ACK, ready.
  - `0x9_` = ACK, not ready.
  - The first data packet carries a 2-byte message length.
- **Keep-alive:** `A3` channel test → `A1` reply. This is TP 2.0's own keep-alive, separate from KWP `3E` tester present.
- **Disconnect:** `A8` from either side.
- Handle: sequence-number wraparound, ACK timeouts, block size, not-ready ACKs, the module disconnecting, retries capped.

## ISO-TP
- Single frame `0L`. First frame `1L LL` (12-bit length). Consecutive frame `2N` (sequence 1..F, then 0). Flow control `30 BS STmin` / `31` wait / `32` overflow.
- VW 11-bit addressing, e.g. engine request `0x7E0`, response `0x7E8` (confirm per module in the capture).
- Padding byte value: confirm from the capture (VW often pads with `0xAA` or `0x55`). Respect the module's STmin and block size.

## Tests (all without hardware)
- **Guard rejection tests** (required by the root `CLAUDE.md`): one parameterized test per forbidden service and sub-function, for both KWP and UDS:
  - The request is rejected and zero frames reach the `SimulatedAdapter`.
  - Allowed requests pass.
  - An unknown protocol/service defaults to rejected.
- **No bypass:** a reflection- or ArchUnit-style test that raw channel classes aren't public and that `ChannelFactory` only returns guarded channels.
- TP 2.0 with hand-written frames: setup, parameter exchange, single- and multi-packet messages, ACK handling, sequence wraparound, keep-alive, disconnect, timeouts (fake `TimeSource`).
- ISO-TP with hand-written frames: SF, FF+CF with flow control, block size, STmin, wait frames, overflow, sequence errors, timeouts.
- **Replay:** run real channel setups and message exchanges from the sniffing capture through `ReplayAdapter` in interactive mode.
- Hardware (`@Tag("hardware")`, manual): open one TP 2.0 channel to the gateway, keep it alive 10 s, disconnect cleanly. No diagnostic requests beyond tester present.

## Open questions (answer from the capture, then update this file)
- Which modules use TP 2.0 vs ISO-TP. The module address → CAN ID mapping. Padding. The exact VCDS session and timing parameters.
