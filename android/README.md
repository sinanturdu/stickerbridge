# StickerBridge for Android — v0.1 package A1

A sticker keyboard that shows the stickers from your WhatsApp stickers folder and inserts them into Instagram (DM and Stories) with one tap.

> **Status: A1, pre-alpha.** Debug builds only. See [../docs/ROADMAP.md](../docs/ROADMAP.md) for A2 and later.

## What A1 does
- **Onboarding app:**
  1. Enable the keyboard (opens the system keyboard settings and shows whether StickerBridge is enabled).
  2. Connect the WhatsApp stickers folder through the system folder picker (read-only, persisted). WhatsApp Business can be connected as an optional second folder. Connected folders are listed with a *Disconnect* button.
  3. Status: number of stickers, last successful scan, last scan result.
- **Library:** app-private Room database (`sticker_file`). The file time from the folder is shown as **"recently seen"**, never as "added to WhatsApp".
- **Scanner:** one folder listing per scan, incremental diff (new / changed / missing). A file is archived only after 3 consecutive *complete* scans without it; failed or partial scans never count as missing.
  - Scans run when the keyboard opens (at most once per 60 s), when you tap ↻, and when the onboarding screen opens.
- **Keyboard "StickerBridge":**
  - ◀ Keyboard (back to your normal keyboard), ↻ refresh with spinner and last sync time, 4-column grid ordered by "recently seen".
  - Tap a sticker to insert it via `commitContent` (`image/webp`). A toast appears if the app refuses it.
  - Fields that accept no images (e.g. Instagram comments) show "This field doesn't accept stickers".

## Privacy
- **No permissions:** no internet, no storage permission (folder access is a user-granted SAF folder), no accessibility, no contacts.
  The merged manifest only contains androidx's internal `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which protects the app's own receivers.
- The keyboard has no letter keys and **never reads or logs typed text**.
- Everything stays on the phone. The library database is app-private and not backed up (`allowBackup=false`).

## Build

Requirements: Android Studio (Ladybug or newer) with Android SDK Platform 35, JDK 17+.
Versions: Gradle 8.11.1 (wrapper, checksum-pinned), AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Room 2.6.1, compileSdk/targetSdk 35, minSdk 26.

### Android Studio
*File → Open…* → select the `android/` folder → wait for Gradle sync → *Run* with your phone connected.

### PowerShell
```powershell
cd android
# only if Android Studio has not created local.properties yet (it is gitignored, never commit it):
"sdk.dir=$($env:LOCALAPPDATA.Replace('\','/'))/Android/Sdk" | Out-File -Encoding ascii local.properties
.\gradlew.bat testDebugUnitTest assembleDebug
```
APK: `android\app\build\outputs\apk\debug\app-debug.apk`

## Install
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Setup on a Samsung phone (One UI)
1. Open **StickerBridge** → *Open keyboard settings*. On Samsung this is *Settings → General management → Keyboard list and default*. Turn on **StickerBridge** and accept the standard Android keyboard warning (it is shown for every keyboard).
2. In the same screen, turn on **Keyboard button on navigation bar**. A keyboard icon then appears at the bottom right whenever a keyboard is open; tap it to switch to StickerBridge. Keep Samsung Keyboard as the default keyboard.
3. Back in StickerBridge → *Connect WhatsApp stickers folder*. The picker opens at `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers`; tap **Use this folder** → **Allow**.
4. Wait for the status to show the sticker count.
5. In Instagram, open a DM, tap the text field, switch to StickerBridge with the navigation-bar keyboard button, tap a sticker. Use **◀ Keyboard** to go back.

Menu names can differ between One UI versions.

## Current limitations
- **Animated stickers are sent as static** until A2 (Instagram shows the first frame of an animated WEBP; A2 converts to GIF).
- **The folder holds stickers seen in your chats, not your WhatsApp favorites.** Favorites are only reachable through the experimental linked-device sync planned for v0.2 (see E5).
- The order is "recently seen" (file time of the folder), not WhatsApp's own order.
- In Instagram DMs, transparent stickers appear on a white card (Instagram behavior, E1). Stories keep transparency.
- No periodic background scan yet; scans happen when the keyboard or app opens or on ↻.
- The WhatsApp Business folder path is assumed (`Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Stickers`) and has not been checked on a device.

## Code layout
```
app/src/main/java/dev/stickerbridge/app/
  MainActivity.kt          onboarding
  StickerBridgeApp.kt      database + scan coordinator
  data/                    Room entity, DAO, database
  scan/                    ScanDiff (pure, unit-tested), FolderScanner (SAF), ScanCoordinator, StickerFolders
  ime/                     StickerKeyboardService, StickerAdapter, ThumbnailLoader, StickerInserter
app/src/test/              ScanDiffTest
```
