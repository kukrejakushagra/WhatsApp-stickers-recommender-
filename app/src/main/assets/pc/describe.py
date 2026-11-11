#!/usr/bin/env python3
"""
Sticker Search: describe your stickers on a PC, then import the results on the phone.

Extract the export zip ("Extract All"), then in that folder either double-click
"Start on Windows.bat", or run:

    python describe.py          (python3 on Mac/Linux, or "py describe.py" on some Windows PCs)

It runs a vision model on this computer (Ollama) over every sticker in the folder, using the same
prompt as the phone, and writes results.jsonl into the same folder. The first time it helps you
pick and download a model.

Pause any time by pressing Enter (or Ctrl+C): the stickers in progress are finished and saved
first. You can then import results.jsonl on the phone, and later start it again to carry on where
it stopped. While it's running, results-snapshot.jsonl is a complete copy you can take any time.

Only the Python standard library is needed (Python 3.9+). Your stickers stay on this computer:
the script talks only to a model server on this machine or your home network, and refuses
anything else unless you pass --allow-remote.
"""
import argparse
import base64
import ipaddress
import json
import os
import signal
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait

DEFAULT_MODEL = "gemma3:12b"


def is_local(url: str) -> bool:
    host = urllib.parse.urlparse(url).hostname or ""
    if host in ("localhost", "") or host.endswith(".local"):
        return True
    try:
        for info in socket.getaddrinfo(host, None):
            ip = ipaddress.ip_address(info[4][0])
            if not (ip.is_loopback or ip.is_private or ip.is_link_local):
                return False
        return True
    except (socket.gaierror, ValueError):
        return False


def post(url: str, body: dict, timeout: float, headers=None) -> dict:
    req = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", **(headers or {})},
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def ask_ollama(args, prompt: str, image_b64: str) -> str:
    body = {
        "model": args.model, "prompt": prompt, "images": [image_b64], "stream": False,
        "format": "json", "keep_alive": "30m",
        "options": {"temperature": 0.2, "top_k": 20, "num_predict": 1500},
    }
    return post(args.url.rstrip("/") + "/api/generate", body, args.timeout)["response"]


def ask_openai(args, prompt: str, image_b64: str) -> str:
    body = {
        "model": args.model, "temperature": 0.2, "max_tokens": 1500,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + image_b64}},
        ]}],
    }
    key = os.environ.get("OPENAI_API_KEY")
    headers = {"Authorization": "Bearer " + key} if key else None
    reply = post(args.url.rstrip("/") + "/chat/completions", body, args.timeout, headers)
    return reply["choices"][0]["message"]["content"]


PC_PROMPT_VERSION = 2

CHECKLIST = """First notice everything that could matter:
- Who or what: person, animal, cartoon or object. Name well-known cartoon, anime or meme characters only if you are sure; never guess the names of real people.
- Face and body: expression, eyes, gestures, posture, what the hands are doing.
- Clothing and accessories, and what they suggest: a tie, suit or ID badge suggests office, work or a formal occasion; a uniform suggests that job or school; a helmet suggests riding or cycling; a party hat suggests a birthday; pyjamas suggest bedtime or a lazy day.
- Objects and props, and the activity they suggest: laptop, phone, coffee, money, food, books, steering wheel, gym weights, gifts.
- Setting and background: office, classroom, home, bed, kitchen, road, gym, beach, stage, hospital, rain; also time of day, weather, season, and festivals or occasions (Diwali, Holi, Eid, Christmas, a wedding, a cricket match).
- Text, emoji and symbols in the picture: copy text exactly; note its language and script.
- Motion and story, for animated stickers: what changes from frame to frame.
- References: a famous meme format, film or TV scene, or Bollywood dialogue, if you recognise it.

Then work out when someone would send it: what they mean, to whom (friend, family, colleague, partner), and in what situation (morning greeting, Monday at work, exam stress, payday, traffic, a late reply). Only state what is visible or strongly implied; don't invent details."""


def _vocab_from_old_prompt(prompt: str, key: str) -> list:
    """Exports made before the manifest listed the vocabularies: read them out of the old prompt text."""
    marker = f'"{key}": 1-4 of ['
    i = prompt.find(marker)
    if i < 0:
        return []
    j = prompt.find("]", i)
    return [w.strip() for w in prompt[i + len(marker):j].split(",") if w.strip()]


