Sticker Search: describe your stickers on a PC
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
2. Windows: double-click "Start on Windows.bat".
   Mac or Linux: open a Terminal in this folder and run:  python3 describe.py
3. The first time, it asks which model to use and downloads it (only the model is downloaded;
   your stickers never leave this PC):
     1) gemma3:12b  best; needs a graphics card with about 10 GB of memory, or a Mac with 16 GB
     2) gemma3:4b   faster; for laptops and modest PCs
   Your choice is remembered for next time.
4. It shows progress and the time left. Results are saved in this folder as results.jsonl.

Pause and continue
------------------
- Press Enter to pause. It finishes the stickers it's working on, saves them, and says how many
  are done. Ctrl+C also pauses; pressing Ctrl+C twice quits at once. (If Windows then asks
  "Terminate batch job (Y/N)?", either answer is fine: everything is already saved.)
- To continue, start it again the same way. It skips everything already done.
- Want results while it's still running? Copy results-snapshot.jsonl instead (always complete,
  updated every 50 stickers).

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
