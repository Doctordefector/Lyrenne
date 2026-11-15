#!/bin/bash
# Turns the Compose distributable into the macOS release: Lyrenne.app with VLC inside, ad-hoc
# signed, in a DMG. Run on a Mac after `./gradlew :desktop:createDistributable`:
#
#   packaging/macos/package.sh <version>      ->  dist/Lyrenne-<version>-macos-<arm64|x64>.dmg
#
# The architecture is the one Compose built for (the JDK's), read off the launcher, not uname:
# an x64 JDK under Rosetta builds an Intel app on an Apple Silicon Mac.
set -euo pipefail

VERSION=${1:?usage: package.sh <version>}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP="$ROOT/desktop/build/compose/binaries/main/app/Lyrenne.app"
DIST="$ROOT/dist"
VLC_VERSION=3.0.23

[ -d "$APP" ] || { echo "No $APP. Run ./gradlew :desktop:createDistributable first."; exit 1; }

case "$(lipo -archs "$APP/Contents/MacOS/Lyrenne")" in
  arm64)  ARCH=arm64; VLC_ARCH=arm64;   VLC_SHA=fc6fac08d87f538517d44aca0c5e7a244b67c8c4cb589bf478363a7315fd5e0d ;;
  x86_64) ARCH=x64;   VLC_ARCH=intel64; VLC_SHA=ec01530ce69d849dd057fba8876e68ac39bf279dc28de4e9c04e4aec11fc98db ;;
  *) echo "Unexpected launcher architecture"; exit 1 ;;
esac

# 1. VLC. The official VideoLAN build, pinned by the sha256 VideoLAN publishes next to it.
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/lyrenne"
DMG_IN="$CACHE/vlc-$VLC_VERSION-$VLC_ARCH.dmg"
mkdir -p "$CACHE"
if [ ! -f "$DMG_IN" ] || ! echo "$VLC_SHA  $DMG_IN" | shasum -a 256 -c - >/dev/null 2>&1; then
  echo "Downloading VLC $VLC_VERSION ($VLC_ARCH)"
  curl -fL --retry 3 -o "$DMG_IN.part" \
    "https://download.videolan.org/pub/videolan/vlc/$VLC_VERSION/macosx/vlc-$VLC_VERSION-$VLC_ARCH.dmg"
  mv "$DMG_IN.part" "$DMG_IN"
fi
echo "$VLC_SHA  $DMG_IN" | shasum -a 256 -c -

# Where Compose's resources dir lives inside the bundle; DesktopPlayer looks in <resources>/vlc/lib.
RES="$APP/Contents/app/resources"
MNT=$(mktemp -d)
hdiutil attach -nobrowse -readonly -mountpoint "$MNT" "$DMG_IN" >/dev/null
trap 'hdiutil detach "$MNT" -force >/dev/null 2>&1 || true' EXIT
VLC_MACOS="$MNT/VLC.app/Contents/MacOS"
rm -rf "$RES/vlc"
mkdir -p "$RES/vlc"
# ditto keeps the lib symlinks and the modification times. plugins.dat records each plugin's
# mtime and size, and a copy that changes them makes libvlc ignore the cache and probe every
# plugin on each launch (11.6 s instead of 0.6 s, measured on Windows).
ditto "$VLC_MACOS/lib" "$RES/vlc/lib"
ditto "$VLC_MACOS/plugins" "$RES/vlc/plugins"
hdiutil detach "$MNT" >/dev/null
trap - EXIT
[ -f "$RES/vlc/lib/libvlc.dylib" ] || { echo "libvlc.dylib missing from the VLC image"; exit 1; }
if [ -f "$RES/vlc/plugins/plugins.dat" ]; then echo "plugins.dat present"; else echo "::warning::VLC image has no plugins.dat"; fi
# GPL notice travels with the binaries.
cp "$VLC_MACOS/../Resources/COPYING" "$RES/vlc/COPYING.txt" 2>/dev/null || true
echo "VLC bundled: $(du -sh "$RES/vlc" | cut -f1)"

# 2. Never ship user data. On macOS the app writes to ~/Library/Application Support, never into
# the bundle, but the same guard as packagePortableZip costs nothing.
if find "$APP" \( -name data -type d -o -name 'credentials*' -o -name preferences.properties \
     -o -name 'lyrenne.db' -o -name login-profile -o -name lyrenne.log \) | grep -q .; then
  echo "REFUSING TO SHIP: the bundle contains user data"; exit 1
fi

# 3. Ad-hoc signature. There is no Apple Developer ID, so Gatekeeper reports an unidentified
# developer (Open Anyway in System Settings) rather than "damaged", which is what an unsigned or
# half-signed bundle downloaded from the web gets. Every Mach-O first, then the bundle as a whole.
find "$APP" -type f \( -name '*.dylib' -o -name '*.jnilib' -o -name '*.so' \) -print0 |
  xargs -0 codesign --force --sign - --timestamp=none
codesign --force --deep --sign - --timestamp=none "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"

# 4. The DMG: the app plus an Applications link to drag it onto.
STAGE=$(mktemp -d)
ditto "$APP" "$STAGE/Lyrenne.app"
ln -s /Applications "$STAGE/Applications"
mkdir -p "$DIST"
OUT="$DIST/Lyrenne-$VERSION-macos-$ARCH.dmg"
rm -f "$OUT"
# hdiutil on CI runners fails now and then with "Resource busy"; a retry clears it.
for i in 1 2 3 4 5; do
  hdiutil create -volname "Lyrenne" -srcfolder "$STAGE" -fs HFS+ -format UDZO -ov "$OUT" && break
  echo "hdiutil create failed, retrying ($i)"; sleep 5
done
[ -f "$OUT" ] || { echo "DMG was not created"; exit 1; }
rm -rf "$STAGE"
echo "Built $OUT ($(du -h "$OUT" | cut -f1))"
