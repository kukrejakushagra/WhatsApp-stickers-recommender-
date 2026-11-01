# Sticker Search: AI search over your WhatsApp stickers (Android)

A native Android app (Kotlin + Jetpack Compose) that will index the WhatsApp stickers on your phone, understand them with on-device AI, and let you search them in natural language (English, Hindi, Hinglish).

**Current state: Milestone 2, indexing.** Sticker access was verified on a real device (about 5,000 unique stickers reachable). The app now keeps a local index of your WhatsApp stickers:
- Room database.
- Incremental WorkManager scans.
- Thumbnails.
- Keyframes for animated WebP and Lottie `.was` stickers.

Search arrives in M3/M4.

- Discovery and feasibility: [`docs/01-discovery-report.md`](docs/01-discovery-report.md)
- Architecture, decision matrix and DB schema: [`docs/02-architecture.md`](docs/02-architecture.md)
- AI model evaluation: [`docs/03-ai-model-evaluation.md`](docs/03-ai-model-evaluation.md)
- Roadmap: [`docs/04-roadmap.md`](docs/04-roadmap.md)

## What M1 does

| Probe | How | Permission |
|---|---|---|
| Folder picker (SAF, primary) | Persistent tree on `Android/media/com.whatsapp/WhatsApp/Media`; reads `WhatsApp Stickers` and `WhatsApp Backup Excluded Stickers`, skips everything else | None; you pick the folder once |
| All files access (diagnostic) | Walks `Android/media/com.whatsapp` to find sticker folders the picker misses; records that `Android/data/com.whatsapp` is blocked | "All files access" (special app access) |
| Share-in | Receives stickers or images shared to the app | None |

MediaStore was tested in the first device run and dropped: it only indexes WhatsApp's encrypted `Backups/Stickers` copies.

For every file found, the app:
1. sniffs the real format (static or animated WebP, GIF, PNG/APNG, zip/Lottie);
2. computes a SHA-256 hash and deduplicates across sources;
3. generates a 256 px thumbnail and checks that animated files decode as animations;
4. shows the unique stickers in a grid, with animated playback in the detail view;
5. offers a **test send** to WhatsApp, either directly or through the system share sheet. You always pick the chat and tap Send yourself.

The **Report** tab produces JSON you can copy or share. It contains counts, formats, folder names and hash-based file names only: no images, no chats. The app has **no INTERNET permission**.

## Build

Requirements:
- JDK 17 or newer;
- Android SDK with platform 36 (set `ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install

The app requires Android 16 (API 36). On the phone, allow installs from the app you use to open the APK (Files, a browser or a chat app), then open `app-debug.apk`.

To install with adb instead:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Device testing (M1 GO/NO-GO)

1. Open **Sticker Search**. On the **Setup** tab:
   1. **Pick folder.** The picker opens at `Media`. Tap **Use this folder**, then **Allow**. If it doesn't open there, navigate to `Android › media › com.whatsapp › WhatsApp › Media`.
   2. **All files access** (optional). Tap Open settings, enable it, and come back.
2. Tap **Run scan**. Wait for it to finish; 1,000+ files take a minute or two.
3. On the **Stickers** tab:
   1. Check the unique count.
   2. Scroll through and check that the stickers you use most are there.
   3. Use the source filter chips to see what each method found.
4. Tap an **animated** sticker and confirm it plays. Then try:
   1. **Send to WhatsApp.** Pick a chat, such as yourself or a test group, and send it. Did it arrive as a **sticker** or as a **photo**? Is it still animated?
   2. **Share…** Same questions, through the share sheet.
5. In WhatsApp, long-press a sticker in a chat. Is there a **Share** option that lists Sticker Search? If yes, share one and note that the app logs it.
6. On the **Report** tab, tap **Copy** and paste the report into the Claude chat, together with your answers to 3, 4 and 5.

## Known limitations

- **Favourites can't be identified.** WhatsApp keeps the favourites list in its private database, which the app does not and cannot read. The app indexes every reachable sticker; ranking does the rest.
- **Some favourites may be missing.** Favourites that came from WhatsApp's own sticker packs, or whose received copy was deleted by WhatsApp's storage cleanup, are expected to be unreachable.
- **All files access is broad.** It's only used for this diagnostic, and would not be allowed on Google Play for this use. The folder picker is the long-term path.
- **Scans run while the app is in the foreground or shortly after.** A scheduled background sync arrives in M6. An interrupted scan resumes where it left off.
- **Static grid.** Grid thumbnails show the first frame; animation plays in the detail view.
- **Sharing sends a photo.** WhatsApp receives an `ACTION_SEND` WebP as a photo, not a sticker; real sticker sending is planned through WhatsApp's sticker-pack provider (M5).
- **Liquid-glass panels are frosted, not refracting.** Compose has no backdrop blur without an extra library.

The Geist font is bundled under the SIL Open Font License (`third_party/geist/OFL.txt`).
- **Android 16 only.** The checklist for older versions is in the Project doc.
