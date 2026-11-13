# Publishing on Google Play

Package: **`app.stickersearch`** (permanent once uploaded) · version 1.0.0 (versionCode 41) · min Android 9 · arm64 · Play App Signing.

## 0. Build the bundle
Create your own upload key first (README › Release signing). It lives outside the repo. Point the build at its properties file (or copy it to `keystore.properties` in the project root, which git ignores):

```bash
STICKERSEARCH_KEYSTORE_PROPERTIES=/path/to/keystore.properties ./gradlew bundleRelease
```

Upload `app/build/outputs/bundle/release/app-release.aab`. Each upload needs a higher `versionCode` in `app/build.gradle.kts`.

## 1. Create the app (personal account)
1. Play Console › **Create app**. Name: **AI Sticker Search**. App, Free, then accept the declarations.
2. **Testing › Closed testing › Create track**. Add at least **12 testers** (a Google Group or email list), upload the AAB, and roll out.
   - Personal accounts must keep **12+ testers opted in for 14 days in a row** before they can apply for production.
3. Leave **Play App Signing** on (the default). Google keeps the app signing key, and this project's key is the upload key. If the upload key is ever lost, it can be reset in Play Console › Setup › App signing.

## 2. Store listing
- **App name** (≤30): `AI Sticker Search` (17 characters)
  - Don't put "WhatsApp" in the name or icon: that breaks Google's impersonation and trademark policy.
- **Short description** (≤80): `Find the right sticker by meaning, in English, Hindi or Hinglish. Offline.`
- **Full description:**

  > AI Sticker Search finds the perfect sticker from the ones already on your phone. Type what you want to say ("so done with work", "kya bakwas hai", "happy birthday") and it finds stickers that mean it, not just ones with matching words.
  >
  > • Search by meaning in English, Hindi and Hinglish
  > • Finds animated and Lottie stickers too, and plays them like your chat app does
  > • "Suggest stickers": share a chat screenshot and get fitting replies
  > • Related stickers: story sequences, look-alikes and shared topics
  > • Make your own sticker packs from a search
  > • Works with WhatsApp and WhatsApp Business, through WhatsApp's official sticker-pack feature
  >
  > Private by design: everything runs on your phone. The app has no internet access, no ads, no tracking and no account. You import the AI models yourself; an optional script lets a PC describe your stickers faster.
  >
  > AI Sticker Search is an independent app and is not affiliated with WhatsApp LLC or Meta.

- **Graphics:**
  - 512×512 icon.
  - 1024×500 feature graphic.
  - At least 2 phone screenshots (Stickers tab, a sticker's page, Settings).
- **Category:** Tools (or Communication). Contact email: yours.
- **Privacy policy URL:** a public link to your privacy policy (for example a published Google Doc, or [`docs/privacy-policy.md`](privacy-policy.md) here). It must name a contact and be reachable without signing in.

## 3. App content (Policy › App content)
- **Ads:** No.
- **App access:** all features are available without special access. Note for reviewers: "No login. To see content, the device needs WhatsApp stickers; pick WhatsApp's Media folder in Settings."
- **Content rating:** answer **No** to violence, sexual content, gambling, user interaction and sharing location. Stickers are the user's own. This usually gives Everyone or PEGI 3. Choose "contains user-generated content": No.
- **Target audience:** 13 and over (WhatsApp's own minimum). Not designed for children.
- **Data safety:**
  - **Data collected: None. Data shared: None.**
  - Everything stays on the device; there's no Internet permission.
  - Encryption in transit: not applicable. Deletion: the user can delete the data in the app (Settings › Data › Delete index) or by uninstalling.
- **Government app / financial / health:** No.

## 4. Sensitive permissions (Policy › App content › Sensitive permissions)
Google reviews these by hand. Have a **short screen recording** ready for each (unlisted YouTube link).

### All files access (`MANAGE_EXTERNAL_STORAGE`)
- **Core feature:** "Indexing and searching the user's WhatsApp sticker files stored in shared storage."
- **Justification:**
  > The app's core purpose is to search the sticker files WhatsApp keeps in shared storage (Android/media/com.whatsapp/…/WhatsApp Stickers). The app reads only sticker folders, never other files. The Storage Access Framework folder picker is offered first and works for most users. All files access is an optional fallback for devices where the picker can't grant the WhatsApp media folder. No data leaves the device (no Internet permission).
- **Risk:** Google often refuses all-files access when the folder picker suffices.
  - If it's refused, the fix is to remove this permission from the Play build. The folder picker keeps working, and Android 9–10 use the storage permission.
  - A sideload build can keep it.

### Ignore battery optimisations (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
> Long-running on-device AI indexing (describing thousands of stickers overnight while charging) is stopped by battery optimisation. The user grants the exemption from Settings, and it's optional; the app works without it.

If Google refuses this, switch the Settings button to open the system battery-optimisation list (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), which needs no permission, and remove it.

### Foreground service types (Policy › App content › Foreground service permissions)
- **Data sync** (Android 10–14) and **Media processing** (Android 15+):
  > A user-visible notification shows progress while the app indexes the sticker files and runs the on-device AI over them (and while it builds the user-requested export file). These are user-initiated or charging-time tasks that take minutes to hours and must not be interrupted.
- Video: start a scan in Settings, then show the progress notification.

## 5. Before each release
- [ ] Raise `versionCode` (and `versionName`).
- [ ] `./gradlew testDebugUnitTest lintRelease bundleRelease` with the upload key.
- [ ] Release notes.
- [ ] Closed test → (after 14 days, 12 testers) apply for production.

## Moving from an earlier sideloaded build
A build with a different package name is a separate app; both can be installed side by side. To carry the work over:
1. In the old app: **Settings › Faster: describe on a PC › Share descriptions**.
2. In the new app: import the models, pick the folder, scan, then **Import results** with that file.
3. Uninstall the old app.
