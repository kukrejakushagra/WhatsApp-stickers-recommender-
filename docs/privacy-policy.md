# AI Sticker Search: Privacy Policy

_Last updated: 13 November 2026 (version 1.1.0)_

AI Sticker Search ("the app", package `app.stickersearch`) helps you find and send the WhatsApp stickers already on your phone. This policy explains what the app does with your information. In short: **your stickers, chats and descriptions stay on your phone. The app collects nothing and shares nothing. It uses the internet only to download its AI model files.**

## What the app reads
- **Sticker files in WhatsApp's media folder** (and WhatsApp Business's, if you choose). You pick the folder yourself through Android's folder picker, or grant "All files access" on Android 11+ (the storage permission on Android 9–10). The app reads only the folders whose names contain "Sticker" and skips everything else, including WhatsApp's backups.
- **Images you choose to attach** (for example, chat screenshots for "Suggest stickers"), through Android's photo picker or the share menu. Only the images you pick are read.
- **AI model files.** The app downloads its search models from Hugging Face (huggingface.co) when you agree to it during setup or in Settings. You can also download a model in your browser and import it.

The app never reads WhatsApp's private database, chats, contacts, or account.

## What the app stores
Everything it creates is stored **only on your phone**, in the app's private storage:
- an index of your stickers (file fingerprints, sizes, dates);
- small preview images and keyframes;
- descriptions and keywords made by the on-device AI, and any edits you make;
- your settings and a local diagnostics log (counts and timings, no images or chats).

Uninstalling the app removes all of this, including the downloaded models (backups are turned off, so nothing comes back on reinstall). **Settings › Data & privacy › Delete index** removes the index and descriptions while keeping the app. Files you saved yourself outside the app (an export, a model downloaded in your browser) stay where you put them.

## What the app sends
- **Nothing about you is sent over the internet.** The app's only network use is downloading the AI model files from Hugging Face. Those requests are ordinary file downloads: they carry no account, no identifier and none of your content, though Hugging Face, like any website, sees the request (for example your IP address) under its own privacy policy. The app contains no analytics, ads, crash reporting, or tracking.
- **To WhatsApp, when you tap Send:** the sticker you chose is offered to WhatsApp (or WhatsApp Business) through WhatsApp's official sticker-pack interface, so you can send it from WhatsApp's sticker picker.
- **Files you export yourself:** "Export for PC" and "Share descriptions" write a file to a location you pick. It's yours to copy or delete. The optional PC script that comes with the export talks only to an AI model running on your own computer or home network.
- **Diagnostics report:** only if you tap Copy or Share in Settings › Help & diagnostics. It contains counts, timings and file names; no images or chats.

## Permissions
- **Internet:** only to download the AI model files.
- **Storage / All files access:** to read sticker files in WhatsApp's folders when you don't pick the folder directly.
- **Notifications:** to show progress while indexing, describing, or exporting.
- **Foreground service (data sync / media processing):** so long indexing and AI runs can finish in the background.
- **Ignore battery optimisations:** optional, so overnight runs aren't stopped early.

## Children
The app is not directed at children under 13.

## Changes
If this policy changes, the new version will be published at this address with a new date.

## Contact
Questions: **[your contact email]**

_AI Sticker Search is an independent app and is not affiliated with, endorsed by, or sponsored by WhatsApp LLC or Meta Platforms, Inc._
