# tools

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** not started. **Package:** `io.github.samsanjaysatish.macvagdiag.tools`
**Depends on:** `can-core` only (later also `transport` for the fixture sanitizer). **Used by:** nothing; these are command-line programs.

## Purpose
Command-line utilities that support development, mainly the **listen-only CAN logger** for the sniffing session. It comes right after `can-core` because the real capture must exist before the protocol layers can be built and tested against it.

## What to build
- [ ] **`can-log`, the listen-only logger** (Gradle `application` plugin, runnable with `./gradlew :tools:run --args=...` or as an installed script).
  - Arguments: serial port (or auto-detect if exactly one CANable is present), bitrate (default 500 kbit/s), output file (default: `local/captures/<date>-<time>.log`).
  - **Opens the adapter only in `LISTEN_ONLY` mode, with no option to change that.** There is no code path in this program that calls `send`.
  - Writes candump log format via `can-core`'s `CaptureWriter`. Flushes regularly so a crash or unplugged cable loses at most a second of data.
  - Writes a metadata header: date, bitrate, firmware version, tool version, free-text note (`--note "VCDS auto-scan, engine off"`).
  - Live console summary every few seconds: frames/sec, total frames, distinct IDs, errors. Logs go to stderr.
  - Ctrl-C: close the adapter, flush and close the file, print the final stats.
- [ ] **`list-ports`**: print serial ports that look like a CANable, to find the right `/dev/cu.usbmodem*`.
- [ ] **`capture-info`**: summarize a capture: duration, frame count, IDs seen with counts, first/last timestamps. Used to sanity-check a sniffing session before leaving the car.
- [ ] **Later: fixture sanitizer** (needs `transport`). Masks the VIN in captures before they're committed. The VIN is 17 ASCII bytes split across several CAN frames inside TP 2.0 / ISO-TP messages, so a plain per-frame search won't find it. It has to reassemble messages, mask, then rewrite frames. Also masks the VIN in VCDS text logs (easy: text replace). Until this exists, no capture gets committed; raw captures stay in `local/`.

## Tests (all without hardware)
- The logger run against `SimulatedAdapter` / `ReplayAdapter`: the output file contains every frame in order, in valid candump format, with the header.
- **The logger never transmits:** the adapter records zero sent frames, and it was opened in `LISTEN_ONLY`.
- Argument parsing: defaults, bad bitrate, missing port, output path in `local/`.
- Shutdown path flushes and closes (simulate Ctrl-C by calling the shutdown hook logic directly).
- `capture-info` on a small hand-written capture.
- Hardware (`@Tag("hardware")`, manual): log 10 seconds from the running car in listen-only.

## Sniffing session checklist (run by the user, not by Claude)
1. Wiring: OBD Y-splitter. CANable on CAN-H pin 6, CAN-L pin 14, ground pin 5. Pin 16 (12 V) **not** connected. CANable termination **off**.
2. Start `can-log` with a note, ignition on, before starting VCDS.
3. In VCDS: Auto-Scan. Then open a module with fault codes and read the freeze frame. Then open measuring blocks for the engine (groups 001, 011) for the v2 work.
4. Stop the logger. Save the VCDS Auto-Scan log (`C:\Ross-Tech\VCDS\Logs`) and copy it to the Mac in `local/vcds-logs/`, with names matching the capture.
5. Run `capture-info` to confirm the capture isn't empty and the timestamps cover the whole session.
6. Never run VCDS and this tool transmitting at the same time. The logger is silent, so it's fine alongside VCDS.

## Done when
A full VCDS Auto-Scan has been captured from the car, checked with `capture-info`, and saved in `local/` next to the matching VCDS log.
