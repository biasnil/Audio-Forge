#!/usr/bin/env bash
# Builds the Windows installer (AudioForge-Setup-<version>.exe). Run from the
# MSYS2 UCRT64 shell, in the project root, after building:
#
#   scripts/build_installer.sh [version]          (default version: 1.1.0)
#
# Steps: a fresh self-contained app folder via deploy_windows.sh, then Inno
# Setup (https://jrsoftware.org/isinfo.php) packs it. The result lands in
# installer/Output/.
set -euo pipefail

VERSION="${1:-1.1.0}"
EXE="build/audioforge.exe"
DIST="build/installer-dist"
ISCC="${ISCC:-/c/Program Files (x86)/Inno Setup 6/ISCC.exe}"

if [ ! -x "$ISCC" ]; then
    echo "error: Inno Setup compiler not found at $ISCC (install Inno Setup 6, or set ISCC=...)" >&2
    exit 1
fi

# Start clean so DLLs from an older build can't sneak into the installer.
rm -rf "$DIST"
scripts/deploy_windows.sh "$EXE" "$DIST"

echo "== Inno Setup"
# MSYS2_ARG_CONV_EXCL: otherwise MSYS2 rewrites "/Q" and "/D..." into paths.
MSYS2_ARG_CONV_EXCL='*' "$ISCC" /Q "/DAppVersion=$VERSION" "/DSourceDir=$(cygpath -w "$(realpath "$DIST")")" installer/audioforge.iss

echo "== done: installer/Output/AudioForge-Setup-$VERSION.exe"
