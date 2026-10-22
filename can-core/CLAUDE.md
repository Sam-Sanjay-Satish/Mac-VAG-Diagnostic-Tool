# can-core

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** not started. **Package:** `io.github.samsanjaysatish.macvagdiag.can`
**Depends on:** nothing (jSerialComm, SLF4J only). **Used by:** every other project.

## Purpose
Everything about raw CAN frames and nothing about diagnostics. It moves frames between the program and an adapter (real, simulated or replayed) and reads/writes capture files. It knows nothing about TP 2.0, ISO-TP, KWP or UDS.

## What to build
- [ ] **`CanFrame`**: immutable value type: CAN ID, standard (11-bit) vs extended (29-bit) flag, 0–8 data bytes.
  - Validate in the constructor: ID range per flag, data length ≤ 8.
  - A record holding a `byte[]` gets identity-based `equals`. Copy the array in and out, and override `equals`/`hashCode`/`toString` (hex, e.g. `7E0 [8] 02 10 03 00 00 00 00 00`).
  - VW PQ35 diagnostics use 11-bit IDs; support 29-bit anyway, since slcan and captures can contain them.
- [ ] **`TimestampedFrame`**: a frame plus a receive timestamp (monotonic nanos plus wall-clock `Instant` for capture files).
- [ ] **`CanAdapter`** interface (`AutoCloseable`):
  - `open(Bitrate, AdapterMode)`, `close()`, `isOpen()`.
  - `send(CanFrame)`.
  - Receiving: a blocking `receive(Duration timeout)` returning `Optional<TimestampedFrame>`, or a listener. Pick one and keep it the only way. A blocking receive with a timeout is easier to test.
  - `AdapterMode`: `NORMAL`, `LISTEN_ONLY`. **`send` in `LISTEN_ONLY` throws, and nothing reaches the wire.** This is enforced in the shared base logic, not left to each implementation.
- [ ] **Time abstraction**: a small `TimeSource` (monotonic now + wall clock), with a manually advanced fake. Every timeout in every layer uses it so tests never sleep. Expose the fake to other projects' tests via Gradle `java-test-fixtures`.
- [ ] **slcan codec** (pure functions, no I/O): encode commands and frames to ASCII, decode received lines to frames or acknowledgements.
- [ ] **`SlcanAdapter`**: jSerialComm serial port + slcan codec + a reader thread that splits the byte stream into lines (lines can arrive split across reads, or several in one read).
  - Port discovery helper: list candidate ports (CANable shows up as `/dev/cu.usbmodem*`). Use `cu.*`, not `tty.*`, on macOS.
  - Close cleanly: send `C`, stop the reader thread, release the port, even after errors.
- [ ] **`SimulatedAdapter`**: scripted fake. A responder function maps each sent frame to zero or more reply frames. It records every sent frame for assertions and can inject errors, delays (via `TimeSource`) and dropped frames.
- [ ] **`ReplayAdapter`**: feeds frames from a capture file.
  - Passive mode: plays all frames back (for the logger and decoders).
  - Interactive mode: on each `send`, checks it against the next recorded tester frame and releases the recorded ECU replies that followed it. The match must tolerate fields that legitimately differ between VCDS and us. Design this after the sniffing session, once the real differences are known (e.g. TP 2.0 channel IDs and sequence numbers).
  - These fakes live in `main`, not tests: the server can later offer a demo mode that runs on a replay with no car.
- [ ] **Capture files**: use the **candump log format** from Linux `can-utils`, one frame per line: `(1729600000.123456) can0 7E0#0210030000000000`. SavvyCAN, python-can and can-utils can all read it. Write `CaptureWriter` / `CaptureReader`. Comment lines starting with `#` hold metadata (date, car, bitrate, tool version).

## slcan reference (Lawicel protocol)
Commands end with `\r`. The adapter replies `\r` for OK and `\a` (0x07) for error.
- `S0`…`S8`: bitrate (`S6` = 500 kbit/s, the expected VW diagnostic CAN speed). Set it before opening.
- `O`: open normal. `C`: close. `V` / `N`: version / serial.
- Frames: `tIIIL<data>` standard (3 hex ID digits, 1 length digit, 2 hex per byte), e.g. `t7E080210030000000000`. `TIIIIIIIIL<data>` is extended. `r` / `R` are remote frames; we never send those.
- **Listen-only open:** the classic Lawicel spec uses `L`, but the CANable stock firmware may differ (some builds use `M1` for silent mode, or don't support it). **Verify against the exact firmware source/version before the sniffing session** and record the answer here. If the firmware can't do true silent mode, the logger must not open the port at all until we decide what to do.
- Timestamps (`Z1`): check whether the firmware supports them. If not, timestamp on receipt in Java.

## Tests (all without hardware)
- `CanFrame`: validation bounds (ID 0x7FF / 0x800, extended max, length 0 and 8, length 9 rejected), equality on content, defensive copying, hex `toString`.
- slcan codec: encode/decode round trips, hand-written lines for each frame type, lowercase/uppercase hex, malformed lines (wrong length, bad hex, unknown prefix) rejected with a clear error, `\a` error replies.
- Line splitting: a line split across reads, several lines in one read, stray bytes.
- `SlcanAdapter` against a fake serial port (wrap jSerialComm behind a tiny interface so tests can feed bytes). Checks: the open sequence sends the bitrate before open, and listen-only uses the silent command.
- **Listen-only guarantee:** in `LISTEN_ONLY`, `send` throws on every adapter type and the fake serial port receives zero frame bytes.
- `SimulatedAdapter` and `ReplayAdapter` behaviour, including timeouts using the fake `TimeSource` (instant, no sleeping).
- Capture files: write → read round trip, parse sample lines from real `candump -L` output, reject malformed lines with line numbers in the error.
- Hardware (`@Tag("hardware")`, manual only): open the real CANable in listen-only, receive at least one frame from the running car, close cleanly.

## Done when
All of the above pass in CI, the listen-only guarantee is tested, and `tools` can be written using only this project's public API.
