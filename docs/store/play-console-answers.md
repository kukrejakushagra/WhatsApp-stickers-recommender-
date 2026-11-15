# Play Console: App content answers

These are the answers for the "Let us know about the content of your app" checklist for **AI Sticker Search** (`app.stickersearch`). They describe version 1.2.2.

**Facts behind the answers:**
- The app has no accounts, server, analytics, crash reporting or ads SDK.
- Sticker files, AI descriptions, search history and the search index stay on the phone.
- The app uses the internet only to download the AI models from huggingface.co, and only when the user taps Download.

Public contact: **squabla.adm@gmail.com**

---

## 1. Sign-in details (App access)
- Choose **"All functionality is available without any special access"**.
- The app has no login.
  - The optional Gemma model needs a free Hugging Face account, but that sign-in happens on huggingface.co in the browser.
  - The file is then imported into the app.
  - Reviewers don't need it.

## 2. Ads
- **"No, my app does not contain ads."**

## 3. Content rating
- **Email:** squabla.adm@gmail.com
- **Category:** **All other app types**. That covers utility, productivity and tools.

| Question | Answer |
|---|---|
| Violence, blood, gore | No |
| Fear / horror | No |
| Sexuality, nudity | No |
| Gambling (real or simulated) | No |
| Profanity / crude humour | No |
| Drugs, alcohol, tobacco | No |
| Users can interact or exchange content | **No** |
| Shares the user's physical location with other users | No |
| Allows purchase of digital goods | No |
| Unrestricted internet access (web browser) | No |
| Is it a web browser or search engine | No |

**Why "Users can interact" is No:** "Send in WhatsApp" hands the sticker to WhatsApp through Android's share sheet. The app itself has no chat, feed or uploads.

The search only covers stickers already on the phone, so the app isn't a search engine.

**Expected rating:** Everyone / PEGI 3 / IARC 3+.

## 4. Target audience and content
- **Target age groups:** tick **18 and over** only.
  - The stickers are the user's own and could contain anything.
  - Leaving out under-13 also keeps the app out of the Families policy.
- **Could the store listing unintentionally appeal to children?** No.

## 5. Data safety
- **Does your app collect or share any of the required user data types?** Choose **No**.
  - Under Google's rules, data processed only on the device isn't "collected".
  - The app sends no user data anywhere. The model downloads send nothing about the user.
- **Is all user data encrypted in transit?** Yes. All downloads use HTTPS.
  - Only answer this if the form still shows it after you choose "No" above.
- **Can users request that their data is deleted?** Yes.
  - Uninstalling the app, or Settings → Apps → AI Sticker Search → Clear storage, deletes everything.
  - Use the privacy policy URL as the deletion link if one is asked for.
- **The preview should read:** *No data collected · No data shared.*

## 6. Government apps
- **No**, the app isn't developed by or for a government.

## 7. Financial features
- **"My app doesn't provide any financial features."**

## 8. Health
- Tick **none**: the app has no health features.

## 9. Select an app category and provide contact details
- **App or game:** App
- **Category:** **Tools**
- **Tags (up to 5):** pick the closest ones Play offers, for example Search, Messaging, Personalisation, Utilities and Productivity.
- **Email:** squabla.adm@gmail.com
- **Phone:** leave blank.
- **Website:** https://github.com/kukrejakushagra/whatsapp-stickers-recommender-
  - It's already set through the API; just confirm it.
- **External marketing:** your choice; it's optional.

---

## Coming up next: Play will ask for these too

### Advertising ID
- **"Does your app use advertising ID?"** No.

### Foreground service permissions
Play asks for a short description and a video link (an unlisted YouTube screen recording) for each service type:

- **Data sync:** "Downloads the AI search models the user chose to download, with progress and Pause shown in a notification. Started only when the user taps Download."
- **Media processing:** "Describes and indexes the user's stickers on the device so they can be searched. A long task the user starts, shown in a notification. Nothing leaves the device."

### All files access (MANAGE_EXTERNAL_STORAGE): ⚠ action needed
- Play allows this permission only for file managers, backup apps, antivirus apps and similar.
- This app would very likely be **rejected**.
- The app already works through the folder picker without it.
- **Recommendation:** remove it from the Play build in the next release. Until then, choose the "remove the permission" path if Play asks.

### Ignore battery optimisations (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
- This is a restricted-use permission.
- **Justification:** "Indexing thousands of stickers on the device is a long task the user starts. Android stops it part-way when the app is battery-optimised, so the index never finishes."
- If Play questions it, the fallback is to drop the direct prompt and only open the battery settings page. The app already explains the manual steps.

### Other
- **Privacy policy:** done.
- **News app:** No.
- **COVID-19 apps:** not applicable.