def build_prompt(item: dict, manifest: dict, style: str) -> str:
    """The prompt for one sticker. "pc" (default) asks a bigger model to look closely first."""
    if style == "app":
        return item["prompt"]
    emotions = manifest.get("emotions_vocab") or _vocab_from_old_prompt(item.get("prompt", ""), "emotions")
    intents = manifest.get("intents_vocab") or _vocab_from_old_prompt(item.get("prompt", ""), "intents")
    animated = bool(item.get("animated"))
    lines = ["You are labelling a WhatsApp sticker so someone can find it later by typing what they want to say in a chat. "
             "Study the picture closely before answering."]
    if animated:
        secs = item.get("duration_ms")
        lasting = f", lasting about {secs / 1000:.1f} seconds" if secs else ""
        lines.append("The picture shows 4 frames of an animated sticker in time order (left to right, then top to bottom)"
                     f"{lasting}. Describe what happens across the frames and what it expresses, not just the character.")
    lines.append("")
    lines.append(CHECKLIST)
    lines.append("")
    emo = f"1-4 of [{', '.join(emotions)}]" if emotions else "1-4 single words"
    inte = f"1-4 of [{', '.join(intents)}]" if intents else "1-4 short snake_case labels such as responding_to_joke"
    lines.append("Reply with one JSON object and nothing else, with these keys in this order:")
    lines.append('"observations": 5-12 short notes of the concrete details you noticed,')
    lines.append('"description": one or two sentences: who or what is shown, the setting or context it implies, and what it expresses '
                 '(e.g. "Cartoon office worker in a shirt and tie slumped over a laptop, exhausted by a long workday"),')
    lines.append('"text": any words written in the sticker, exactly, or "",')
    lines.append('"visible": 3-10 concrete things visible (objects, clothing, props, setting) as short English words,')
    lines.append('"setting": 1-5 situations or contexts it implies (e.g. "office", "work meeting", "birthday", "road trip"),')
    lines.append(f'"emotions": {emo},')
    lines.append(f'"intents": {inte},')
    lines.append('"tags": 8-16 short English search words covering who, what, mood, setting and situation,')
    lines.append('"phrases": 5-8 short things someone might type to find it, in English,')
    lines.append('"hinglish": 3-5 such phrases in romanized Hindi (e.g. "kya bakwas hai", "office ka scene"),')
    lines.append('"hindi": 1-3 such phrases in Devanagari,')
    lines.append('"emoji": one emoji that fits,')
    lines.append('"confidence": a number from 0 to 1 for how sure you are of the meaning.')
    return "\n".join(lines)


def readable(raw: str) -> bool:
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end <= start:
        return False
    try:
        return bool(json.loads(raw[start:end + 1]).get("description"))
    except (ValueError, AttributeError):
        return False


MODELS = [
    ("gemma3:12b", "best descriptions; needs a graphics card with about 10 GB of memory, or a Mac with 16 GB"),
    ("gemma3:4b", "faster and smaller; for laptops and modest PCs"),
]


def ollama_models(url: str) -> list:
    """Names of the models Ollama has, or exits with setup help if Ollama isn't running."""
    try:
        with urllib.request.urlopen(url.rstrip("/") + "/api/tags", timeout=10) as r:
            return [m.get("name", "") for m in json.loads(r.read()).get("models", [])]
    except (urllib.error.URLError, OSError):
        sys.exit("Ollama isn't running on this computer.\n"
                 "Install it from https://ollama.com, open it (it then runs in the background), and start this again.")


def has_model(names: list, model: str) -> bool:
    return (model if ":" in model else model + ":latest") in names


def pull_model(url: str, model: str) -> None:
    """Downloads a model through Ollama (only the model comes in; no sticker goes out)."""
    print(f"Downloading {model} through Ollama. This happens once and can take a while...")
    req = urllib.request.Request(url.rstrip("/") + "/api/pull", data=json.dumps({"model": model, "stream": True}).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=3600) as r:
        for raw in r:
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            if msg.get("error"):
                sys.exit(f"Ollama couldn't download {model}: {msg['error']}")
            total, done = msg.get("total"), msg.get("completed")
            if total and done:
                print(f"\r  {msg.get('status', '')[:30]:30} {done / total * 100:5.1f}%   ", end="", flush=True)
    print(f"\r  {model} is ready.{' ' * 40}")


