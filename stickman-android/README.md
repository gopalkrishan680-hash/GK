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
2. Otherwise **the build machine's debug key**, kept in the Actions cache. GitHub drops caches that
   are unused for 7 days; after that the next APK has a new key and Android refuses to update it.
   Then use *Project save* to back up, uninstall, install the new APK and *Project open*.

Do not switch between the two after installing: an APK signed with the other key will not update the app.
