# Stickman Studio for Android

The Android app shows the same page as `stickman-studio/index.html` (it is packed into the APK at
build time), so every feature of the web version works the same. Native code adds what a WebView
cannot do on its own:

- **Share into the app:** in ChatGPT or Gemini, open the generated grid image → **Share → Stickman Studio**.
  It goes into the selected (yellow) card and is cut into its scenes. A voiceover can be shared the same way.
- **Paste image** reads an image copied to the Android clipboard.
- **Open ChatGPT / Open Gemini** buttons next to *Copy next prompt*.
- Exported videos are saved to the gallery in **Movies/Stickman Studio**; a **Share video** button
  appears after export. Project files, prompts and SRT files go to **Download/Stickman Studio**.
- The screen stays on while a video is exporting.
- **Open in Stickman Studio** (button in the Colab / Kaggle voice notebook) opens the app with the voice
  server link (`stickmanstudio://connect?url=…`); the app asks before connecting. A copied server link is
  noticed when you switch back to the app.

## Two apps: Stickman Studio and 3D Studio
The same code builds two apps (Gradle flavours): **Stickman Studio** (`com.gopalkrishan.stickmanstudio`) and
**3D Studio** (`com.gopalkrishan.studio3d`), which makes 3D Pixar-style cinematic videos: Pixar-style art style for
the pictures, a cinematic look in the video (film grade, vignette, thin letterbox bars, slower fades, more camera
motion) and no cartoon doodles. They install side by side with separate projects and settings. CI publishes both in
the `android-latest` release (`StickmanStudio.apk`, `3DStudio.apk`). For YouTube upload, 3D Studio needs its own
Android OAuth client (package `com.gopalkrishan.studio3d`, same SHA-1).

## Auto Pilot (★ tab)
Makes one video a day from the **Mythology Roadmap** topic list: Gemini (free key, default) or Claude (API key)
writes the script and checks the facts,
Gemini TTS speaks it (default `gemini-2.5-pro-preview-tts`, voice Orus, Promo/Hype, Natural pace, Neutral accent;
falls back to a Flash TTS model when Pro has no free quota), Gemini plans the boards, the pictures and the thumbnail
are made in the **Flow app** (or with the Gemini API), the app adds music, renders the MP4, writes the YouTube kit and
uploads the video to YouTube as private with `publishAt` = the publish time (default 5 pm India time).

- **Flow helper** is an accessibility service (*Settings → Accessibility → Stickman Studio Auto Pilot*). *Teach Flow*
  records once which prompt box, Generate button, picture and Download button to use. It only acts during a run or
  while teaching. The downloaded picture is read from the gallery (photos permission); if that fails it is cut out of a
  screenshot.
- **Daily run**: an alarm at the start time opens the app (screen lock must be Swipe/None, phone on charge).
- **YouTube**: Google sign-in through an *Android OAuth client* (package `com.gopalkrishan.stickmanstudio` + the SHA-1
  shown in the tab) in a Google Cloud project with *YouTube Data API v3* on. Videos from an unaudited API project stay
  private until Google's YouTube API audit is approved.
- Keys (Claude, Gemini) are stored only in the app on the phone.

## Install
Every push that changes the app builds a new APK on GitHub Actions and publishes it as the
`android-latest` release:

https://github.com/gopalkrishan680-hash/GK/releases/download/android-latest/StickmanStudio.apk

Open that link on the phone, download, and tap the file to install. Android asks once to allow
installs from your browser. A newer build installs over the old one and keeps your projects.

Needs Android 10 or newer with an up-to-date **Android System WebView** (Play Store).

## Build it yourself
Android Studio: open the `stickman-android` folder and run. Or on the command line with Gradle 8.7+
and the Android SDK: `gradle -p stickman-android :app:assembleRelease`.

## Signing (so updates keep your projects)
Android only installs an update if it is signed with the same key as the installed app. No key is
stored in this repository. CI signs with:

1. **Your own key**, if the repository has the secrets `SS_KEYSTORE_BASE64` (the keystore file,
   base64-encoded) and `SS_KEYSTORE_PASSWORD` (key alias `stickman`). Add them under
   *Settings → Secrets and variables → Actions*. This is the reliable option.
2. Otherwise **a key made on the first build and kept in the Actions cache** (`stickman-signing-key-v1`). GitHub
   drops caches that are unused for 7 days; after that the next APK has a new key, Android refuses to update it and
   the SHA-1 for YouTube sign-in changes.
   Then use *Project save* to back up, uninstall, install the new APK and *Project open*.

Do not switch between the two after installing: an APK signed with the other key will not update the app.
