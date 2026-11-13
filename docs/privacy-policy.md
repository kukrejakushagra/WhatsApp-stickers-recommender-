# AI Sticker Search: Privacy Policy

_Last updated: 13 November 2026_

AI Sticker Search ("the app", package `app.stickersearch`) helps you find and send the WhatsApp stickers already on your phone. This policy explains what the app does with your information. In short: **everything stays on your phone. The app has no internet access, collects nothing, and shares nothing.**

## What the app reads
- **Sticker files in WhatsApp's media folder** (and WhatsApp Business's, if you choose). You pick the folder yourself through Android's folder picker, or grant "All files access" on Android 11+ (the storage permission on Android 9–10). The app reads only the folders whose names contain "Sticker" and skips everything else, including WhatsApp's backups.
- **Images you choose to attach** (for example, chat screenshots for "Suggest stickers"), through Android's photo picker or the share menu. Only the images you pick are read.
- **AI model files you import yourself.** You download them in your browser and import them.

The app never reads WhatsApp's private database, chats, contacts, or account.

## What the app stores
Everything it creates is stored **only on your phone**, in the app's private storage:
- an index of your stickers (file fingerprints, sizes, dates);
- small preview images and keyframes;
- descriptions and keywords made by the on-device AI, and any edits you make;
- your settings and a local diagnostics log (counts and timings, no images or chats).

Deleting the app, or using **Settings › Data › Delete index**, removes this data.

## What the app sends
- **Nothing is sent over the internet.** The app does not request the Internet permission and contains no analytics, ads, crash reporting, or tracking.
- **To WhatsApp, when you tap Send:** the sticker you chose is offered to WhatsApp (or WhatsApp Business) through WhatsApp's official sticker-pack interface, so you can send it from WhatsApp's sticker picker.
- **Files you export yourself:** "Export for PC" and "Share descriptions" write a file to a location you pick. It's yours to copy or delete. The optional PC script that comes with the export talks only to an AI model running on your own computer or home network.
- **Diagnostics report:** only if you tap Copy or Share in Settings › Diagnostics. It contains counts, timings and file names; no images or chats.

## Permissions
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
