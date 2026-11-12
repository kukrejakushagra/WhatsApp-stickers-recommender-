# Sticker Search: AI search over your WhatsApp stickers (Android)

A native Android app (Kotlin + Jetpack Compose) that indexes the WhatsApp stickers already on your phone. It understands them with on-device AI and lets you search them by meaning, in English, Hindi or Hinglish, then send the one you pick through WhatsApp's own sticker picker. Everything runs on the phone, and the app has **no INTERNET permission**.

- Discovery, device results and feasibility: [`docs/01-discovery-report.md`](docs/01-discovery-report.md)
- Architecture, decision matrix and DB schema: [`docs/02-architecture.md`](docs/02-architecture.md)
- AI model evaluation and the two-pass plan: [`docs/03-ai-model-evaluation.md`](docs/03-ai-model-evaluation.md)
- Roadmap: [`docs/04-roadmap.md`](docs/04-roadmap.md)

## What it does

| Part | How |
|---|---|
| Access | You pick WhatsApp's `Media` folder once (Storage Access Framework). The app reads `WhatsApp Stickers` and `WhatsApp Backup Excluded Stickers`, and skips the encrypted `Backups/` |
| Index | Room database; incremental WorkManager scans that skip unchanged files; SHA-256 dedup; thumbnails; keyframes for animated WebP (pure-Kotlin frame decode) and Lottie `.was` stickers; missing-file detection |
| Pass 1: looks | SigLIP2 image embeddings for every sticker (minutes), so all of them can be searched visually on the first night |
| Pass 2: meaning | Gemma 3n E2B describes each sticker from its image, or from a contact sheet of keyframes for animated ones: description, text in the sticker, emotions, conversational intents, tags, and English/Hinglish/Hindi search phrases. Newest first, while charging. Each result is saved in the database once; app opens never re-run the AI, and later runs only describe new stickers. A sticker's page lists its keywords and search phrases, and tapping one searches for it |
| Describe on a PC | Optional and much faster. **Settings › AI › Export for PC** saves a zip with each undescribed sticker's image and the same prompt the phone uses, plus `describe.py` (Python standard library only) and a README. On your computer, **Extract All** and double-click `Start on Windows.bat` (or run `python3 describe.py` in the folder): the first run offers `gemma3:12b` or `gemma3:4b` and downloads it through Ollama; any OpenAI-compatible local server works too. Enter pauses (finishing and saving the stickers in progress), starting again resumes, and `results.jsonl` lands in the same folder. **Import PC results** reads it back by SHA-256. The script refuses model servers outside your machine or home network unless you pass `--allow-remote`. Source: `app/src/main/assets/pc/` |
| Search | Hybrid ranking with reciprocal rank fusion of three signals: multilingual-e5 text vectors, SigLIP2 text→image vectors, and keyword matching. It also handles negation ("not offensive", "gussa nahi") and Hinglish glosses. Everything is in memory, with no vector database |
| Help me reply | Attach up to 3 screenshots or images (photo picker, or share them into the app). Gemma reads the chat and any text you add, says what's going on, and suggests reply stickers. Library stickers that appear in the images are found by exact file or by SigLIP2 match of image crops |
| Related stickers (graph) | A local sticker graph with three link kinds: **story** (clips cut from one video, found when one clip's last keyframe matches the next one's first), **looks alike** (same character or art) and **topic** (a shared specific theme such as driving or cycling, confirmed by meaning). Search adds a "Related" section by expanding the top results through the graph; each sticker shows its story in order, its look-alikes and its topics |
| One Stickers tab | Search and the library are one screen. With the search box empty you browse every sticker, newest first, with filters (Animated, Lottie, Described, Not described, Need a look) and a fast scroller; typing switches to search results. Every sticker tile has a **send button** (and long-press works too) that sends it in WhatsApp straight away; related stickers in a sticker's page send on long-press |
| Corrections | Edit any description, emotions, intents or tags. Your edits are stored separately and always win over the AI, including after re-analysis |
| Sending | Two dynamic "Sticker Search" packs (static and animated) served through WhatsApp's documented third-party sticker-pack provider. WhatsApp asks to add each pack once (or all at once with Settings › WhatsApp packs › **Quick send**); the app remembers WhatsApp's answer, so after that **Send in WhatsApp** puts the sticker first in the pack and takes you straight back to the chat you left, where you tap the sticker icon and send it as a real sticker. "Share as photo" is the fallback |
| Your own packs | On the Stickers tab, tap **Select**, pick up to 30 stickers (**Top 30** of the results, or **First 30** in the current filter when browsing), then **Create pack**. A mixed pick becomes a static pack and an animated pack, as WhatsApp requires, each with at least 3 stickers. WhatsApp's "Add" screen opens for each. From any sticker, **Add to pack** puts it in one of your packs. Settings › WhatsApp packs lets you rename packs, remove stickers, or delete a pack |
| Background | Daily check while charging: scan, then visual pass, then annotation. Long runs show a progress notification (foreground media processing). **Pause** (Settings › AI, or the notification's "Pause describing") stops describing until **Resume**; nothing done is lost |

## AI models (imported, not bundled)

Download these four files in the phone's browser, then use **Settings › AI models › Import model files**. You can pick several files at once. The app recognises each file by its contents.

| Model | File to download | Size |
|---|---|---|
| Gemma 3n E2B (understanding) | `gemma-3n-E2B-it-int4.task`: huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task. It downloads directly once you're signed in and have accepted Google's Gemma licence on the model page | ~3.1 GB |
| multilingual-e5-small (meaning search) | `onnx/model_int8.onnx` from huggingface.co/Xenova/multilingual-e5-small | ~118 MB |
| SigLIP2 image | `onnx/vision_model_int8.onnx` from huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX | ~95 MB |
| SigLIP2 text | `onnx/text_model_int8.onnx` from the same page | ~283 MB |

The two small tokenizer files are downloaded during the Gradle build into `app/build/generated/tokenizers`; they're not committed.

## Build

Requirements:
- JDK 17 or newer;
- Android SDK platform 36;
- network access during the first build, to fetch the tokenizers.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The tokenizer tests compare against reference IDs and run only when the tokenizer files are provided:

```bash
./gradlew testDebugUnitTest -PspModelsDir=/path/with/e5-and-siglip2-folders
```

## Release build

The sideload build is the minified release, about 32 MB. R8 shrinking takes the code from about 35 MB to 4.5 MB, and native libraries are stored compressed. It's signed with the debug key so it installs over earlier builds:

```bash
./gradlew assembleRelease
```

## Install

Works on **Android 9 or newer** (API 28+) with a **64-bit ARM** phone (nearly every phone since about 2017), with **WhatsApp, WhatsApp Business, or both**. Sideload `app/build/outputs/apk/release/app-release.apk`, or use adb:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## First run

1. **Settings › Sticker access:** pick the Media folder, then **Run scan**.
2. **Settings › AI models:** import the four files. The visual pass starts on its own.
3. **Settings › Battery optimisation:** tap **Allow**, and follow the tip shown for your phone's maker.
4. Leave the phone **charging overnight**. Gemma describes stickers newest first, taking a few nights for about 5,000. **Start now** runs it immediately, on battery too.
5. **Search:** type what you want to say. Open a result and tap **Send in WhatsApp**. The first time, WhatsApp asks you to add the "Sticker Search" pack. After that, the sticker is first in that pack in WhatsApp's picker.

## Known limitations

- **Favourites can't be identified.** WhatsApp keeps that list in its private database, which the app never reads. Every reachable sticker is indexed.
- **Lottie `.was` stickers can't go into WhatsApp packs.** WhatsApp only accepts WebP there. They can still be found and previewed.
- **Some animated stickers can't go into packs.** That happens when they break WhatsApp's pack rules (512×512, ≤500 KB, ≤10 s); the app says why.
- **WhatsApp may not refresh a pack it has already shown until the picker is reopened.**
- **Annotation is slow on mid-range phones** (tens of seconds per sticker). Search works by looks until then.
- **Android decides background timing.** Battery savers can delay runs; every step resumes where it stopped.
- **Glass panels are frosted, not refracting.** Compose has no backdrop blur without an extra library.
- **Android 9+, 64-bit ARM.** 32-bit and x86 phones aren't supported (the on-phone AI engines ship 64-bit ARM builds).
- **On-phone describing needs about 6 GB of memory.** On phones with less, it's off by default (Settings › Your stickers › Try anyway turns it on). Use Describe on a PC or Share descriptions there; search by look works on every phone.
- **WhatsApp Business** keeps its own sticker folder: pick it in Settings › Sticker access › WhatsApp Business folder. With both apps installed, Send asks once which one to use (changeable in Settings › WhatsApp packs).
- **Battery savers differ by maker.** Settings › Background › Battery optimisation shows the extra step for your phone's maker (Xiaomi, Samsung, OnePlus/Oppo/Realme, Vivo, Huawei/Honor, Asus).

The Geist font is bundled under the SIL Open Font License (`third_party/geist/OFL.txt`).
