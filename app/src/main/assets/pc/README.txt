Sticker Search: describe your stickers on a PC
=============================================

This zip holds the stickers your phone hasn't described yet: one picture each (animated ones
as four frames on one image) plus the exact instructions the phone's AI would use. A PC with a
decent graphics card does this in a few hours instead of a few nights.

Your stickers stay with you: this file only goes where you copy it, and describe.py only talks
to a model running on your own computer.

1. Install Ollama from https://ollama.com (Windows, macOS or Linux) and start it.

2. Download a vision model. In a terminal:

       ollama pull gemma3:12b

   gemma3:12b needs a graphics card with about 10 GB of memory, or a Mac with 16 GB.
   On a smaller PC use gemma3:4b instead (faster, a little less accurate), and add
   --model gemma3:4b to the command in step 3.

3. Install Python 3.9 or newer (https://python.org), then in this folder run:

       python describe.py sticker-search-export.zip

   It shows progress and time left. Stop it with Ctrl+C whenever you like; running the same
   command again carries on where it stopped. Results go into results.jsonl in this folder.

4. Copy results.jsonl to your phone (USB, or any way you like), then in Sticker Search open
   Settings > AI > Import PC results and pick it. Search uses the new descriptions straight away;
   the meaning-search vectors and related-sticker links are added in the background.

Other local servers (LM Studio, llama.cpp server, vLLM) work too:

       python describe.py sticker-search-export.zip --api openai --url http://localhost:1234/v1 --model <name>

Stickers already described on the phone, or ones you've edited, are never overwritten.
