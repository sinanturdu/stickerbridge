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
  - ↻ and connecting a folder always list every file. Opening the keyboard or the app does nothing if the last complete scan is under 5 minutes old; otherwise it first does a quick check (the folder's own time + number of files) and lists files only if something changed (a full listing still happens at least every 24 h).
  - The grid always shows the saved library immediately; "Syncing…" appears only while files are actually being listed. Unchanged files cause no database writes.
  - Debug builds log scan timings with tag `SB-Scan` and animated sends with tag `SB-Gif` (`adb logcat -s SB-Scan SB-Gif`). The last converted GIF is kept at `/sdcard/Android/data/dev.stickerbridge.app/files/debug/last.gif`.
- **Keyboard "StickerBridge":**
  - ◀ Keyboard (back to your normal keyboard), ↻ refresh with spinner and last sync time, 4-column grid.
  - **Tabs** (like WhatsApp): **Recent** (clock), **Favorites** (star), **All** (grid). The last tab you tapped is remembered. If you never tapped one: All on first use, afterwards Recent once it has stickers.
    - **Recent:** the last 32 different stickers you sent, newest first. Sending one again moves it to the top. Only successful inserts count. The history keeps at most 200 send events.
    - **Favorites:** StickerBridge's own favorites, newest first. (WhatsApp favorites are not read here; merging them is planned for A3 via the experimental linked-device source.)
    - **All:** every sticker in the connected folder(s), ordered by "recently seen".
    - Favorites show a small ★ in Recent and All.
    - Stickers that disappear from the folder (archived) or whose folder is disconnected are hidden from Recent and Favorites, but not forgotten: they come back when the file reappears or the folder is reconnected.
  - Tap a sticker to insert it via `commitContent`. A toast appears if the app refuses it.
    - **Static stickers** are sent as `image/webp`.
    - **Animated stickers** (marked with a small **GIF** badge) are converted to GIF on tap (spinner on the tile) and sent as `image/gif`, because Instagram plays GIF but freezes animated WEBP (E1). If the field does not advertise `image/gif`, or the conversion fails, the WEBP is sent instead.
  - Fields that accept no images (e.g. Instagram comments) show "This field doesn't accept stickers".
- **Animated WEBP → GIF:** decoded with Google's libwebp (v1.5.0, vendored, built with the NDK) through a small JNI bridge; encoded by our own GIF89a encoder (median-cut palette ≤ 255 colors + 1 transparent index, alpha < 50 % = transparent, LZW, delays kept with a 2 cs minimum, infinite loop, disposal "restore to background").
  - Longest side ≤ 512 px; more than 60 frames are dropped evenly with merged delays; target < 2 MB, retried at 384 and 256 px if larger.
  - GIFs are cached in the app cache, keyed by document id + last-modified time.
  - The GIF badge appears once the first 64 bytes of a file have been checked. This runs in the background after a scan (newest stickers first), so scans stay fast.
- **Getting back to the sticker keyboard:** a Quick Settings tile **StickerBridge** and a launcher shortcut **Switch to sticker keyboard** both open the system keyboard picker. The onboarding screen explains all ways to switch.

## Privacy
- **No permissions:** no internet, no storage permission (folder access is a user-granted SAF folder), no accessibility, no contacts.
  The merged manifest only contains androidx's internal `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which protects the app's own receivers.
- The keyboard has no letter keys and **never reads or logs typed text**.
- Everything stays on the phone. The library database is app-private and not backed up (`allowBackup=false`).

## Build

Requirements: Android Studio (Ladybug or newer) with Android SDK Platform 35, **NDK 27.0.12077973** and **CMake 3.22.1**, JDK 17+.
Install the NDK and CMake in Android Studio (*SDK Manager → SDK Tools*) or with:
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --install "ndk;27.0.12077973" "cmake;3.22.1"
```
Versions: Gradle 8.11.1 (wrapper, checksum-pinned), AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Room 2.6.1, compileSdk/targetSdk 35, minSdk 26.
Native ABIs: arm64-v8a, armeabi-v7a, x86_64 (emulator). Third-party code: see [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

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

## Favorites and Recent: how to use
- **Add to favorites:** long-press a sticker in any tab → **Add to favorites**. It appears at the top of Favorites.
- **Remove from favorites:** long-press → **Remove from favorites**.
- **Remove from recent:** in the Recent tab, long-press → **Remove from recent**.

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
6. Optional, to get back to StickerBridge without the navigation-bar button:
   - **Quick Settings tile:** swipe down twice → ✎ (Edit) → drag **StickerBridge** into the active tiles. While typing, swipe down and tap it → pick StickerBridge.
   - **Launcher shortcut:** long-press the StickerBridge icon → **Switch to sticker keyboard** (drag it to the home screen to keep it there).

Menu names can differ between One UI versions.

## Database upgrades
The library uses Room with explicit migrations only (no destructive fallback); the schema history is exported to `app/schemas/` and checked in.
`MigrationSqlTest` checks on the JVM that the v1 → v2 migration SQL matches Room's exported v2 schema exactly. Room's `MigrationTestHelper` would need instrumented tests and androidx.test, which are not approved dependencies.

**Manual upgrade check (v1 → v2), once per release that changes the schema:**
1. Install the previous build (schema v1), connect the folder and let it scan.
2. Install the new build over it with `adb install -r app\build\outputs\apk\debug\app-debug.apk` (do not uninstall).
3. Open the keyboard: All still shows the stickers (no re-scan from zero, count unchanged on the status screen), and Recent/Favorites work. A crash on open means the migration does not match.

## Current limitations
- **GIF quality:** GIF has 1-bit transparency and at most 255 colors per frame, so soft edges can show a slight halo and gradients can band.
- Lottie stickers are not handled (E0 found only `.webp` files in the stickers folder).
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
  data/                    Room entities, DAOs, database + Migrations, StickerLibrary (Recent/Favorites), TabLogic (pure)
  scan/                    ScanDiff (pure, unit-tested), FolderScanner (SAF), ScanCoordinator, StickerFolders
  ime/                     StickerKeyboardService, StickerAdapter, ThumbnailLoader, StickerInserter
  convert/                 WebpAnimDecoder (JNI), GifEncoder + ColorQuantizer + Lzw, GifPlanning, ArgbScaler, WebpHeader
  switcher/                KeyboardTileService (Quick Settings), KeyboardPickerActivity
app/src/main/cpp/          CMake build, JNI bridge, third_party/libwebp (vendored v1.5.0 subset)
app/schemas/               exported Room schemas (checked in)
app/src/test/              ScanDiffTest, GifEncoderTest, GifPlanningTest, WebpHeaderTest, TabLogicTest, MigrationSqlTest
```
