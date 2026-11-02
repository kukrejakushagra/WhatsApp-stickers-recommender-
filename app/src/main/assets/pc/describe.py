#!/usr/bin/env python3
"""
Sticker Search: describe your stickers on a PC, then import the results on the phone.

    python describe.py sticker-search-export.zip

Runs a vision model on this computer (Ollama by default) over every sticker in the export,
using the same prompt as the phone, and writes results.jsonl next to the zip. Stop it any time
(Ctrl+C) and run the same command again: it carries on where it stopped.

Only the Python standard library is needed (Python 3.9+). Your stickers stay on this computer:
the script talks only to a model server on this machine or your home network, and refuses
anything else unless you pass --allow-remote.
"""
import argparse
import base64
import ipaddress
import json
import os
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed

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
        "options": {"temperature": 0.2, "top_k": 20, "num_predict": 900},
    }
    return post(args.url.rstrip("/") + "/api/generate", body, args.timeout)["response"]


def ask_openai(args, prompt: str, image_b64: str) -> str:
    body = {
        "model": args.model, "temperature": 0.2, "max_tokens": 900,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + image_b64}},
        ]}],
    }
    key = os.environ.get("OPENAI_API_KEY")
    headers = {"Authorization": "Bearer " + key} if key else None
    reply = post(args.url.rstrip("/") + "/chat/completions", body, args.timeout, headers)
    return reply["choices"][0]["message"]["content"]


def readable(raw: str) -> bool:
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end <= start:
        return False
    try:
        return bool(json.loads(raw[start:end + 1]).get("description"))
    except (ValueError, AttributeError):
        return False


def check_ollama(args) -> None:
    try:
        with urllib.request.urlopen(args.url.rstrip("/") + "/api/tags", timeout=10) as r:
            names = [m.get("name", "") for m in json.loads(r.read()).get("models", [])]
    except (urllib.error.URLError, OSError) as e:
        sys.exit(f"Can't reach Ollama at {args.url} ({e}).\nInstall it from https://ollama.com and make sure it is running.")
    wanted = args.model if ":" in args.model else args.model + ":latest"
    if wanted not in names:
        sys.exit(f"Ollama doesn't have {args.model} yet. Run:\n\n    ollama pull {args.model}\n")


def main() -> None:
    p = argparse.ArgumentParser(description="Describe Sticker Search stickers with a local vision model.")
    p.add_argument("export", help="the sticker-search-export.zip from the phone")
    p.add_argument("--model", default=DEFAULT_MODEL, help=f"model name (default {DEFAULT_MODEL}; gemma3:4b is faster on modest PCs)")
    p.add_argument("--api", choices=["ollama", "openai"], default="ollama",
                   help="ollama (default), or any OpenAI-compatible local server such as LM Studio or llama.cpp")
    p.add_argument("--url", default=None, help="server address (default http://localhost:11434, or http://localhost:1234/v1 for --api openai)")
    p.add_argument("--workers", type=int, default=1, help="stickers in parallel (raise it if your server handles parallel requests)")
    p.add_argument("--limit", type=int, default=0, help="stop after this many stickers (to try it out)")
    p.add_argument("--timeout", type=float, default=300, help="seconds to wait for one reply")
    p.add_argument("--out", default=None, help="results file (default results.jsonl next to the zip)")
    p.add_argument("--allow-remote", action="store_true", help="allow a model server that isn't on this machine or your home network")
    args = p.parse_args()
    args.url = args.url or ("http://localhost:11434" if args.api == "ollama" else "http://localhost:1234/v1")

    if not is_local(args.url) and not args.allow_remote:
        sys.exit(f"{args.url} isn't on this computer or your home network, so your stickers would leave it.\n"
                 "Pass --allow-remote if that's really what you want.")
    if args.api == "ollama":
        check_ollama(args)

    zf = zipfile.ZipFile(args.export)
    manifest = json.loads(zf.read("manifest.json"))
    out_path = args.out or os.path.join(os.path.dirname(os.path.abspath(args.export)), "results.jsonl")
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
    print(f"{total} stickers in the export, {len(done)} already done, {len(todo)} to describe with {args.model}.")
    if not todo:
        print(f"Nothing left. Copy {out_path} to your phone and import it.")
        return

    ask = ask_ollama if args.api == "ollama" else ask_openai
    zip_lock, out_lock = threading.Lock(), threading.Lock()
    started = time.time()
    finished = failed = 0

    def describe(item):
        with zip_lock:
            image = base64.b64encode(zf.read(item["image"])).decode("ascii")
        t0 = time.time()
        raw = ""
        for _ in range(3):
            raw = ask(args, item["prompt"], image)
            if readable(raw):
                break
        return item, raw, time.time() - t0

    with open(out_path, "a", encoding="utf-8") as out, ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = [pool.submit(describe, it) for it in todo]
        try:
            for fut in as_completed(futures):
                try:
                    item, raw, secs = fut.result()
                except Exception as e:  # one bad reply shouldn't stop the run
                    failed += 1
                    print(f"  skipped one: {e}", file=sys.stderr)
                    continue
                if not readable(raw):
                    failed += 1
                    continue
                with out_lock:
                    out.write(json.dumps({"sha256": item["sha256"], "model": args.model, "raw": raw,
                                          "seconds": round(secs, 1)}, ensure_ascii=False) + "\n")
                    out.flush()
                finished += 1
                rate = (time.time() - started) / finished
                left = (len(todo) - finished - failed) * rate
                print(f"\r  {finished + len(done)}/{total} described · {rate:.1f} s each · about {left / 60:.0f} min left   ",
                      end="", flush=True)
        except KeyboardInterrupt:
            print("\nStopped. Run the same command again to carry on.")
            pool.shutdown(wait=False, cancel_futures=True)
            return
    print(f"\nDone: {finished} described, {failed} couldn't be read (run again to retry them).")
    print(f"Copy {out_path} to your phone, then in Sticker Search open Settings › AI › Import PC results.")


if __name__ == "__main__":
    main()
