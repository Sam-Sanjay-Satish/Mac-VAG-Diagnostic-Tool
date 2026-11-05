#!/usr/bin/env bash
# Build the patched CANable 2.0 slcan firmware used by macvagdiag.
#
# Clones upstream canable2-fw at a pinned commit, applies patches/*.patch,
# tags the result so the firmware reports VERSION_TAG (USB product string and
# the slcan 'V' reply), and builds it. Output: build/out/ (git-ignored).
#
# Needs the Arm GNU Toolchain (with newlib). Homebrew's arm-none-eabi-gcc
# formula has no C library and can't build this. Override the location with
# ARM_TOOLCHAIN=/path/to/bin.
#
# Building never touches the adapter. Flashing is a separate, manual step:
# see README.md.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

UPSTREAM_URL="https://github.com/normaldotcom/canable2-fw.git"
UPSTREAM_COMMIT="b158aa7ab106bc018715cd4d5f02f544f9de1830"
# Reported as "CANable2 <VERSION_TAG> <remote>". Keep "CANable2 <tag>" within
# 31 characters: macOS shows only that much in the USB product name.
VERSION_TAG="macvagdiag-silent-1"
REMOTE_LABEL="https://github.com/Sam-Sanjay-Satish/Mac-VAG-Diagnostic-Tool"

TOOLCHAIN="${ARM_TOOLCHAIN:-$HOME/.local/opt/arm-gnu-toolchain-15.3.rel2-darwin-arm64-arm-none-eabi/bin}"
SRC="$HERE/build/canable2-fw"
OUT="$HERE/build/out"

if [[ ! -x "$TOOLCHAIN/arm-none-eabi-gcc" ]]; then
    echo "build.sh: no arm-none-eabi-gcc in $TOOLCHAIN (set ARM_TOOLCHAIN)" >&2
    exit 1
fi

rm -rf "$SRC" "$OUT"
mkdir -p "$OUT"

git clone --quiet "$UPSTREAM_URL" "$SRC"
git -C "$SRC" checkout --quiet -b macvagdiag "$UPSTREAM_COMMIT"
# --keep-cr: src/main.c upstream has CRLF line endings.
git -C "$SRC" -c user.name=macvagdiag -c user.email=macvagdiag@invalid \
    am --quiet --keep-cr --committer-date-is-author-date "$HERE"/patches/*.patch
git -C "$SRC" tag "$VERSION_TAG"
git -C "$SRC" remote set-url origin "$REMOTE_LABEL"

DESCRIBED="$(git -C "$SRC" describe --abbrev=7 --dirty --always --tags)"
if [[ "$DESCRIBED" != "$VERSION_TAG" ]]; then
    echo "build.sh: firmware would report '$DESCRIBED', expected '$VERSION_TAG'" >&2
    exit 1
fi

PATH="$TOOLCHAIN:$PATH" make -C "$SRC" -j8 >"$OUT/make.log" 2>&1 || {
    tail -30 "$OUT/make.log" >&2
    exit 1
}

cp "$SRC/build/canable2-$VERSION_TAG.bin" "$SRC/build/canable2-$VERSION_TAG.elf" "$OUT/"
(cd "$OUT" && shasum -a 256 "canable2-$VERSION_TAG.bin" | tee "canable2-$VERSION_TAG.bin.sha256")
PATH="$TOOLCHAIN:$PATH" arm-none-eabi-size "$OUT/canable2-$VERSION_TAG.elf"
echo "Built $OUT/canable2-$VERSION_TAG.bin"
