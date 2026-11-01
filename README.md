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
| Pass 2: meaning | Gemma 3n E2B describes each sticker from its image, or from a contact sheet of keyframes for animated ones: description, text in the sticker, emotions, conversational intents, tags, and English/Hinglish/Hindi search phrases. Newest first, while charging |
| Search | Hybrid ranking with reciprocal rank fusion of three signals: multilingual-e5 text vectors, SigLIP2 text→image vectors, and keyword matching. It also handles negation ("not offensive", "gussa nahi") and Hinglish glosses. Everything is in memory, with no vector database |
| Corrections | Edit any description, emotions, intents or tags. Your edits are stored separately and always win over the AI, including after re-analysis |
| Sending | Two dynamic "Sticker Search" packs (static and animated) served through WhatsApp's documented third-party sticker-pack provider. The sticker you choose goes first, WhatsApp opens, and you send it from its picker as a real sticker. "Share as photo" is the fallback |
| Background | Daily check while charging: scan, then visual pass, then annotation. Long runs show a progress notification (foreground media processing) |

## AI models (imported, not bundled)

Download these four files in the phone's browser, then use **Settings › AI models › Import model files**. You can pick several files at once. The app recognises each file by its contents.

| Model | File to download | Size |
|---|---|---|
| Gemma 3n E2B (understanding) | `gemma-3n-E2B-it-int4.task` from huggingface.co/google/gemma-3n-E2B-it-litert-preview. You must sign in and accept Google's Gemma licence first | ~3.1 GB |
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

## Install

Requires Android 16 (API 36) and an arm64 phone. Sideload `app/build/outputs/apk/debug/app-debug.apk`, or use adb:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First run

1. **Settings › Sticker access:** pick the Media folder, then **Run scan**.
2. **Settings › AI models:** import the four files. The visual pass starts on its own.
3. **Settings › Battery optimisation:** tap **Allow**. On Honor phones, also set the app to "Manage manually" under Battery › App launch.
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
- **Android 16, arm64 only.** The checklist for older versions is in the Project doc.

The Geist font is bundled under the SIL Open Font License (`third_party/geist/OFL.txt`).
