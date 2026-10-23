# can-core

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** built and tested against fakes (148 tests, `./gradlew :can-core:build` green). Blocked on one decision: `SlcanAdapter` refuses `LISTEN_ONLY` because the stock firmware's silent mode doesn't work (see slcan reference). No hardware test yet. **Package:** `io.github.samsanjaysatish.macvagdiag.can`
**Depends on:** nothing (jSerialComm, SLF4J only). **Used by:** every other project.

## Purpose
Everything about raw CAN frames and nothing about diagnostics. It moves frames between the program and an adapter (real, simulated or replayed) and reads/writes capture files. It knows nothing about TP 2.0, ISO-TP, KWP or UDS.

## What to build
- [x] **`CanFrame`**: immutable value type: CAN ID, standard (11-bit) vs extended (29-bit) flag, 0–8 data bytes.
  - Validate in the constructor: ID range per flag, data length ≤ 8.
  - A record holding a `byte[]` gets identity-based `equals`. Copy the array in and out, and override `equals`/`hashCode`/`toString` (hex, e.g. `7E0 [8] 02 10 03 00 00 00 00 00`).
  - VW PQ35 diagnostics use 11-bit IDs; support 29-bit anyway, since slcan and captures can contain them.
  - Done as a final class (not a record). Factories `standard(id, int...)`, `extended(...)`, `standardHex(id, "02 10 03")`, `extendedHex(...)`; `dataByte(i)` reads without copying. `Hex` has the shared bus-style hex helpers.
- [x] **`TimestampedFrame`**: a frame plus a receive timestamp (monotonic nanos plus wall-clock `Instant` for capture files).
- [x] **`CanAdapter`** interface (`AutoCloseable`):
  - `open(Bitrate, AdapterMode)`, `close()`, `isOpen()`.
  - `send(CanFrame)`.
  - Receiving: a blocking `receive(Duration timeout)` returning `Optional<TimestampedFrame>`, or a listener. Pick one and keep it the only way. A blocking receive with a timeout is easier to test.
  - `AdapterMode`: `NORMAL`, `LISTEN_ONLY`. **`send` in `LISTEN_ONLY` throws, and nothing reaches the wire.** This is enforced in the shared base logic, not left to each implementation.
  - Chosen: blocking `receive(Duration)` only. `CanAdapter` is `sealed ... permits AbstractCanAdapter`, and `AbstractCanAdapter` is `sealed ... permits SlcanAdapter, SimulatedAdapter, ReplayAdapter` with `final` public methods and package-private hooks (`connect`, `transmit`, `awaitFrame`, `disconnect`). So no adapter can exist outside the base's checks, and nothing outside this package can reach `transmit`. `send` in `LISTEN_ONLY` throws `ListenOnlyViolationException`. A test checks these modifiers by reflection.
- [x] **Time abstraction**: a small `TimeSource` (monotonic now + wall clock), with a manually advanced fake. Every timeout in every layer uses it so tests never sleep. Expose the fake to other projects' tests via Gradle `java-test-fixtures`.
  - `TimeSource`: `nanoTime()` (monotonic, non-negative), `now()`, `awaitUntil(monitor, deadlineNanos)` (use it in place of `Object.wait`; the caller holds the monitor and loops), `sleepUntil(deadline)`, `deadlineAfter(Duration)` (saturating). `TimeSource.system()` is the real one.
  - `FakeTimeSource` (in `src/testFixtures`): `advance(Duration)` wakes all waiters. `awaitWaiters(n)` lets a multi-threaded test advance only once the other thread is blocked. `FakeTimeSource.autoAdvancing()` jumps each wait straight to its deadline (single-threaded tests only). Other projects: `testImplementation(testFixtures(project(":can-core")))`.
- [x] **slcan codec** (pure functions, no I/O): encode commands and frames to ASCII, decode received lines to frames or acknowledgements. `SlcanCodec` + sealed `SlcanMessage` (`Received`, `RemoteFrame`, `Ack`, `Error`); bad lines throw `SlcanFormatException` quoting the line.
- [x] **`SlcanAdapter`**: jSerialComm serial port + slcan codec + a reader thread that splits the byte stream into lines (lines can arrive split across reads, or several in one read).
  - Port discovery helper: list candidate ports (CANable shows up as `/dev/cu.usbmodem*`). Use `cu.*`, not `tty.*`, on macOS.
  - Close cleanly: send `C`, stop the reader thread, release the port, even after errors.
  - Serial I/O goes through the package-private `SerialPortIo` (real: `JSerialCommPort`; tests: `FakeSerialPort`). `SlcanAdapter.forPort(path[, timeSource])` is the only way to make a real one. Open sequence: `C`, `S<n>`, then `O`. The firmware ignores `S` while it is on the bus, so we close first. Frames are timestamped on receipt in Java. The receive queue holds up to 100 000 frames, then drops new ones (`droppedFrameCount()`). Bad lines, BEL replies and remote frames are counted and skipped (`decodeErrorCount()`, `errorReplyCount()`, `remoteFrameCount()`). An unplug or read error makes `receive` throw, after the frames received before it.
  - `SlcanPortDiscovery.candidates()` returns `/dev/cu.usbmodem*` ports, CANables (USB `16D0:117E`) first. The port description carries the firmware build (`"CANable2 <git version> <remote>"`, from `usbd_desc.c`), so `tools` can put the firmware version in the capture header without sending `V`.
  - **`LISTEN_ONLY` is refused** (throws before the port is opened). See the slcan reference below.
