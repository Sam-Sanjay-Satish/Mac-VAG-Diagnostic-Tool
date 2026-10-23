# tools

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** `can-log`, `list-ports` and `capture-info` built and tested against fakes (62 tests, `./gradlew :tools:build` green). **`can-log` can't be used on the car yet:** it opens the adapter only in `LISTEN_ONLY`, and `SlcanAdapter` refuses that because the CANable 2.0 stock firmware has no working silent mode (see `can-core/CLAUDE.md`, "Listen-only / silent mode"). On the real adapter it exits 1 at open with that explanation, having transmitted nothing and created no file. Fixture sanitizer not started (needs `transport`). **Package:** `io.github.samsanjaysatish.macvagdiag.tools`
**Depends on:** `can-core` only (later also `transport` for the fixture sanitizer). **Used by:** nothing; these are command-line programs.

## Purpose
Command-line utilities that support development, mainly the **listen-only CAN logger** for the sniffing session. It comes right after `can-core` because the real capture must exist before the protocol layers can be built and tested against it.

## What to build
- [x] **`can-log`, the listen-only logger** (Gradle `application` plugin, runnable with `./gradlew :tools:run --args=...` or as an installed script).
  - Arguments: serial port (or auto-detect if exactly one CANable is present), bitrate (default 500 kbit/s), output file (default: `local/captures/<date>-<time>.log`).
  - **Opens the adapter only in `LISTEN_ONLY` mode, with no option to change that.** There is no code path in this program that calls `send`.
  - Writes candump log format via `can-core`'s `CaptureWriter`. Flushes regularly so a crash or unplugged cable loses at most a second of data.
  - Writes a metadata header: date, bitrate, firmware version, tool version, free-text note (`--note "VCDS auto-scan, engine off"`).
  - Live console summary every few seconds: frames/sec, total frames, distinct IDs, errors. Logs go to stderr.
  - Ctrl-C: close the adapter, flush and close the file, print the final stats.
  - **Built** as `CanLogger` (core loop) + `CanLogCommand` (arguments, port choice, Ctrl-C hook) + `CanLogOptions` + `PortChoice`.
    - The mode is the constant `CanLogger.MODE = LISTEN_ONLY`; there is no option for it, and `start()` re-checks `adapter.mode()` after opening. `NoTransmitSourceTest` scans `tools/src/main/java` and fails on any `send(` call or mention of `NORMAL`.
    - `start()` opens the adapter first and only then creates the file, so a refused open leaves no file. An existing output file is never overwritten (exit 2). Output outside a `local/` directory prints a VIN warning.
    - Options: `--port`, `--bitrate` (`500000` or `500k`), `--output`, `--note`, `--duration <seconds>` (optional; added so a run can end by itself, used by the hardware test). `--opt value` and `--opt=value` both work.
    - Header (`# key: value`): `date` (ISO, local zone), `bitrate`, `mode: LISTEN_ONLY`, `port`, `firmware`, `tool: macvagdiag-tools 0.1.0-dev`, `note` (only if given), plus two plain comments (format, VIN warning).
    - Firmware comes from the USB description of the matching discovered port (`"CANable2 <git version> <remote>"`), never from sending `V`. Unknown or non-CANable ports give `unknown`. **To verify on the Mac:** that jSerialComm's `getPortDescription()` really returns the firmware's USB product string.
    - Flush every 1 s, summary every 5 s (`can-log: 0:00:05  0.6 frames/s  3 frames  2 IDs  0 errors`), both timed on the `TimeSource`. Errors = `SlcanAdapter` decode errors + error replies + discarded lines + dropped frames, plus capture write errors.
    - `shutdown()` is the Ctrl-C hook: sets the stop flag, closes the adapter (which wakes a blocked `receive`), flushes and closes the file, and prints the final stats once. It's idempotent and thread-safe. An adapter failure (unplug) keeps the frames received before it, then goes through the same shutdown, exit 1.
    - Console output goes to `System.err` directly. can-core's SLF4J logs also go to stderr through `slf4j-simple` (`runtimeOnly`, approved by the user on 2026-10-22), configured in `src/main/resources/simplelogger.properties`.
- [x] **`list-ports`**: print serial ports that look like a CANable, to find the right `/dev/cu.usbmodem*`.
  - **Built** (`ListPortsCommand`): path, `CANable` marker, `VID:PID`, USB description; CANables first. It only enumerates ports (opens nothing). Exit 1 if there are no ports.
- [x] **`capture-info`**: summarize a capture: duration, frame count, IDs seen with counts, first/last timestamps. Used to sanity-check a sniffing session before leaving the car.
  - **Built** (`CaptureInfo` + `CaptureInfoCommand`): header metadata, frame count, first/last timestamps (local zone), duration, largest gap between frames (a long gap may mean a dropped connection), frames per ID (standard first, then extended). Exit 1 for an empty, missing or malformed capture (malformed names the line).
