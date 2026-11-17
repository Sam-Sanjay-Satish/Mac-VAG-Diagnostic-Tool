# tools

Layer guide. Read the root `CLAUDE.md` first: its safety and testing rules apply here too.

**Status:** `can-log`, `list-ports`, `capture-info`, `sanitize-capture` and `sanitize-vcds-log` built and tested against fakes (107 tests, `./gradlew :tools:build` green). **`can-log` needs the patched CANable firmware `macvagdiag-silent-1`** ([`firmware/canable2`](../firmware/canable2/README.md), flashed on the user's adapter 2026-11-05). It opens the adapter only in `LISTEN_ONLY`, and `SlcanAdapter` allows that only after `V` reports that build (see `can-core/CLAUDE.md`, "Listen-only / silent mode"). On stock firmware it exits 1 at open with an explanation, having sent only `C` and `V` to the adapter (nothing on the bus) and created no file. **Bench-checked 2026-11-05** on the real adapter with nothing connected: `list-ports` found it, `can-log --duration 5` passed the `V` check, opened at 500 kbit/s in `LISTEN_ONLY`, logged 0 frames with 0 errors and exited 0, with the header correct. **Run on the car 2026-11-13** (sniffing session, see below): a full VCDS Auto-Scan and a 01 fault-code read captured in `LISTEN_ONLY` with 0 errors. **Fixture sanitizer built 2026-11-15** and run on both captures and both VCDS logs; the user decided to mask the VIN only, and the sanitized captures and VCDS logs were committed on 2026-11-16 as shared fixtures in `can-core/src/testFixtures/resources/fixtures/2026-11-13/` (see the sanitizer section). **Package:** `io.github.samsanjaysatish.macvagdiag.tools`
**Depends on:** `can-core` only. The sanitizer has its own tolerant TP 2.0 / ISO-TP reassembly instead of using `transport`: `transport` is a tester (it negotiates, ACKs and keeps channels alive), while the sanitizer reads somebody else's traffic, including channels opened before the capture started. **Used by:** nothing; these are command-line programs.

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
    - The header's firmware comes from the USB description of the matching discovered port (`"CANable2 <git version> <remote>"`); the logger itself never sends `V`. (`SlcanAdapter` does, as part of the `LISTEN_ONLY` open, to check the build.) Unknown or non-CANable ports give `unknown`. **Verified on the Mac 2026-11-05:** jSerialComm's `getPortDescription()` returns the full USB product string (`CANable2 macvagdiag-silent-1 github.com/Sam-Sanjay-Satish/Mac-VAG-Diagnostic-Tool`), not ioreg's 31-character short name.
    - Flush every 1 s, summary every 5 s (`can-log: 0:00:05  0.6 frames/s  3 frames  2 IDs  0 errors`), both timed on the `TimeSource`. Errors = `SlcanAdapter` decode errors + error replies + discarded lines + dropped frames, plus capture write errors.
    - `shutdown()` is the Ctrl-C hook: sets the stop flag, closes the adapter (which wakes a blocked `receive`), flushes and closes the file, and prints the final stats once. It's idempotent and thread-safe. An adapter failure (unplug) keeps the frames received before it, then goes through the same shutdown, exit 1.
    - Console output goes to `System.err` directly. can-core's SLF4J logs also go to stderr through `slf4j-simple` (`runtimeOnly`, approved by the user on 2026-10-22), configured in `src/main/resources/simplelogger.properties`.
- [x] **`list-ports`**: print serial ports that look like a CANable, to find the right `/dev/cu.usbmodem*`.
  - **Built** (`ListPortsCommand`): path, `CANable` marker, `VID:PID`, USB description; CANables first. It only enumerates ports (opens nothing). Exit 1 if there are no ports.
- [x] **`capture-info`**: summarize a capture: duration, frame count, IDs seen with counts, first/last timestamps. Used to sanity-check a sniffing session before leaving the car.
  - **Built** (`CaptureInfo` + `CaptureInfoCommand`): header metadata, frame count, first/last timestamps (local zone), duration, largest gap between frames (a long gap may mean a dropped connection), frames per ID (standard first, then extended). Exit 1 for an empty, missing or malformed capture (malformed names the line).
- [x] **Fixture sanitizer** (`sanitize-capture`, `sanitize-vcds-log`). Masks the VIN in captures and VCDS logs so they can be committed as fixtures. Reads files only: no adapter, no serial port.
  - **Built** as `DiagPayloads` (reassembly), `Redactions` (what to mask and to look for), `VinFinder`, `CaptureSanitizer`, `VcdsLogSanitizer`, `SanitizeArgs` and the two commands.
  - **Reassembly** (`DiagPayloads`), self-contained and tolerant; it never fails on odd frames, it just leaves them out of the payloads:
    - TP 2.0: a positive setup reply on `200 + addr` (`00 D0 ..`) marks both negotiated IDs as TP 2.0 from then on. Data opcodes `0x`..`3x`; the first packet's 2-byte length has its top 2 bits masked (`80 03` on every `7F xx 78`); `1x`/`3x` end a message. ACKs and `Ax` control carry no payload, and `A8` drops a half-received message.
    - ISO-TP on every other ID outside `200..2FF`: SF, FF (also the 32-bit escape), CF and FC. Padding after the declared length is left out. CFs without an FF (capture started mid-message) become a message of unknown length.
    - Each payload byte remembers its frame and byte index, so a VIN split across frames is written back into exactly those bytes. Payloads are searched per ID with messages concatenated, so a string running across a message boundary is found too.
  - **What is masked** (`Redactions`, ASCII case-insensitive, length always kept):
    - The full VIN becomes the fixed `Vin.FAKE` = `3VW00000XD0000000`. It has a valid character set and check digit, keeps WMI `3VW` and model year `D`, and the rest is zeros.
    - Every substring of 8+ characters becomes the fake's characters at the same offset. That covers a VIN cut by a message or capture boundary and the "last 8" form.
    - The last 6 characters (the serial) are masked anywhere in binary payloads, but in text only as a standalone token, so a mileage like `1042788` isn't touched.
    - `--mask <text>` (6+ printable ASCII characters, repeatable) masks other strings, e.g. module serial numbers: digits become `0`, letters `X`.
    - Refused: a VIN that shares 7 characters in a row with the fake (masking couldn't remove them).
  - **Which VIN:**
    - By default it comes from the capture's own VIN responses: OBD `49 02 01`, UDS `62 F1 90` or KWP `5A 90`, followed by 17 VIN characters. `--vin` or `--vin-from <capture or VCDS log>` can give it instead, which saves typing it.
    - Refused: a capture holding a VIN other than the given one, several VINs, or no VIN with none given. In the last case there would be nothing to verify the output against.
    - Other 17-character strings with a valid check digit are listed for review, not masked. On this car they are airbag sensor serial numbers on `77F`.
  - **Captures:**
    - The output is the input line for line, plus one `# sanitized: ...` metadata line before the first frame.
    - Only payload bytes and comment-line text change. Timestamps, interface, IDs, lengths, protocol bytes, hex case and line endings stay byte for byte.
  - **Verification** (`CaptureSanitizer.verify`, independent of the masking code) runs twice: on the result in memory, then on the file read back from disk. If the second run fails, the file is deleted (exit 1). It checks:
    - The structure is unchanged, and changed bytes sit only inside payloads.
    - No VIN piece of 7+ characters and no serial is left in the reassembled payloads, in any single frame, in each ID's raw bytes (all bytes, and without byte 0), in all frames in file order, or in comment lines. Error messages give locations, never values.
  - **VCDS logs:**
    - Handled as single bytes (ISO-8859-1), so the code page, `°` characters and CRLF survive unchanged, and the length stays the same.
    - The VIN is always masked. So are the license plate and the repair order when filled in.
    - Listed and kept unless asked for: `VCID` (`--mask-vcid`, zeros), `VINID` (`--mask-vinid`), `Serial number` (`--mask-serials`), and workshop codes `WSC nnnnn nnn nnnnn` (`--mask-shop-codes`). `--mask-all` masks all four.
    - A masked value is masked wherever else it appears too. A value that is already its own mask (the instrument cluster's all-zero serial) is skipped, so it doesn't make verification see the other masked serials as leftovers.
  - **Run on the real files 2026-11-15** (outputs in `local/sanitized/`, cross-checked with a separate script):
    - Auto-Scan capture: 2535 frames. The VIN is only in the six OBD `09 02` replies on `7E8` (VCDS asks once per UDS module), and all 6 were masked. 12 frames and 78 bytes changed; the first frame of each reply is unchanged because the real VIN also starts with `3VW`. No partial VIN appears anywhere else.
    - Freeze-frame capture: 454 frames. No VIN on the bus (verified with `--vin-from` the Auto-Scan capture), 0 changed.
    - Auto-Scan VCDS log: the VIN masked in 2 places. Kept by default: 13 VCIDs, 1 VINID, 17 serial numbers, 13 workshop codes. The license plate and repair order are empty. A `--mask-all` variant is next to it.
    - Freeze-frame VCDS log: no VIN. Kept: 1 VCID and 1 workshop code.
  - **Still identifying in the sanitized captures:** module serial numbers in identification responses. They show up on `300` (TP 2.0 modules), `77E`, `77F` (airbag sensors), `79B`, `79C` and `7D5`. Masking them needs `--mask` per value; whether to do that is the user's call. The header's `port:` line holds the adapter's USB serial, which is already public in `can-core/CLAUDE.md`.
  - **Decided by the user on 2026-11-15: mask the VIN only.** Module serials, VCIDs, the VINID and workshop codes stay as recorded. The two captures and the two VCDS logs from `local/sanitized/` (not the `.mask-all` variant) were committed as fixtures on 2026-11-16, re-checked first for any 8-character piece of the VIN as text or hex (also across frame boundaries per CAN ID): none. **Location:** `can-core/src/testFixtures/resources/fixtures/2026-11-13/`, loaded with `RealCaptures` (can-core test fixtures, on every layer's test class path); `.gitattributes` keeps them byte for byte. `RealCapturesTest` re-checks the masked VIN in CI and, on the user's Mac only (it reads the raw log from `local/`), that no piece of the real VIN is in any fixture. Any new fixture must go through the sanitizer first.

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
  - `tools/build/install/macvagdiag-tools/bin/macvagdiag-tools sanitize-capture --output local/sanitized/<name>.log [--vin <VIN> | --vin-from <file>] [--mask <text>]... local/captures/<name>.log`
  - `tools/build/install/macvagdiag-tools/bin/macvagdiag-tools sanitize-vcds-log --output local/sanitized/<name>.vcds.txt [--vin-from <file>] [--mask-vcid] [--mask-vinid] [--mask-serials] [--mask-shop-codes] [--mask-all] [--mask <text>]... local/vcds-logs/<name>.vcds.txt`
  - For a capture or log without a VIN of its own (the freeze-frame session), pass `--vin-from` the matching Auto-Scan capture or log, so the output is still checked against the real VIN.
- `<command> --help` prints each command's options. Exit codes: 0 OK, 1 failure, 2 bad arguments.

## Tests (all without hardware)
- [x] The logger run against `SimulatedAdapter` / `ReplayAdapter`: the output file contains every frame in order, in valid candump format, with the header. (`CanLoggerTest`, `CanLogCommandTest`: byte-for-byte frame lines, plus the header read back with `CaptureReader`.) Also periodic flush, the 5 s summary, unplug, write failure and refused open, all on `FakeTimeSource`.
- [x] **The logger never transmits:** the adapter records zero sent frames, and it was opened in `LISTEN_ONLY`. (`CanLoggerTest.neverTransmitsAndOpensOnlyInListenOnly`, `CanLogCommandTest`, and the source scan `NoTransmitSourceTest`.)
- [x] Argument parsing: defaults, bad bitrate, missing port, output path in `local/`. (`CanLogOptionsTest`, `PortChoiceTest`, `CanLogCommandTest`: also auto-detect, several CANables, no overwrite, unknown options.)
- [x] Shutdown path flushes and closes (simulate Ctrl-C by calling the shutdown hook logic directly). (`CanLoggerTest`: called directly, and from another thread while the loop is blocked in `receive`.)
- [x] `capture-info` on a small hand-written capture. (`CaptureInfoTest`; `ListPortsCommandTest` covers `list-ports` with fake candidates.)
- [x] Sanitizer, all on hand-written frames and logs with the made-up VIN `1M8GDM9AXKP042788` (45 tests):
  - `VinTest`: check digit, the fake VIN is valid.
  - `RedactionsTest`: full VIN, pieces of 8+, the serial token rule, case, `--mask`, refusals, messages without the VIN.
  - `DiagPayloadsTest`: TP 2.0 single/multi-packet with byte positions, the `80 03` flag, `A8`, invalid setup IDs; ISO-TP SF/FF/CF/FC, padding `55`/`AA`, CFs without an FF, cut-off messages, extended IDs.
  - `CaptureSanitizerTest`: OBD `49 02`, KWP `5A 90` over TP 2.0 and UDS `62 F1 90`, each split across frames, with the exact output frames; partial VINs; comment lines; lowercase hex and CRLF; a VIN piece outside any payload fails loudly; verification catches leftovers and structural changes; `--mask`.
  - `VcdsLogSanitizerTest`: default and all-fields masking, values elsewhere, the all-zero serial, verify failures.
  - `SanitizeCommandsTest`: end to end on temp files: VIN auto-detect, `--vin-from` a capture or VCDS log, a different VIN refused, no overwrite, bad arguments, no file left after a failed verification.
  - `NoTransmitSourceTest` covers the new sources too.
- [x] Hardware (`@Tag("hardware")`, manual): log 10 seconds from the running car in listen-only. (`CanLogHardwareTest`, written but never run. Needs the patched firmware.) Since 2026-11-17 it runs only with `MACVAGDIAG_EXPECT_FRAMES=1`, while VCDS is talking: the diagnostic CAN is silent without a tester, so its "at least one frame" check would otherwise fail on a healthy car.

## Sniffing session checklist (run by the user, not by Claude)
0. Before leaving for the car: `list-ports` shows `CANable2 macvagdiag-silent-1`, the `120R` and `BOOT` headers on the board are open, and a bench run of `can-log --duration 5` (nothing connected) opens in `LISTEN_ONLY` and exits 0 with 0 frames.
1. Wiring: OBD Y-splitter. CANable on CAN-H pin 6, CAN-L pin 14, ground pin 5. Pin 16 (12 V) **not** connected. CANable termination **off**.
2. Start `can-log` with a note, ignition on, before starting VCDS.
3. In VCDS: Auto-Scan. Then open a module with fault codes and read the freeze frame. Then open measuring blocks for the engine (groups 001, 011) for the v2 work.
4. Stop the logger. Save the VCDS Auto-Scan log (`C:\Ross-Tech\VCDS\Logs`) and copy it to the Mac in `local/vcds-logs/`, with names matching the capture.
5. Run `capture-info` to confirm the capture isn't empty and the timestamps cover the whole session.
6. Never run VCDS and this tool transmitting at the same time. The logger is silent, so it's fine alongside VCDS.

**Lessons from the 2026-11-13 session:**
- With the ignition on and no tester connected, the diagnostic CAN is silent: 0 frames is normal until VCDS talks.
- The first Auto-Scan gave 0 frames because the CANable's wires, pushed into the splitter's female socket, weren't touching the contacts. A proper OBD plug (pigtail) fixed it. Check for frames within ~20 s of VCDS starting before letting a scan run.
- VCDS's own clock was wrong, which only affects the VCDS log header and its UDS freeze-frame dates; captures are timed by the Mac.
- Measuring blocks (step 3) weren't captured.
- This Mac's default `java` is 21, so the installed script fails with `UnsupportedClassVersionError` unless `JAVA_HOME` points at JDK 25 (the one Gradle provisioned: `~/.gradle/jdks/eclipse_adoptium-25-aarch64-os_x.2/jdk-25.0.4.1+1/Contents/Home`).
- Wiring check with a multimeter (ignition off): 50–130 Ω between `H` and `L` at the CANable's screw terminals means it's on the bus; open means it isn't. Probe tips don't reach the contacts in an OBD female socket either, so measure at the plug or the screws.
- Bench check repeated 2026-11-13 before the session: passed (`local/captures/bench-2026-11-13-check.log`). `capture-info` exits 1 with "no frames" on an empty bench capture; that's expected.
- A logger started in the background can be stopped with `kill -INT <pid>` (same shutdown path as Ctrl-C, exit code 130, file flushed and closed).

## Done when
A full VCDS Auto-Scan has been captured from the car, checked with `capture-info`, and saved in `local/` next to the matching VCDS log.

**Met 2026-11-13.** `local/captures/2026-11-13-150741-autoscan-2.log` (Auto-Scan, 2535 frames, 32 IDs, 194.5 s, 0 errors) and `local/captures/2026-11-13-151419-freezeframe-01.log` (01 fault codes and freeze frames, 454 frames, 0 errors), both checked with `capture-info` at the car and saved next to their VCDS logs in `local/vcds-logs/` (matching names; `NOTES.md` there records that the VCDS laptop's clock was wrong). Both were sanitized (VIN only, the user's choice) and committed as fixtures on 2026-11-16; the replay tests in `transport`, `diag` and `scan` run on them.
