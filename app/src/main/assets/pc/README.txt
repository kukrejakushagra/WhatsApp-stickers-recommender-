AI Sticker Search: describe your stickers on a PC
==============================================

This folder holds the stickers your phone hasn't described yet: one picture each (animated ones
as four frames on one picture) and the exact instructions the phone's AI uses. A PC with a decent
graphics card does them in hours instead of nights. Your stickers stay with you: nothing here is
uploaded anywhere.

Before you start (once)
-----------------------
- Install Ollama from https://ollama.com and open it. It then runs quietly in the background.
- Install Python 3 from https://www.python.org/downloads/
  (on Windows, tick "Add python.exe to PATH" in the installer).

Run it
------
1. If you haven't yet: right-click the zip and choose "Extract All". Use the extracted folder,
   the one this README is in. Don't move anything out of it.
2. Start it from that folder:
   - Windows: double-click "Start on Windows.bat".
   - Mac: double-click "Start on Mac.command". The first time, macOS may say it's from an
     unidentified developer: right-click the file, choose Open, then Open again.
   - Linux: double-click "start-linux.sh" and choose Run (or Run in Terminal), or in a terminal
     in this folder run:  ./start-linux.sh
3. The first time, it asks which model to use and downloads it (only the model is downloaded;
   your stickers never leave this PC):
     1) gemma3:12b  best; needs a graphics card with about 10 GB of memory, or a Mac with 16 GB
     2) gemma3:4b   faster; for laptops and modest PCs
   Your choice is remembered for next time. It also asks whether to redo the stickers your
   phone's AI already described (a PC model usually does better). That answer is remembered
   too; start with --ask-again to be asked again.
4. It shows progress and the time left. Results are saved in this folder as results.jsonl.

Too slow, or Ollama keeps stopping? The model is probably too big for this PC's memory (the
script tells you if so). Switch to the faster gemma3:4b by starting it with --choose-model:
   Windows (in a terminal in this folder):  py describe.py --choose-model
   Mac (Terminal in this folder):           ./"Start on Mac.command" --choose-model
   Linux (terminal in this folder):         ./start-linux.sh --choose-model
If Ollama stops while it's working, the script waits for it to come back (open Ollama again)
instead of skipping stickers; nothing is lost.

Pause and continue
------------------
- Press Enter to pause. It finishes the stickers it's working on, saves them, and says how many
  are done. Ctrl+C also pauses; pressing Ctrl+C twice quits at once. (If Windows then asks
  "Terminate batch job (Y/N)?", either answer is fine: everything is already saved.)
- To continue, start it again the same way. It skips everything already done.
- Want results while it's still running? Copy results-snapshot.jsonl instead (always complete,
  updated every 50 stickers).

Redo stickers with the improved prompt
--------------------------------------
This version asks the model to look closely first (clothing, props, setting, text, what they
suggest), so descriptions are richer. To redo stickers an older version already described, rename
results.jsonl (e.g. to results-old.jsonl) and start again. When you import the new file, its
descriptions replace the older ones.

Back on the phone
-----------------
Copy results.jsonl from this folder to your phone (USB cable, or any way you like). In Sticker
Search open Settings > "Faster: describe on a PC" > Import results and pick it. You can import
after a pause and again at the end: stickers already imported are skipped.

PC descriptions replace ones the phone made (a bigger model usually does better). Your own edits
in the app always stay on top.

Other model servers
-------------------
LM Studio, llama.cpp server or vLLM work too. In a terminal in this folder:
    python describe.py --api openai --url http://localhost:1234/v1 --model <name>
