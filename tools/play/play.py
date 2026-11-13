#!/usr/bin/env python3
"""Google Play uploads for AI Sticker Search, with the Android Publisher API (v3).

Standard library only; `openssl` signs the service account's token request.

Secrets come from environment variables and are never printed or written into the repo:
  PLAY_SERVICE_ACCOUNT_JSON_BASE64   the service account's JSON key, base64-encoded (one line)
  UPLOAD_KEYSTORE_BASE64             the upload keystore (.jks), base64-encoded   (upload only)
  UPLOAD_KEYSTORE_PASSWORD           its store password                           (upload only)
  UPLOAD_KEY_PASSWORD                the key's password                           (upload only)
  UPLOAD_KEY_ALIAS                   optional, default "upload"

Commands (several can be given; they go into one edit, committed once at the end):
  listing            title, short and full description, one language per docs/store/listing*.json
  details            contact website from docs/store/details.json; --contact-email sets the public
                     contact email (otherwise it's left as set in the Console)
  images             icon, feature graphic and phone screenshots from docs/store/
  upload [--track T] build a signed bundle and release it to a track (default: internal), with the
                     "What's new" notes for its versionCode from docs/store/release-notes.json
  show               print the live listing (read-only)

  --dry-run          prepare everything and validate the edit, but don't commit it
"""
import argparse, base64, json, os, pathlib, shutil, subprocess, sys, tempfile, time
import urllib.error, urllib.parse, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
STORE = ROOT / "docs/store"
PACKAGE = "app.stickersearch"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD_API = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def die(msg):
    sys.exit(f"play.py: {msg}")


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def service_account():
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON_BASE64")
    if not raw:
        die("PLAY_SERVICE_ACCOUNT_JSON_BASE64 isn't set. Add it in the environment's settings, then start a new session.")
    try:
        return json.loads(base64.b64decode(raw))
    except Exception:
        die("PLAY_SERVICE_ACCOUNT_JSON_BASE64 isn't valid base64-encoded JSON.")


def access_token(sa, tmp: pathlib.Path) -> str:
    """Service-account JWT (RS256, signed by openssl) exchanged for an OAuth access token."""
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": sa["client_email"], "scope": SCOPE, "aud": sa["token_uri"],
        "iat": now, "exp": now + 3000,
    }).encode())
    unsigned = f"{header}.{claims}".encode()
    key = tmp / "sa.pem"
    key.touch(mode=0o600)
    key.write_text(sa["private_key"])
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key)], input=unsigned,
                         capture_output=True, check=True).stdout
    key.unlink()
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": f"{unsigned.decode()}.{b64url(sig)}",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(sa["token_uri"], data=body)) as r:
        return json.load(r)["access_token"]


class Play:
    def __init__(self, token):
        self.token = token
        self.edit = None

    def call(self, method, url, body=None, data=None, content_type=None):
        headers = {"Authorization": f"Bearer {self.token}"}
        if body is not None:
            data = json.dumps(body).encode()
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                text = r.read().decode()
                return json.loads(text) if text else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            try:
                detail = json.loads(detail)["error"]["message"]
            except Exception:
                pass
            die(f"{method} {url.split(PACKAGE)[-1] or '/'} failed: HTTP {e.code}: {detail}")

    def begin(self):
        self.edit = self.call("POST", f"{API}/edits", body={})["id"]

    def e(self, path=""):
        return f"{API}/edits/{self.edit}{path}"

    def u(self, path=""):
        return f"{UPLOAD_API}/edits/{self.edit}{path}"


def do_listing(play: Play):
    for f in sorted(STORE.glob("listing*.json")):
        put_listing(play, json.loads(f.read_text()))


def do_details(play: Play, contact_email=None):
    d = json.loads((STORE / "details.json").read_text())
    body = {k: v for k, v in d.items() if k in ("contactWebsite", "contactEmail", "defaultLanguage") and v}
    if contact_email:  # passed on the command line, so no address is stored in the repo
        body["contactEmail"] = contact_email
    # Read what's there and write it back with only these fields changed, so the contact email and
    # phone stay exactly as set in the Console.
    current = play.call("GET", play.e("/details"))
    play.call("PUT", play.e("/details"), body={**current, **body})
    print("  details: " + ", ".join(f"{k} = {v}" for k, v in body.items()))


def put_listing(play: Play, d):
    lang = d["language"]
    try:
        old = play.call("GET", play.e(f"/listings/{lang}"))
    except SystemExit:
        old = {}  # no listing in this language yet
    for k in ("title", "shortDescription", "fullDescription"):
        same = old.get(k) == d[k]
        print(f"  listing {lang} {k}: {'unchanged' if same else 'updated'} ({len(d[k])} characters)")
    play.call("PUT", play.e(f"/listings/{lang}"), body=d)


