# Sticker Search: AI search over your WhatsApp stickers (Android)

A native Android app (Kotlin + Jetpack Compose) that will index the WhatsApp stickers on your phone, understand them with on-device AI, and let you search them in natural language (English, Hindi, Hinglish).

**Current state: Milestone 1, the feasibility diagnostic.** This build doesn't search yet. It tests which ways of reading your WhatsApp stickers work on your phone, and produces a report that decides GO/NO-GO for the rest of the project.

- Discovery and feasibility: [`docs/01-discovery-report.md`](docs/01-discovery-report.md)
- Architecture, decision matrix and DB schema: [`docs/02-architecture.md`](docs/02-architecture.md)
- AI model evaluation: [`docs/03-ai-model-evaluation.md`](docs/03-ai-model-evaluation.md)
- Roadmap: [`docs/04-roadmap.md`](docs/04-roadmap.md)

## What M1 does

| Probe | How | Permission |
|---|---|---|
| A. All files access | Walks `Android/media/com.whatsapp`, `com.whatsapp.w4b` and legacy `/sdcard/WhatsApp`; records that `Android/data/com.whatsapp` is blocked | "All files access" (special app access) |
| B. Folder picker (SAF) | Persistent tree on `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers` | None; you pick the folder once |
| C. MediaStore | Queries the media index for sticker paths | Photos and media |
| D. Share-in | Receives stickers or images shared to the app | None |

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
   1. **Pick folder.** The picker opens at `WhatsApp Stickers`. Tap **Use this folder**, then **Allow**. If it doesn't open there, navigate to `Android › media › com.whatsapp › WhatsApp › Media › WhatsApp Stickers`, and note in the report whether you could.
   2. **All files access.** Tap Open settings, enable it, and come back.
   3. **Photos and media.** Tap Request and choose **Allow all**.
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
- **No persistence yet.** Results live in memory until the app closes; thumbnails are cached on disk. The Room database arrives in M2.
- **Static grid.** Grid thumbnails show the first frame; animation plays in the detail view.
- **Android 16 only.** The checklist for older versions is in the Project doc.