- [x] **`SimulatedAdapter`**: scripted fake. A responder function maps each sent frame to zero or more reply frames. It records every sent frame for assertions and can inject errors, delays (via `TimeSource`) and dropped frames.
  - `Responder`: `List<Reply> respond(CanFrame)`, with `Reply.now(frame)` / `Reply.after(delay, frame)`. Also `sentFrames()`, `openEvents()`, `inject(frame)` / `injectAfter(delay, frame)` (other nodes' traffic), `failNextSends(n, supplier)`, `failNextOpen(supplier)`, `failReceive(error)` (unplug), `dropNextReplies(n)`, `dropRepliesWhere(predicate)`.
- [x] **`ReplayAdapter`**: feeds frames from a capture file.
  - Passive mode: plays all frames back (for the logger and decoders). `ReplayAdapter.passive(capture, Pacing.IMMEDIATE | RECORDED, time)`. Received frames keep the recorded wall-clock time. Sending is refused.
  - Interactive mode: on each `send`, checks it against the next recorded tester frame and releases the recorded ECU replies that followed it. The match must tolerate fields that legitimately differ between VCDS and us. Design this after the sniffing session, once the real differences are known (e.g. TP 2.0 channel IDs and sequence numbers).
    - **Minimal version built:** `ReplayAdapter.interactive(capture, isTesterFrame, matcher, pacing, time)`, where a predicate (usually on CAN ID) marks tester frames. A pluggable `FrameMatcher` decides matches; `FrameMatcher.exact()` is the default. Frames before the first tester frame are released on open. A mismatch throws `ReplayMismatchException` and the replay doesn't advance. **Still to do after the capture:** a tolerant matcher (TP 2.0 channel IDs, sequence numbers, anything else that differs between VCDS and us), and possibly a rule for the case where our request sequence legitimately diverges from VCDS's.
  - These fakes live in `main`, not tests: the server can later offer a demo mode that runs on a replay with no car.
- [x] **Capture files**: use the **candump log format** from Linux `can-utils`, one frame per line: `(1729600000.123456) can0 7E0#0210030000000000`. SavvyCAN, python-can and can-utils can all read it. Write `CaptureWriter` / `CaptureReader`. Comment lines starting with `#` hold metadata (date, car, bitrate, tool version).
  - `CaptureWriter.create(path)` / `new CaptureWriter(writer[, iface])`, with `writeMetadata(key, value)` (`# key: value`), `writeComment`, `write(TimestampedFrame)`, `flush`, `close`. `CaptureReader.read(path | reader)` returns `Capture(metadata, comments, frames)`. Remote, CAN FD and error frames are rejected, and every error is a `CaptureFormatException` with `lineNumber()`.

## slcan reference (Lawicel protocol)
Commands end with `\r`. In the Lawicel spec the adapter replies `\r` for OK and `\a` (0x07) for error. **The CANable 2.0 stock firmware sends no replies at all**: the OK/error transmit lines are commented out in `src/usbd_cdc_if.c`. So `SlcanAdapter` never waits for an ack. The codec still decodes `\r`, `z`/`Z` and `\a` for other slcan devices.
- `S0`…`S8`: bitrate (`S6` = 500 kbit/s, the expected VW diagnostic CAN speed). Set it before opening. In the CANable firmware (`inc/can.h`), `S0`–`S6` = 10k/20k/50k/100k/125k/250k/500k, `S7` = 750k (Lawicel: 800k, so we don't offer it), `S8` = 1M, `S9` = 83.3k. `S` is ignored while on the bus.
- `O`: open normal. `C`: close. `V`: version, which replies `"<git hash> <git remote>\r"`. **`N` (serial) is not implemented** in the CANable firmware.
- Frames: `tIIIL<data>` standard (3 hex ID digits, 1 length digit, 2 hex per byte), e.g. `t7E080210030000000000`. `TIIIIIIIIL<data>` is extended. `r` / `R` are remote frames; we never send those. The CANable 2.0 firmware also sends/accepts CAN FD lines `d`/`D`/`b`/`B`; we reject them on receive and never send them.
- Never send `X`: in canable2-fw it falls through into the frame-transmit path (`case 'X': break;` then `can_tx`).

### Listen-only / silent mode: verified against the firmware source; does NOT work
Source read 2026-10-22:
- **CANable 2.0 stock slcan firmware:** https://github.com/normaldotcom/canable2-fw, branch `main`, commit `b158aa7ab106bc018715cd4d5f02f544f9de1830` (2022-07-07, latest; no tags or releases). Files `src/slcan.c`, `src/can.c`.
- `src/slcan.c`: `case 'M':` (uppercase only, marked `// FIXME: Nonstandard!`). `M1` calls `can_set_silent(1)`, any other `M` calls `can_set_silent(0)`. There is no `L` command; `L` and lowercase `m` fall into `default: return -1`.
- `src/can.c`: `can_set_silent(1)` only does `can_handle.Init.Mode = FDCAN_MODE_BUS_MONITORING` (and only while off-bus). But `can_enable()`, which `O` runs, then sets `can_handle.Init.Mode = FDCAN_MODE_NORMAL;` (line 92) before `HAL_FDCAN_Init`. **So `M1` followed by `O` still opens the controller in normal mode.** `M1` after `O` is ignored, because the adapter is already on the bus.
- The CANable 1.x firmware (https://github.com/normaldotcom/canable-fw, commit `9fddea4ad12b05acc519d90c3771ba5c54a81ea2`) has the same bug: `can_enable()` sets `CAN_MODE_NORMAL` at `src/can.c` line 69.
- **Consequence:** with stock firmware, "listen-only" isn't silent. The controller ACKs every frame, and if it sees bit errors (wrong bitrate, wiring fault) it sends error frames, which can disrupt the car's bus. Not sending frames ourselves isn't enough.
- **What the code does:** the command is kept as `SlcanCodec.SILENT_MODE_COMMAND = "M1\r"` but never sent. `SlcanAdapter.open(..., LISTEN_ONLY)` throws before the port is opened, and a test proves zero bytes reach the serial port. So the logger can't open the port until this is decided, as this guide requires.
- **Open question for the user (don't decide silently):** how to get true silent mode before the sniffing session. Options: (a) build and flash canable2-fw with `can_enable()` keeping the stored mode (a one-line fix, but it's our own firmware build; verify with a scope or a second node that no ACKs are sent); (b) use other firmware that implements silent mode properly, after checking its source the same way; (c) accept normal mode for sniffing (ACKs plus error-frame risk). Not recommended, and it would need an explicit decision plus a code change to allow it. Whatever is chosen, record the exact firmware version here, and pin the check in `SlcanAdapter` (e.g. compare the `V` / USB description version) instead of trusting any CANable.

### Timestamps (`Z1`)
**Not supported** by canable2-fw `b158aa7` (or canable-fw `9fddea4`): there is no `Z` case in `slcan_parse_str`, so it hits `default: return -1`, silently, since no error reply is sent. Received lines never carry a timestamp. `SlcanAdapter` stamps each frame on receipt in Java with the `TimeSource` (monotonic plus wall clock), so expect USB/OS jitter of about a millisecond.

## Tests (all without hardware)
- [x] `CanFrame`: validation bounds (ID 0x7FF / 0x800, extended max, length 0 and 8, length 9 rejected), equality on content, defensive copying, hex `toString`. (`CanFrameTest`)
- [x] slcan codec: encode/decode round trips, hand-written lines for each frame type, lowercase/uppercase hex, malformed lines (wrong length, bad hex, unknown prefix) rejected with a clear error, `\a` error replies. (`SlcanCodecTest`)
- [x] Line splitting: a line split across reads, several lines in one read, stray bytes. (`SlcanLineSplitterTest`)
- [x] `SlcanAdapter` against a fake serial port (wrap jSerialComm behind a tiny interface so tests can feed bytes). Checks: the open sequence sends the bitrate before open, and listen-only uses the silent command. (`SlcanAdapterTest`) **Changed:** since the silent command doesn't work in the stock firmware, the test instead proves `LISTEN_ONLY` is refused before the port is opened, with zero bytes written. Once silent mode is resolved, replace it with a test of the real silent open sequence.
- [x] **Listen-only guarantee:** in `LISTEN_ONLY`, `send` throws on every adapter type and the fake serial port receives zero frame bytes. (`ListenOnlyGuaranteeTest`. It also checks by reflection that the adapter types are sealed/final and the hooks package-private. A mutation check showed that removing the base-class guard fails 3 of these tests.)
- [x] `SimulatedAdapter` and `ReplayAdapter` behaviour, including timeouts using the fake `TimeSource` (instant, no sleeping). (`SimulatedAdapterTest`, `ReplayAdapterTest`, `FakeTimeSourceTest`)
- [x] Capture files: write → read round trip, parse sample lines from real `candump -L` output, reject malformed lines with line numbers in the error. (`CaptureFileTest`. The sample lines follow the `candump -L` format; swap in lines from our own capture once it exists.)
- [ ] Hardware (`@Tag("hardware")`, manual only): open the real CANable in listen-only, receive at least one frame from the running car, close cleanly. **Not written:** it can't pass while `LISTEN_ONLY` is refused. Write it once silent mode is resolved.

## Done when
All of the above pass in CI, the listen-only guarantee is tested, and `tools` can be written using only this project's public API.
