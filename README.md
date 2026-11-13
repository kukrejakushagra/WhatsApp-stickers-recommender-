# AI Sticker Search

**Search your WhatsApp stickers by meaning: in English, Hindi or Hinglish, fully on your phone.**

AI Sticker Search is a native Android app (Kotlin + Jetpack Compose). It indexes the stickers WhatsApp already keeps on your phone, describes them with on-device AI, and lets you find the right one by typing what you want to say ("so done with work", "kya bakwas hai", "happy birthday"). Then you send it through WhatsApp's own sticker picker.

- **Private by design:** no analytics, no ads, no account. The internet is used for one thing only: downloading the AI models. Your stickers, chats and descriptions never leave the phone.
- **Android 9+** on 64-bit ARM phones, with **WhatsApp**. (WhatsApp Business support exists in the code but is hidden for now: `StickerPacks.BUSINESS_ENABLED`.)

---

## Contents
- [Features](#features)
- [Use the app](#use-the-app)
- [Build from source](#build-from-source)
- [Release signing (your own keys)](#release-signing-your-own-keys)
- [Secrets: what you need, what never goes in git](#secrets-what-you-need-what-never-goes-in-git)
- [Publishing your own copy](#publishing-your-own-copy)
- [Project layout](#project-layout)
- [Known limitations](#known-limitations)
- [Credits and licences](#credits-and-licences)

---

## Features

| | |
|---|---|
| **Search by meaning** | Hybrid ranking that fuses three signals (multilingual-e5 text vectors, SigLIP2 text→image vectors, and keywords). It understands negation ("not offensive", "gussa nahi") and Hinglish. Everything is in memory; there's no server. |
| **On-device understanding** | SigLIP2 makes every sticker searchable by its look within minutes. Gemma 3n then describes each one: what it shows, the text in it, emotions, when you'd send it, keywords, and English/Hinglish/Hindi phrases. It runs while charging; with thousands of stickers that takes several nights. Each sticker is done once. |
| **Describe on a PC** (optional, much faster) | Export a zip, extract it, and double-click the starter on Windows, macOS or Linux. A local model (Ollama: `gemma3:12b` or `gemma3:4b`) describes your stickers with a richer "look first" prompt. You can pause and resume, then import `results.jsonl` back. |
| **One Stickers tab** | Browse everything (newest first, with filters and a fast scroller) or search. Animated stickers play on screen the way WhatsApp's picker plays them. Every tile has a send button. |
| **Sending** | Through WhatsApp's documented third-party sticker-pack feature, so stickers arrive as real stickers. Two built-in packs (static and animated) keep your latest 30 each. You can also make your own packs from a search. |
| **Suggest stickers** | Share a chat screenshot and get fitting replies. Stickers visible in the image are found too. |
| **Related stickers** | Story sequences (clips cut from one video), look-alikes and shared topics, from a local graph. |
| **Edits and sharing** | Fix any description; your edits always win. Share descriptions with your other phone: they're matched by each file's fingerprint (SHA-256), so nothing is described twice. |

Design notes:
- [discovery](docs/01-discovery-report.md)
- [architecture](docs/02-architecture.md)
- [AI models](docs/03-ai-model-evaluation.md)
- [roadmap](docs/04-roadmap.md)

## Use the app

1. **Install:** from Google Play (when it's published), or build it yourself (below) and sideload the APK.
2. **Follow the setup** the app opens with:
   - **Find your stickers:** pick WhatsApp's **Media** folder once. The scan starts by itself.
   - **Get the search models:** the app downloads two free models (about 500 MB, no sign-in, Wi-Fi by default) while the scan runs. Search by meaning and by look works as soon as they're in.
   - **Make search smarter (optional):** choose how stickers get described.
3. **Describing**, pick one (or skip it: search by look already works for every sticker):

   | Route | Cost | Sign-in | Time for thousands of stickers |
   |---|---|---|---|
   | **On a PC** (Settings › Describe on a PC) | Free | None. Ollama downloads its own model on the PC | Hours |
   | **On the phone** with Gemma 3n | Free | **Yes:** a free Hugging Face account. Sign in at huggingface.co and accept Google's Gemma licence on [the model page](https://huggingface.co/google/gemma-3n-E2B-it-litert-preview), then download `gemma-3n-E2B-it-int4.task` (3.1 GB) in your browser and import it in Settings › AI models | Several nights, while charging |
   | **Skip** | Free | None | Nothing to wait for; stickers you describe yourself are found by your words |

4. **Settings › Background › Battery optimisation:** allow it if you describe on the phone, and follow the tip shown for your phone's maker.

| Model | File | Size | How it arrives |
|---|---|---|---|
| multilingual-e5-small (meaning search) | `onnx/model_int8.onnx` from `huggingface.co/Xenova/multilingual-e5-small` | ~118 MB | Downloaded by the app |
| SigLIP2 image | `onnx/vision_model_int8.onnx` from `huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX` | ~95 MB | Downloaded by the app |
| SigLIP2 text | `onnx/text_model_int8.onnx` from the same page | ~283 MB | Downloaded by the app |
| Gemma 3n E2B (describing, optional) | `gemma-3n-E2B-it-int4.task` from `huggingface.co/google/gemma-3n-E2B-it-litert-preview` | ~3.1 GB | You download it (sign-in needed) and import it |

Model files can also be imported by hand (Settings › AI models › Import files you downloaded); the app recognises each by its contents.

**Uninstalling** removes everything the app made: models, index, descriptions, edits, search history, previews and settings (backups are off, so nothing comes back on reinstall). Files you saved yourself, such as a PC export or a model you downloaded in the browser, stay where you put them.

## Build from source

You need:
- **JDK 17+**;
- the **Android SDK** with platform 36;
- network access on the first build (the build fetches two small tokenizer files from Hugging Face into `app/build/generated/tokenizers`; they aren't committed).

```bash
git clone https://github.com/kukrejakushagra/whatsapp-stickers-recommender-.git
```
```bash
cd whatsapp-stickers-recommender-
```
```bash
./gradlew testDebugUnitTest assembleDebug
```

- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk`. Install it with:
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **Release build** (R8-minified, about 32 MB):
  ```bash
  ./gradlew assembleRelease
  ```
  Without your own key set up (next section), the release build is signed with your machine's Android **debug key**. That's fine for sideloading, but not for Google Play.
- **Optional tokenizer tests** against the real model files:
  ```bash
  ./gradlew testDebugUnitTest -PspModelsDir=/path/with/e5-and-siglip2-folders
  ```

## Release signing (your own keys)

**No signing keys are in this repository, and none ever should be.** Everyone who builds or forks this project makes their own.

1. **Create an upload key** once, somewhere outside the repo. Keep the file and its password in a password manager, with a backup:
   ```bash
   keytool -genkeypair -v -keystore ~/keys/aistickersearch-upload.jks -storetype PKCS12 -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```
2. **Tell the build where it is.** Copy [`keystore.properties.example`](keystore.properties.example) to `keystore.properties` in the project root and fill it in. That file is **gitignored**.
   ```properties
   storeFile=/home/you/keys/aistickersearch-upload.jks
   storePassword=…
   keyAlias=upload
   keyPassword=…
   ```
   Or keep the file anywhere else and point to it:
   ```bash
   export STICKERSEARCH_KEYSTORE_PROPERTIES=/secure/path/keystore.properties
   ```
3. **Build** the Play bundle and/or a signed APK:
   ```bash
   ./gradlew bundleRelease assembleRelease
   ```
   - Bundle: `app/build/outputs/bundle/release/app-release.aab`
   - APK: `app/build/outputs/apk/release/app-release.apk`

With **Play App Signing**, Google holds the key that signs what users install, and yours is only the *upload* key. If you lose it or it leaks, reset it in Play Console › Setup › App signing.

## Secrets: what you need, what never goes in git

| Secret | Needed for | Where it lives |
|---|---|---|
| Upload keystore (`*.jks`) and its passwords | Signed release builds and Play uploads | Your machine or password manager. Gitignored: `*.jks`, `*.keystore`, `keystore.properties` |
| Hugging Face account | Downloading the Gemma model (gated licence) | Your browser only. The app and build never need a token |
| Play Console service-account JSON (optional) | Uploading to Play with `tools/play/play.py` | Base64-encoded in an environment variable (`PLAY_SERVICE_ACCOUNT_JSON_BASE64`) in your CI or cloud environment's settings, never in git |
| Upload key as variables (optional) | Signed builds on a machine without the keystore file | `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_PASSWORD` (alias `upload`) |
| `OPENAI_API_KEY` (optional) | Only if you point the PC script at a server that needs a key | An environment variable on your PC |

Nothing else is secret: the build needs no API keys, and the app's only network use is downloading the public model files. Before you push a fork, run `git status` and make sure none of the above shows up.

## Publishing your own copy

If you publish a fork:
- **Change the package name.** `applicationId` and `namespace` in `app/build.gradle.kts` are `app.stickersearch`, which already belongs to the original app on Play. Pick your own; renaming the Kotlin package is optional.
- **Change the app name** (`app/src/main/res/values/strings.xml`) and the icon.
- **Write your own privacy policy and terms**, with your contact details.
- **Follow the checklist** in [`docs/play-console.md`](docs/play-console.md): store listing, data safety ("no data collected or shared"), content rating, the declarations for all-files access, battery optimisation and foreground services, and closed testing (new personal accounts need 12 testers for 14 days).
- **Don't use "WhatsApp" in your app's name or icon**, and say it isn't affiliated with WhatsApp or Meta.

## Project layout

```
app/src/main/java/app/stickersearch/
  discovery/   finding WhatsApp sticker folders (SAF, file paths)
  index/       scanning, keyframes, AI worker, PC export/import
  ml/          tokenizers, ONNX embedders, Gemma annotator, crash guard
  search/      query parsing and hybrid ranking
  graph/       story / look-alike / topic links
  share/       WhatsApp sticker-pack provider and sending
  ui/          Compose screens and the design-system components (ui/kds)
  compat/      Android 9–16 differences in one place
app/src/main/assets/pc/   describe.py, starters and README for "Describe on a PC"
tools/ui-mirror/          web copy of the app's design tokens and icons (for mock-ups)
tools/play/               Google Play listing, graphics and bundle uploads (Android Publisher API)
docs/                     design notes, Play Console checklist, privacy policy
```

## Known limitations

- **WhatsApp favourites can't be identified.** That list lives in WhatsApp's private database, which the app never reads.
- **Lottie `.was` stickers can't go into WhatsApp packs** (WhatsApp accepts only WebP there). They can still be found and previewed.
- **Some animated stickers break WhatsApp's pack rules** (512×512, ≤500 KB, ≤10 s); the app says why.
- **On-phone describing needs about 6 GB of memory.** On smaller phones it's off by default (with a "Try anyway" switch); use Describe on a PC or Share descriptions instead.
- **Android 9+, 64-bit ARM only.**
- **WhatsApp Business is hidden for now.** The code supports it; set `StickerPacks.BUSINESS_ENABLED = true` to bring it back.
- **Battery savers differ by maker.** Settings shows the extra step for Xiaomi, Samsung, OnePlus/Oppo/Realme, Vivo, Huawei/Honor and Asus.

## Credits and licences

- **AI models:** Gemma (Google, Gemma Terms of Use), multilingual-e5-small (MIT), SigLIP2 (Apache-2.0). Users download them; they're not bundled.
- **Libraries:** ONNX Runtime (MIT), MediaPipe (Apache-2.0), Lottie (Apache-2.0), AndroidX.
- **Font:** Geist, bundled under the SIL Open Font License (`third_party/geist/OFL.txt`).
- **This project:** [MIT](LICENSE), © AI Sticker Search contributors.

AI Sticker Search is an independent project and is not affiliated with, endorsed by, or sponsored by WhatsApp LLC or Meta Platforms, Inc.
