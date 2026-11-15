#!/bin/sh
# Builds Lyrenne-<version>-<arch>.AppImage: the createDistributable folder (app + its own Java
# runtime) plus libVLC and the plugins an audio player needs, with every library they link,
# so one file runs on any distro without VLC installed.
#
# Runs inside debian:bookworm (CI does `docker run ... debian:bookworm`), for two reasons:
#  - Debian security-maintains its VLC 3.0.x, so the bundled libVLC gets fixes with each release.
#  - glibc 2.36. Libraries only need the glibc symbols they use, and the build prints the
#    newest one required, which sets the oldest distro the AppImage runs on.
#
# Usage, from the repo root, after ./gradlew :desktop:createDistributable on the same arch:
#   LYRENNE_VERSION=2.21.0 sh packaging/appimage/build-appimage.sh
# Output: dist/Lyrenne-<version>-<arch>.AppImage
set -eu

ARCH=$(uname -m)                        # x86_64 or aarch64
VERSION=${LYRENNE_VERSION:?set LYRENNE_VERSION}
DIST=desktop/build/compose/binaries/main/app/Lyrenne
WORK=build/appimage-$ARCH
APPDIR=$WORK/Lyrenne.AppDir
OUT=dist/Lyrenne-$VERSION-$ARCH.AppImage
[ -x "$DIST/bin/Lyrenne" ] || { echo "No distributable at $DIST; run createDistributable first" >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends \
    libvlc5 libvlccore9 vlc-plugin-base libvlc-bin \
    ca-certificates wget file binutils desktop-file-utils >/dev/null
TRIPLET=$(dpkg-architecture -qDEB_HOST_MULTIARCH 2>/dev/null || gcc -dumpmachine 2>/dev/null || echo "$ARCH-linux-gnu")
VLCLIB=/usr/lib/$TRIPLET
echo "libVLC $(dpkg-query -W -f '${Version}' libvlc5) for $TRIPLET"

rm -rf "$WORK"
mkdir -p "$APPDIR/usr/lib/vlc" dist
cp -a "$DIST" "$APPDIR/Lyrenne"
# Never ship what a smoke run left behind (same rule as packagePortableZip).
rm -rf "$APPDIR/Lyrenne/data" "$APPDIR/Lyrenne/Downloads" "$APPDIR/Lyrenne/updates" "$APPDIR/Lyrenne/lyrenne.log"

# libVLC and only the plugins an audio player uses (an allowlist: vlc-plugin-base carries ~300,
# and their libraries were most of the image). What YouTube serves is Opus in WebM and AAC in MP4,
# over HTTPS with DASH; local downloads add MP3, FLAC, Vorbis and WAV. AAC decodes through faad,
# not libavcodec, which alone would drag in some 60 codec libraries. Never add `control/`: its dbus
# module registers a second MPRIS player named "vlc" next to Lyrenne's own.
cp -L "$VLCLIB/libvlc.so.5" "$VLCLIB/libvlccore.so.9" "$APPDIR/usr/lib/"
SRC=$VLCLIB/vlc/plugins
DST=$APPDIR/usr/lib/vlc/plugins
keep() { dir=$1; shift; mkdir -p "$DST/$dir"; for m in "$@"; do cp "$SRC/$dir/lib${m}_plugin.so" "$DST/$dir/"; done; }
keep access filesystem http https
keep demux adaptive mp4 mkv es ogg wav flacsys
keep codec faad opus vorbis flac mpg123 araw lpcm
keep misc gnutls logger xml
keep keystore memory_keystore
# Small and self-contained, so whole categories: parsing, filters (EQ, normalize, speed), output.
for dir in packetizer audio_filter audio_mixer stream_filter; do cp -a "$SRC/$dir" "$DST/"; done
# Except the one packetizer built on libavcodec, which would bring the 60 libraries back.
rm -f "$DST/packetizer/libpacketizer_avparser_plugin.so"
keep audio_output pulse alsa adummy amem afile

# Every library those link, with rpaths pointing inside the AppDir. linuxdeploy leaves out the
# base system (glibc, libasound, X11, GL, fontconfig...) per the AppImage excludelist.
TOOLS=$WORK/tools
mkdir -p "$TOOLS"
fetch() { wget -q -O "$TOOLS/$1" "$2" && chmod +x "$TOOLS/$1"; }
fetch linuxdeploy "https://github.com/linuxdeploy/linuxdeploy/releases/download/continuous/linuxdeploy-$ARCH.AppImage"
fetch appimagetool "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$ARCH.AppImage"
export APPIMAGE_EXTRACT_AND_RUN=1 NO_STRIP=1
set -- --appdir "$APPDIR" --library "$APPDIR/usr/lib/libvlc.so.5" --library "$APPDIR/usr/lib/libvlccore.so.9"
for d in "$APPDIR"/usr/lib/vlc/plugins/*/; do set -- "$@" --deploy-deps-only "$d"; done
"$TOOLS/linuxdeploy" "$@" >"$WORK/linuxdeploy.log" 2>&1 || { tail -50 "$WORK/linuxdeploy.log"; exit 1; }

# libVLC's plugin cache. Without it the first libvlc_new scans every plugin, which is the slow
# start measured on Windows (CLAUDE.md, Startup Performance). squashfs keeps mtimes, so it stays valid.
VLC_PLUGIN_PATH="$APPDIR/usr/lib/vlc/plugins" "$VLCLIB/vlc/vlc-cache-gen" "$APPDIR/usr/lib/vlc/plugins"
test -s "$APPDIR/usr/lib/vlc/plugins/plugins.dat"

# Entry point, desktop entry, icon.
cp packaging/appimage/AppRun "$APPDIR/AppRun"
chmod +x "$APPDIR/AppRun"
sed -e 's/^Exec=.*/Exec=lyrenne %U/' -e '/^#/d' packaging/linux/lyrenne.desktop > "$APPDIR/lyrenne.desktop"
echo "X-AppImage-Version=$VERSION" >> "$APPDIR/lyrenne.desktop"
cp desktop/src/main/resources/icon.png "$APPDIR/lyrenne.png"
ln -sf lyrenne.png "$APPDIR/.DirIcon"

# The oldest glibc this runs on: the newest GLIBC_ symbol version anything inside requires.
GLIBC=$(find "$APPDIR" -type f \( -name '*.so*' -o -perm -u+x \) -exec sh -c 'file -b "$1" | grep -q ELF && objdump -T "$1" 2>/dev/null' _ {} \; \
    | grep -o 'GLIBC_[0-9.]*' | sort -u -V | tail -1)
echo "Requires $GLIBC or newer, because of:"
find "$APPDIR" -type f -name '*.so*' -exec sh -c 'objdump -T "$1" 2>/dev/null | grep -q "$2" && echo "  ${1#$3/}"' _ {} "$GLIBC" "$APPDIR" \;
echo "$GLIBC" > "$WORK/glibc.txt"
echo "Bundled: $(find "$APPDIR/usr/lib" -maxdepth 1 -name '*.so*' | wc -l) libraries, $(find "$APPDIR/usr/lib/vlc/plugins" -name '*.so' | wc -l) VLC plugins"

# Test media in the formats YouTube serves, for CI's smoke test (not part of the AppImage).
if [ "${LYRENNE_SMOKE_MEDIA:-}" = 1 ]; then
    apt-get install -y -qq --no-install-recommends ffmpeg >/dev/null
    for f in m4a:aac webm:libopus mp3:libmp3lame flac:flac; do
        ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=440:duration=8" -c:a "${f#*:}" "dist/smoke.${f%%:*}"
    done
fi

ARCH=$ARCH "$TOOLS/appimagetool" --no-appstream "$APPDIR" "$OUT" >"$WORK/appimagetool.log" 2>&1 || { tail -50 "$WORK/appimagetool.log"; exit 1; }
ls -l "$OUT"