def do_images(play: Play):
    lang = json.loads((STORE / "listing.json").read_text())["language"]
    sets = {
        "icon": [STORE / "icon.png"],
        "featureGraphic": [STORE / "feature-graphic.png"],
        "phoneScreenshots": sorted((STORE / "screenshots").glob("phone-*.png")),
    }
    for kind, files in sets.items():
        files = [f for f in files if f.exists()]
        if not files:
            print(f"  images {kind}: no files, left as they are")
            continue
        play.call("DELETE", play.e(f"/listings/{lang}/{kind}"))
        for f in files:
            play.call("POST", play.u(f"/listings/{lang}/{kind}?uploadType=media"), data=f.read_bytes(), content_type="image/png")
        print(f"  images {kind}: {len(files)} uploaded")


def build_bundle(tmp: pathlib.Path) -> pathlib.Path:
    need = ["UPLOAD_KEYSTORE_BASE64", "UPLOAD_KEYSTORE_PASSWORD", "UPLOAD_KEY_PASSWORD"]
    missing = [n for n in need if not os.environ.get(n)]
    if missing:
        die(f"{', '.join(missing)} not set. Add them in the environment's settings, then start a new session.")
    ks = tmp / "upload.jks"
    ks.touch(mode=0o600)
    ks.write_bytes(base64.b64decode(os.environ["UPLOAD_KEYSTORE_BASE64"]))
    props = tmp / "keystore.properties"
    props.touch(mode=0o600)
    props.write_text(
        f"storeFile={ks}\nstorePassword={os.environ['UPLOAD_KEYSTORE_PASSWORD']}\n"
        f"keyAlias={os.environ.get('UPLOAD_KEY_ALIAS', 'upload')}\nkeyPassword={os.environ['UPLOAD_KEY_PASSWORD']}\n")
    env = dict(os.environ, STICKERSEARCH_KEYSTORE_PROPERTIES=str(props))
    print("  building the signed bundle (a few minutes)")
    subprocess.run(["./gradlew", "-q", "bundleRelease"], cwd=ROOT, env=env, check=True)
    return ROOT / "app/build/outputs/bundle/release/app-release.aab"


def do_upload(play: Play, track: str, tmp: pathlib.Path):
    aab = build_bundle(tmp)
    r = play.call("POST", play.u("/bundles?uploadType=media"), data=aab.read_bytes(), content_type="application/octet-stream")
    code = r["versionCode"]
    print(f"  bundle uploaded: versionCode {code}")
    release = {"versionCodes": [str(code)], "status": "completed"}
    notes_file = STORE / "release-notes.json"
    notes = json.loads(notes_file.read_text()).get(str(code), {}) if notes_file.exists() else {}
    if notes:
        release["releaseNotes"] = [{"language": l, "text": t} for l, t in notes.items()]
    play.call("PUT", play.e(f"/tracks/{track}"), body={"track": track, "releases": [release]})
    print(f"  release on track '{track}': versionCode {code}" + (f", notes in {', '.join(notes)}" if notes else ""))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("commands", nargs="+", choices=["listing", "details", "images", "upload", "show"])
    ap.add_argument("--track", default="internal")
    ap.add_argument("--contact-email", help="public contact email for the listing (details)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    sa = service_account()
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="play-"))
    try:
        play = Play(access_token(sa, tmp))
        play.begin()
        if args.commands == ["show"]:
            for l in play.call("GET", play.e("/listings")).get("listings", []):
                print(json.dumps({k: l.get(k) for k in ("language", "title", "shortDescription")}, ensure_ascii=False))
            d = play.call("GET", play.e("/details"))
            print(json.dumps({"defaultLanguage": d.get("defaultLanguage"), "contactWebsite": d.get("contactWebsite")}))
            for t in play.call("GET", play.e("/tracks")).get("tracks", []):
                rel = [(r.get("versionCodes"), r.get("status"), [n["language"] for n in r.get("releaseNotes", [])]) for r in t.get("releases", [])]
                print(json.dumps({"track": t["track"], "releases": rel}))
            play.call("DELETE", play.e())
            return
        for c in args.commands:
            if c == "listing": do_listing(play)
            elif c == "details": do_details(play, args.contact_email)
            elif c == "images": do_images(play)
            elif c == "upload": do_upload(play, args.track, tmp)
        play.call("POST", play.e(":validate"))
        if args.dry_run:
            play.call("DELETE", play.e())
            print("dry run: the edit is valid; nothing was committed.")
        else:
            play.call("POST", play.e(":commit"))
            print("committed: the changes are live in Play Console (store listing changes may go to review).")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
