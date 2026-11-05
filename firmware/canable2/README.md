# Patched CANable 2.0 firmware (`macvagdiag-silent-1`)

The stock CANable 2.0 slcan firmware has no working listen-only mode, so `can-log` can't sniff the car with it (details in [`can-core/CLAUDE.md`](../../can-core/CLAUDE.md), "Listen-only / silent mode"). This folder holds a small patch set on top of upstream [normaldotcom/canable2-fw](https://github.com/normaldotcom/canable2-fw) `b158aa7` and a script that builds it.

Adapter it's meant for: Makerbase CANable V2.0 S (STM32G431C8, 128 KB flash reported by the boot loader), stock firmware `b158aa7`, USB `16D0:117E`.

## Patches
1. **`0001` build fix:** `slcan.c` calls `snprintf_` without including `printf.h`. GCC 14+ rejects that, so upstream no longer builds.
2. **`0002` silent mode works:** `can_enable()` no longer forces `FDCAN_MODE_NORMAL`, so `M1` then `O` opens the controller in FDCAN **bus monitoring** mode: no ACKs, no error frames, no transmission. Normal mode stays the default (set in `can_init()`). Also: `can_tx()` refuses to queue frames unless on the bus in normal mode, and `O` drops anything queued while closed.
3. **`0003` `X` = restart into the ROM DFU boot loader:** firmware updates without opening the case. Accepted only as the bare line `X` and only while the channel is closed. Stock firmware parsed `X...` as a CAN frame and transmitted it; that's gone. The STM32G431 ROM boot loader has no CAN interface, so the bus stays untouched in boot loader mode.

The firmware reports **`macvagdiag-silent-1`**: USB product string `CANable2 macvagdiag-silent-1 github.com/Sam-Sanjay-Satish/Mac-VAG-Diagnostic-Tool` (macOS/ioreg truncates the name to `CANable2 macvagdiag-silent-1 gi`) and the same version in the `V` reply.

## Build
Needs the [Arm GNU Toolchain](https://developer.arm.com/downloads/-/arm-gnu-toolchain-downloads) with newlib (Homebrew's `arm-none-eabi-gcc` formula has no C library). The script defaults to `~/.local/opt/arm-gnu-toolchain-15.3.rel2-darwin-arm64-arm-none-eabi/bin`; override with `ARM_TOOLCHAIN=...`.

```
firmware/canable2/build.sh
```

Output: `firmware/canable2/build/out/canable2-macvagdiag-silent-1.bin` plus its `.sha256` (git-ignored).

## Flash (by hand, with the adapter **not** connected to the car)
1. Enter the boot loader:
   - stock firmware: bridge the two `BOOT` pins (next to the screw terminal) while plugging in USB;
   - `macvagdiag-silent-*` firmware: send `C` then `X` on the serial port, e.g. `printf 'C\rX\r' > /dev/cu.usbmodem<serial>1`.
2. Check it's there: `dfu-util -l` shows `0483:df11` "Internal Flash".
3. Flash and restart: `dfu-util -d 0483:df11 -a 0 -s 0x08000000:leave -D firmware/canable2/build/out/canable2-macvagdiag-silent-1.bin`
4. Unplug, remove anything from the `BOOT` pins, replug. `tools ... list-ports` (or `ioreg -p IOUSB -l | grep "USB Product Name"`) must show `CANable2 macvagdiag-silent-1`.
5. Before using it on the car: the `120R` termination header must be open.

## Back up / restore stock
Back up before the first flash (in boot loader mode):
```
dfu-util -d 0483:df11 -a 0 -s 0x08000000:131072 -U local/firmware-backup/<name>-flash.bin
dfu-util -d 0483:df11 -a 1 -s 0x1FFF7800:40 -U local/firmware-backup/<name>-option-bytes.bin
```
Restore: `dfu-util -d 0483:df11 -a 0 -s 0x08000000:leave -D local/firmware-backup/<name>-flash.bin`. The option bytes are never written by this procedure.

## Changing the firmware
Edit in a checkout made by `build.sh` (`build/canable2-fw`, branch `macvagdiag`), commit, regenerate with `git format-patch b158aa7..HEAD -o ../../patches`, bump `VERSION_TAG` in `build.sh`, and update the pinned version in `SlcanAdapter`.