def choose_model(url: str, names: list, interactive: bool) -> str:
    """First run: offer the models, download the chosen one."""
    ready = [m for m, _ in MODELS if has_model(names, m)]
    if ready:
        return ready[0]
    if not interactive:
        sys.exit(f"Ollama has no vision model for this yet. Run:  ollama pull {MODELS[0][0]}   (or {MODELS[1][0]} on a smaller PC)")
    print("Which model should describe your stickers?")
    for i, (m, why) in enumerate(MODELS, 1):
        print(f"  {i}) {m}: {why}")
    while True:
        pick = input("Type 1 or 2 (or a model name) and press Enter [1]: ").strip() or "1"
        if pick.isdigit() and 1 <= int(pick) <= len(MODELS):
            model = MODELS[int(pick) - 1][0]
            break
        if pick and not pick.isdigit():
            model = pick
            break
    if not has_model(names, model):
        pull_model(url, model)
    return model


class Export:
    """The extracted export folder (normal case), or the zip itself (older instructions)."""

    def __init__(self, path: str):
        if os.path.isdir(path) and not os.path.exists(os.path.join(path, "manifest.json")):
            # Started next to the zip instead of inside the extracted folder: use the zip if there's one.
            zips = [f for f in os.listdir(path) if f.lower().endswith(".zip") and f.lower().startswith("sticker-search-export")]
            if len(zips) == 1:
                path = os.path.join(path, zips[0])
        self._zip = None
        self._lock = threading.Lock()
        if os.path.isdir(path):
            manifest_path = os.path.join(path, "manifest.json")
            if not os.path.exists(manifest_path):
                sys.exit("There's no manifest.json here. Right-click the export zip, choose Extract All, "
                         "and start describe.py from inside the extracted folder.")
            self.folder = os.path.abspath(path)
            with open(manifest_path, encoding="utf-8") as f:
                self.manifest = json.load(f)
        else:
            self._zip = zipfile.ZipFile(path)
            self.folder = os.path.dirname(os.path.abspath(path))
            self.manifest = json.loads(self._zip.read("manifest.json"))

    def image(self, name: str) -> bytes:
        if self._zip is not None:
            with self._lock:
                return self._zip.read(name)
        with open(os.path.join(self.folder, *name.split("/")), "rb") as f:
            return f.read()


