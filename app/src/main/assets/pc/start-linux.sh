#!/bin/sh
# Sticker Search: describes the stickers in this folder with a model on this PC.
# Double-click and choose "Run" (or "Run in Terminal"), or run ./start-linux.sh in a terminal here.
cd "$(dirname "$0")" || exit 1
if command -v python3 >/dev/null 2>&1; then
    exec python3 describe.py "$@"
elif command -v python >/dev/null 2>&1; then
    exec python describe.py "$@"
fi
echo "Python 3 isn't installed."
echo "Install it with your package manager (for example: sudo apt install python3), then start this again."
printf "Press Enter to close. "
read -r _
