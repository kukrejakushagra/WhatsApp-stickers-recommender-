#!/bin/sh
# Sticker Search: describes the stickers in this folder with a model on this Mac.
# Double-click to start. The first time, macOS may warn it's from an unidentified developer:
# right-click this file, choose Open, then Open again.
cd "$(dirname "$0")" || exit 1
if command -v python3 >/dev/null 2>&1; then
    exec python3 describe.py "$@"
elif command -v python >/dev/null 2>&1; then
    exec python describe.py "$@"
fi
echo "Python 3 isn't installed on this Mac."
echo "Install it from https://www.python.org/downloads/ (or with Homebrew: brew install python),"
echo "then double-click \"Start on Mac.command\" again."
printf "Press Enter to close. "
read -r _