def load_settings(folder: str) -> dict:
    try:
        with open(os.path.join(folder, "settings.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_settings(folder: str, settings: dict) -> None:
    try:
        with open(os.path.join(folder, "settings.json"), "w", encoding="utf-8") as f:
            json.dump(settings, f, indent=2)
    except OSError:
        pass


def main(interactive: bool) -> None:
    global KEYS
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(description="Describe Sticker Search stickers with a local vision model.")
    p.add_argument("export", nargs="?", default=here,
                   help="the extracted export folder (default: the folder this script is in), or the export zip")
    p.add_argument("--model", default=None, help=f"model name (default: the one chosen the first time; {MODELS[0][0]} or {MODELS[1][0]})")
    p.add_argument("--api", choices=["ollama", "openai"], default="ollama",
                   help="ollama (default), or any OpenAI-compatible local server such as LM Studio or llama.cpp")
    p.add_argument("--url", default=None, help="server address (default http://localhost:11434, or http://localhost:1234/v1 for --api openai)")
    p.add_argument("--workers", type=int, default=1, help="stickers in parallel (raise it if your server handles parallel requests)")
    p.add_argument("--limit", type=int, default=0, help="stop after this many stickers (to try it out)")
    p.add_argument("--prompt", choices=["pc", "app"], default="pc",
                   help="pc (default): a detailed look-first prompt for bigger models; app: the phone's shorter prompt")
    p.add_argument("--timeout", type=float, default=300, help="seconds to wait for one reply")
    p.add_argument("--out", default=None, help="results file (default results.jsonl in the export folder)")
    p.add_argument("--allow-remote", action="store_true", help="allow a model server that isn't on this machine or your home network")
    args = p.parse_args()
    args.url = args.url or ("http://localhost:11434" if args.api == "ollama" else "http://localhost:1234/v1")

    if not is_local(args.url) and not args.allow_remote:
        sys.exit(f"{args.url} isn't on this computer or your home network, so your stickers would leave it.\n"
                 "Pass --allow-remote if that's really what you want.")

    export = Export(args.export)
    manifest = export.manifest
    settings = load_settings(export.folder)
    if args.api == "ollama":
        names = ollama_models(args.url)
        if args.model:
            if not has_model(names, args.model):
                if not interactive:
                    sys.exit(f"Ollama doesn't have {args.model} yet. Run:  ollama pull {args.model}")
                pull_model(args.url, args.model)
        elif settings.get("model") and has_model(names, settings["model"]):
            args.model = settings["model"]
        else:
            args.model = choose_model(args.url, names, interactive)
        if not args.model:
            sys.exit("No model chosen.")
    elif not args.model:
        sys.exit("With --api openai, also pass --model <the model name your server shows>.")
    settings["model"] = args.model
    save_settings(export.folder, settings)

    out_path = args.out or os.path.join(export.folder, "results.jsonl")
    snapshot_path = os.path.join(os.path.dirname(os.path.abspath(out_path)), "results-snapshot.jsonl")
    repair_tail(out_path)
    done = set()
    if os.path.exists(out_path):
        with open(out_path, encoding="utf-8") as f:
            for line in f:
                try:
                    done.add(json.loads(line)["sha256"])
                except (ValueError, KeyError):
                    pass
    todo = [it for it in manifest["items"] if it["sha256"] not in done]
    if args.limit:
        todo = todo[:args.limit]
    total = len(manifest["items"])
    earlier = sum(1 for it in manifest["items"] if it["sha256"] in done)
    if not todo:
        print(f"All {total:,} stickers are described. Copy results.jsonl from this folder to your phone and import it.")
        return
    pause_hint = "Press Enter to pause" if interactive else "Press Ctrl+C to pause"
    if earlier:
        print(f"{earlier:,} already done from earlier runs; {len(todo):,} to go, with {args.model}. {pause_hint}.")
    else:
        print(f"{total:,} stickers to describe with {args.model}. {pause_hint} any time.")

    ask = ask_ollama if args.api == "ollama" else ask_openai
    started = time.time()
    finished = failed = 0

    def describe(item):
        image = base64.b64encode(export.image(item["image"])).decode("ascii")
        t0 = time.time()
        raw = ""
        for _ in range(3):
            raw = ask(args, build_prompt(item, manifest, args.prompt), image)
            if readable(raw):
                break
        return item, raw, time.time() - t0

    # Pausing: Enter (or the first Ctrl+C) finishes and saves the stickers in progress, then stops.
    # A second Ctrl+C quits at once.
    stop = threading.Event()

    def request_pause(how: str) -> None:
        if not stop.is_set():
            stop.set()
            print(f"\nPausing ({how}): finishing the stickers in progress and saving them...", flush=True)

    def on_ctrl_c(_sig, _frame):
        if stop.is_set():
            print("\nQuitting now. Everything saved so far stays in results.jsonl.", flush=True)
            os._exit(130)
        request_pause("Ctrl+C; press it again to quit at once")

    signal.signal(signal.SIGINT, on_ctrl_c)
    if interactive:
        # Started only now: the first-run model menu above reads the keyboard itself.
        KEYS = _Keys()
        KEYS.on_line = lambda: request_pause("Enter")

    workers = max(1, args.workers)
    pending = iter(todo)
    in_flight = set()
    since_snapshot = 0
    with open(out_path, "a", encoding="utf-8") as out, ThreadPoolExecutor(max_workers=workers) as pool:
        def start_next() -> None:
            # Only as many requests as workers are ever in flight, so pausing loses nothing.
            if stop.is_set():
                return
            item = next(pending, None)
            if item is not None:
                in_flight.add(pool.submit(describe, item))

        for _ in range(workers):
            start_next()
        while in_flight:
            finished_now, _ = wait(in_flight, timeout=0.5, return_when=FIRST_COMPLETED)
            for fut in finished_now:
                in_flight.discard(fut)
                try:
                    item, raw, secs = fut.result()
                except Exception as e:  # one bad reply shouldn't stop the run
                    failed += 1
                    print(f"\n  skipped one: {e}", file=sys.stderr)
                    start_next()
                    continue
                if not readable(raw):
                    failed += 1
                    start_next()
                    continue
                line = json.dumps({"sha256": item["sha256"], "model": args.model, "raw": raw,
                                   "prompt_version": manifest.get("prompt_version", 1),
                                   "pc_prompt": PC_PROMPT_VERSION if args.prompt == "pc" else 0,
                                   "seconds": round(secs, 1)}, ensure_ascii=False) + "\n"
                # One write per sticker, pushed to disk, so a copy taken now is at worst one line short.
                out.write(line)
                out.flush()
                os.fsync(out.fileno())
                finished += 1
                since_snapshot += 1
                rate = (time.time() - started) / finished
                # rate is wall-clock time per sticker, so it already reflects parallel workers.
                left = (len(todo) - finished - failed) * rate
                if not stop.is_set():
                    print(f"\r  {finished + earlier:,}/{total:,} described · {rate:.1f} s each · about {left / 60:.0f} min left"
                          f" · {pause_hint}   ", end="", flush=True)
                start_next()
            if since_snapshot >= 50:
                write_snapshot(out_path, snapshot_path)
                since_snapshot = 0
    write_snapshot(out_path, snapshot_path)

    described = finished + earlier
    if stop.is_set() and described < total:
        print(f"\nPaused: {described:,} of {total:,} described and saved in results.jsonl (in this folder).")
        print("You can import results.jsonl on the phone now (Settings › Faster: describe on a PC › Import results).")
        print("To continue later, start this again the same way; it picks up where it stopped.")
        return
    print(f"\nDone: {described:,} of {total:,} described"
          + (f", {failed} couldn't be read (start again to retry them)." if failed else "."))
    print("Copy results.jsonl from this folder to your phone, then in Sticker Search open "
          "Settings › Faster: describe on a PC › Import results.")


def repair_tail(path: str) -> None:
    """If a run was cut off mid-line, drop the half-written last line so new lines don't join onto it."""
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        return
    with open(path, "rb+") as f:
        f.seek(-1, os.SEEK_END)
        if f.read(1) == b"\n":
            return
        f.seek(0)
        data = f.read()
        f.seek(data.rfind(b"\n") + 1)
        f.truncate()


def write_snapshot(src: str, dest: str) -> None:
    """A complete copy of the results so far, safe to copy to the phone even while the script runs."""
    if not os.path.exists(src):
        return
    tmp = dest + ".tmp"
    with open(src, "rb") as f:
        data = f.read()
    data = data[:data.rfind(b"\n") + 1]
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, dest)


class _Keys:
    """Reads Enter presses in the background: during a run they pause it, at the end they close."""

    def __init__(self) -> None:
        self.on_line = None
        self.closing = threading.Event()
        self.closed = threading.Event()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        while True:
            line = sys.stdin.readline()
            if not line:  # no keyboard (EOF)
                self.closed.set()
                return
            if self.closing.is_set():
                self.closed.set()
                return
            if self.on_line:
                self.on_line()


KEYS = None


def run() -> None:
    # Windows consoles (or redirected output) may not have "›" or "·": print a stand-in, never crash.
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except (AttributeError, ValueError):
            pass
    interactive = sys.stdin is not None and sys.stdin.isatty()
    # Double-clicked (no arguments): keep the window open at the end so the messages can be read.
    keep_open = interactive and len(sys.argv) == 1
    code = 0
    try:
        main(interactive)
    except SystemExit as e:
        if e.code not in (None, 0):
            print(e.code if isinstance(e.code, str) else f"Stopped (code {e.code}).", file=sys.stderr)
            code = e.code if isinstance(e.code, int) else 1
    except KeyboardInterrupt:
        print("\nStopped.")
        code = 130
    except Exception as e:  # show the problem instead of a window that vanishes
        print(f"\nSomething went wrong: {e.__class__.__name__}: {e}", file=sys.stderr)
        code = 1
    if keep_open:
        print("\nPress Enter to close this window.", flush=True)
        if KEYS is not None:
            KEYS.closing.set()
            KEYS.closed.wait()
        else:
            try:
                input()
            except EOFError:
                pass
    sys.exit(code)


if __name__ == "__main__":
    run()
