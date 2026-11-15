#!/bin/sh
# Flatpak entry point. The app finds the libVLC built into this Flatpak through LYRENNE_VLC_DIR;
# libVLC finds its plugins in /app/lib/vlc/plugins by itself (its compiled-in path).
export LYRENNE_VLC_DIR=/app/lib
exec /app/lib/lyrenne/bin/Lyrenne "$@"
