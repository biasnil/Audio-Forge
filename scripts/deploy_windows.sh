#!/usr/bin/env bash
# Builds a self-contained AudioForge folder that runs on a Windows machine
# with no MSYS2 or Qt installed. Run from the MSYS2 UCRT64 shell after
# building:
#
#   scripts/deploy_windows.sh [path/to/audioforge.exe] [dist-folder]
#
# (defaults: build/audioforge.exe -> dist/)
#
# Why each step is needed:
#   - windeployqt6 copies the Qt DLLs and most plugins, but NOT the DLLs
#     those plugins depend on. The QtMultimedia FFmpeg backend
#     (multimedia/ffmpegmediaplugin.dll) alone pulls in ~80 codec and
#     support DLLs from /ucrt64/bin (avcodec, libx264, libvpx, gnutls,
#     cairo, ...). Without them Qt reports "No QtMultimedia backends
#     found" and the video wallpaper can't play.
#   - On MSYS2's Qt 6 the plugin folder is multimedia/ (older Qt versions
#     used mediaservice/).
#   - windeployqt6 doesn't always write qt.conf, so it's written here.
set -euo pipefail

EXE="${1:-build/audioforge.exe}"
DIST="${2:-dist}"
UCRT_BIN=/ucrt64/bin
QT_PLUGINS=/ucrt64/share/qt6/plugins

if [ ! -f "$EXE" ]; then
    echo "error: $EXE not found -- build first, or pass the .exe path" >&2
    exit 1
fi

mkdir -p "$DIST"
cp "$EXE" "$DIST/"
EXE_NAME="$(basename "$EXE")"

echo "== windeployqt6"
windeployqt6 --release "$DIST/$EXE_NAME"

echo "== QtMultimedia FFmpeg backend"
mkdir -p "$DIST/multimedia"
cp "$QT_PLUGINS/multimedia/ffmpegmediaplugin.dll" "$DIST/multimedia/"

echo "== qt.conf"
printf '[Paths]\nPlugins = .\n' > "$DIST/qt.conf"

# ldd is run from inside $DIST, where Windows' DLL search finds anything
# already copied next to the .exe, so only dependencies still resolving to
# /ucrt64/bin are reported. Each pass checks every DLL/EXE in the folder,
# including the ones copied in the previous pass (they can have
# dependencies of their own); stops once nothing points at /ucrt64/bin.
cd "$DIST"

# If ldd can't read the .exe at all, "no missing dependencies" below would
# be a false all-clear.
if ! ldd "$EXE_NAME" 2>/dev/null | grep -q '=>'; then
    echo "error: ldd produced no dependency list for $EXE_NAME -- run this from the MSYS2 UCRT64 shell" >&2
    exit 1
fi

list_missing() {
    # `|| true`: ldd can fail on an odd file, and grep finds nothing on the
    # last pass -- neither should abort the script under pipefail.
    { find . \( -iname '*.dll' -o -iname '*.exe' \) -print0 \
        | xargs -0 -n1 ldd 2>/dev/null || true; } \
        | { grep -o "$UCRT_BIN/[^ ]*" || true; } \
        | xargs -r -n1 basename \
        | sort -u
}

echo "== copying transitive DLL dependencies from $UCRT_BIN"
pass=0
while :; do
    missing="$(list_missing)"
    if [ -z "$missing" ]; then
        break
    fi

    pass=$((pass + 1))
    copied=0
    while read -r dll; do
        if [ ! -e "$dll" ]; then
            cp "$UCRT_BIN/$dll" .
            copied=$((copied + 1))
        fi
    done <<< "$missing"
    echo "   pass $pass: copied $copied DLL(s)"

    if [ "$copied" -eq 0 ]; then
        # Everything listed is already here but still resolves to
        # /ucrt64/bin -- more copying won't fix it.
        echo "error: these still resolve to $UCRT_BIN:" >&2
        echo "$missing" >&2
        exit 1
    fi
done

echo "== done: $(pwd) has no remaining $UCRT_BIN dependencies"
echo "   Test it on a machine without MSYS2 before shipping; Qt warnings are"
echo "   logged to %APPDATA%\\AudioForge\\warnings.log."
