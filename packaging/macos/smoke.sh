#!/bin/bash
# CI check of a built DMG, run on a macOS runner (there is no Mac to look at otherwise):
#  1. the DMG mounts and its Lyrenne.app has a valid signature,
#  2. --smoke-test plays a WAV and an AAC .m4a (the codec YouTube streams use) through the libvlc
#     inside the bundle, and fails if libvlc came from anywhere else,
#  3. the app starts with its window, writes its data to ~/Library/Application Support/Lyrenne,
#     logs no exceptions, and quits through the macOS quit handler (the Cmd+Q path).
# Leaves logs and a screenshot in diag/.
set -euo pipefail

DMG=${1:?usage: smoke.sh <dmg>}
DIAG="$PWD/diag"; mkdir -p "$DIAG"
DATA="$HOME/Library/Application Support/Lyrenne"
WORK=$(mktemp -d)

MNT="$WORK/mnt"; mkdir -p "$MNT"
hdiutil attach -nobrowse -readonly -mountpoint "$MNT" "$DMG" >/dev/null
test -L "$MNT/Applications"
ditto "$MNT/Lyrenne.app" "$WORK/Lyrenne.app"
hdiutil detach "$MNT" >/dev/null
APP="$WORK/Lyrenne.app"
codesign --verify --deep --strict --verbose=2 "$APP"
codesign -dv "$APP" 2>&1 | tee "$DIAG/codesign.txt"
grep -q "Signature=adhoc" "$DIAG/codesign.txt"
# What Gatekeeper makes of it. Expected to be rejected (no Developer ID): that is the
# "unidentified developer" case the first-open steps cover, not a "damaged" app.
spctl --assess --type execute -vv "$APP" 2>&1 | tee "$DIAG/spctl.txt" || true
cat "$APP/Contents/app/Lyrenne.cfg" > "$DIAG/launcher-cfg.txt" || true
du -sh "$APP" | tee "$DIAG/size.txt"

# 2. Playback from the bundle. A tone, no binary fixture in the repo.
python3 - "$WORK/tone.wav" <<'EOF'
import math, struct, sys, wave
w = wave.open(sys.argv[1], "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(44100)
w.writeframes(b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 440 * i / 44100))) for i in range(44100 * 8)))
w.close()
EOF
afconvert -f m4af -d aac "$WORK/tone.wav" "$WORK/tone.m4a"
mkdir -p "$DATA"
echo "onboardingCompleted=true" > "$DATA/preferences.properties"
"$APP/Contents/MacOS/Lyrenne" --smoke-test --require-bundled-vlc "$WORK/tone.wav" "$WORK/tone.m4a" | tee "$DIAG/smoke.txt"
cp "$DATA/lyrenne.log" "$DIAG/smoke-lyrenne.log" || true
grep -q "SMOKE OK" "$DIAG/smoke.txt"

# 3. The real app, windowed. Onboarding needs a sign-in a runner cannot do, so it is skipped.
rm -f "$DATA/lyrenne.log"
"$APP/Contents/MacOS/Lyrenne" > "$DIAG/app-stdout.txt" 2>&1 &
PID=$!
for i in $(seq 60); do grep -q "VLC initialized successfully" "$DATA/lyrenne.log" 2>/dev/null && break; sleep 1; done
sleep 15
screencapture -x "$DIAG/window.png" || echo "screencapture unavailable"
kill -0 $PID || { echo "Lyrenne exited early"; cat "$DIAG/app-stdout.txt" "$DATA/lyrenne.log" || true; exit 1; }
# Quit the way Cmd+Q does: an Apple Event to the app, answered by MacIntegration's quit handler.
perl -e 'alarm shift; exec @ARGV' 20 osascript -e 'tell application id "com.lyrenne.desktop" to quit' || echo "osascript quit failed"
for i in $(seq 20); do kill -0 $PID 2>/dev/null || break; sleep 1; done
if kill -0 $PID 2>/dev/null; then echo "::warning::still running after the quit event"; kill $PID; QUIT_OK=0; else QUIT_OK=1; fi
cp "$DATA/lyrenne.log" "$DIAG/app-lyrenne.log"
tail -60 "$DIAG/app-lyrenne.log"
grep -q "VLC initialized successfully" "$DIAG/app-lyrenne.log"
grep -q "Using bundled VLC from: .*Lyrenne.app/Contents/app/resources/vlc/lib" "$DIAG/app-lyrenne.log"
test -f "$DATA/lyrenne.db"
# Not `! grep`: set -e ignores a negated command, so that line could never fail.
if grep -E "Exception in thread|FATAL|Fatal" "$DIAG/app-lyrenne.log" "$DIAG/app-stdout.txt"; then exit 1; fi
grep -q "Quit requested by macOS" "$DIAG/app-lyrenne.log" || echo "::warning::quit handler not seen in the log"
[ "$QUIT_OK" = 1 ]
echo "macOS smoke passed"