- [ ] **Later: fixture sanitizer** (needs `transport`). Masks the VIN in captures before they're committed. The VIN is 17 ASCII bytes split across several CAN frames inside TP 2.0 / ISO-TP messages, so a plain per-frame search won't find it. It has to reassemble messages, mask, then rewrite frames. Also masks the VIN in VCDS text logs (easy: text replace). Until this exists, no capture gets committed; raw captures stay in `local/`.

## How to run
Requires JDK 25. `./gradlew :tools:run` uses the Gradle toolchain automatically. The installed script uses `JAVA_HOME` (or `java` on `PATH`), so point it at a JDK 25, e.g. the one Gradle provisioned under `~/.gradle/jdks/`.

- Via Gradle (working directory = repo root, so the default output lands in `local/captures/`):
  - `./gradlew :tools:run --args="list-ports"`
  - `./gradlew :tools:run --args="can-log --note 'VCDS auto-scan, engine off'"`
  - `./gradlew :tools:run --args="capture-info local/captures/2026-10-22-143005.log"`
  - A non-zero exit code makes Gradle report `BUILD FAILED`; that's expected for e.g. `list-ports` with nothing plugged in.
- Installed script (preferred for the sniffing session: Ctrl-C goes straight to the program and runs the shutdown hook, without Gradle in between): `./gradlew :tools:installDist`, then from the repo root:
  - `tools/build/install/macvagdiag-tools/bin/macvagdiag-tools list-ports`
  - `tools/build/install/macvagdiag-tools/bin/macvagdiag-tools can-log [--port <path>] [--bitrate 500k] [--output <file>] [--note <text>] [--duration <s>]`
  - `tools/build/install/macvagdiag-tools/bin/macvagdiag-tools capture-info <file>`
- `<command> --help` prints each command's options. Exit codes: 0 OK, 1 failure, 2 bad arguments.

## Tests (all without hardware)
- [x] The logger run against `SimulatedAdapter` / `ReplayAdapter`: the output file contains every frame in order, in valid candump format, with the header. (`CanLoggerTest`, `CanLogCommandTest`: byte-for-byte frame lines, plus the header read back with `CaptureReader`.) Also periodic flush, the 5 s summary, unplug, write failure and refused open, all on `FakeTimeSource`.
- [x] **The logger never transmits:** the adapter records zero sent frames, and it was opened in `LISTEN_ONLY`. (`CanLoggerTest.neverTransmitsAndOpensOnlyInListenOnly`, `CanLogCommandTest`, and the source scan `NoTransmitSourceTest`.)
- [x] Argument parsing: defaults, bad bitrate, missing port, output path in `local/`. (`CanLogOptionsTest`, `PortChoiceTest`, `CanLogCommandTest`: also auto-detect, several CANables, no overwrite, unknown options.)
- [x] Shutdown path flushes and closes (simulate Ctrl-C by calling the shutdown hook logic directly). (`CanLoggerTest`: called directly, and from another thread while the loop is blocked in `receive`.)
- [x] `capture-info` on a small hand-written capture. (`CaptureInfoTest`; `ListPortsCommandTest` covers `list-ports` with fake candidates.)
- [x] Hardware (`@Tag("hardware")`, manual): log 10 seconds from the running car in listen-only. (`CanLogHardwareTest`, written but never run. **It will fail at open until silent mode is solved.**)

## Sniffing session checklist (run by the user, not by Claude)
**Blocked:** `can-log` can't open the stock-firmware CANable in listen-only mode (see Status). Solve silent mode first (options in `can-core/CLAUDE.md`).

1. Wiring: OBD Y-splitter. CANable on CAN-H pin 6, CAN-L pin 14, ground pin 5. Pin 16 (12 V) **not** connected. CANable termination **off**.
2. Start `can-log` with a note, ignition on, before starting VCDS.
3. In VCDS: Auto-Scan. Then open a module with fault codes and read the freeze frame. Then open measuring blocks for the engine (groups 001, 011) for the v2 work.
4. Stop the logger. Save the VCDS Auto-Scan log (`C:\Ross-Tech\VCDS\Logs`) and copy it to the Mac in `local/vcds-logs/`, with names matching the capture.
5. Run `capture-info` to confirm the capture isn't empty and the timestamps cover the whole session.
6. Never run VCDS and this tool transmitting at the same time. The logger is silent, so it's fine alongside VCDS.

## Done when
A full VCDS Auto-Scan has been captured from the car, checked with `capture-info`, and saved in `local/` next to the matching VCDS log.
