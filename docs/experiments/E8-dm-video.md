# E8 — Animated stickers to Instagram DMs as a short video

Status: **diagnosis in progress**. No experiment verdict or retain/remove decision.

## Background
Instagram DMs silently drop GIFs inserted by third-party keyboards; Stories animate them (E7). So in DMs StickerBridge currently sends animated stickers as a still image.

## Hypothesis
Instagram's DM flow accepts a short MP4 shared through Android's share sheet (`ACTION_SEND`, `video/mp4`). An animated sticker converted to a looping MP4 (on white, because video has no transparency) reaches the chat and plays there, which is closer to "animated sticker in DM" than a still image.

## Setup
- Android phone with Instagram installed and logged in, a test chat
- StickerBridge from branch `feature/android-dm-video` (debug build)
- Capture only the debug-only `SB-Video` tag (commands below).

What the app does:
- Long-press an animated sticker (GIF badge) → **Send to DM as video**.
- The sticker is converted to MP4: H.264 via MediaCodec + MediaMuxer, square canvas up to 480 px, white background, source frame start times with frames skipped to cap the rate at 30 fps, looped to at least 3 s (at most 10 s), about 1.5 Mbps, no audio track. The existing optional silent AAC code remains disabled (`VideoSender.ADD_SILENT_AUDIO = false`). Audio necessity is unproven; do not change it during this baseline reproduction.
- The MP4 is shared with `ACTION_SEND` (`video/mp4`, `EXTRA_STREAM` = our FileProvider URI, read grant) to `com.instagram.android`; if Instagram is not installed, the system chooser opens.

## Samsung observations supplied by the owner

These are reported observations, not agent device tests. Exact Samsung model, Android/One UI,
Instagram version, build, test date, network state, source dimensions/timings and stage logs were
not supplied. Cases are described without sticker names or media.

| Case / check | Reported observation |
|---|---|
| A | One MP4 sent successfully. |
| B | Another MP4 showed an upload retry icon. |
| C | The separately reported failing animated sticker did not send. Failure stage unknown. |
| Playback | A successful video does not autoplay in the chat; it loops when opened. |

These observations do not establish whether B/C failed in conversion, file publication,
URI handoff, Instagram processing or upload. Looping in Instagram's opened viewer is different
from the repeated animation baked into a finite MP4; MP4 itself does not instruct the chat to
autoplay or repeat forever. The reported playback behavior does not prove defective encoding.

## Scoped code review

Reviewed E8 baseline: `42aaaca`, after `dc65ca6`, `7b478c3`, `ddcc4b6` on
`feature/android-dm-video` relative to `main`. Only the E8 changes and directly related encoder,
sharing, FileProvider, decoder/planning and test/build files were inspected.

### Confirmed code defects / gaps (not established causes of B/C)

| Evidence in the original code | Diagnosis change |
|---|---|
| Closing frame can be only 1 microsecond after the previous frame; the fps test exempted the last delta. Zero-size encoder EOS was discarded by the mux writer. | Remove the duplicate closing frame; retain all planned source start times. Write the documented empty MP4 EOS sample at the planned end time. Tighten the fps test and cover short final frames and single-frame timelines. |
| Second `renameTo` return value was ignored after deleting the destination. Success could be reported with a missing/old output. Cache validity was just nonzero size. | Checked move, demux/container validation before publication and on cache hits, checked outgoing copy. Bump video cache to v2 so pre-diagnosis files are not reused. Invalid cached output fails the attempt and is removed for the next explicit retry. |
| Muxer was created before the codec and before `try`; codec-creation failure leaked it. AAC configure/start were outside cleanup coverage. Output buffers were not released when writing failed. Failed temporary/output-copy files could remain. | Cover acquired resources and dequeued output buffers with `finally`; remove failed temporary/partial outgoing files. Keep unique outgoing files after handoff. |
| Codec input and output-EOS polling had no deadline. | Fail with a fixed TIMEOUT code after a bounded 30-second polling wait; AAC uses a no-progress deadline. This bounds polling, not blocking vendor calls such as configure/stop/release. No Samsung stall has been demonstrated. |
| Cancelling the coroutine does not interrupt synchronous encoding; another invocation could use the same cache/temp path. | Serialize prepare/cache operations and give encoder temporaries unique names. Coroutine cancellation still prevents a late share after `withContext` returns. Background work may finish before cancellation is observed. |
| `FileProvider.getUriForFile` and Intent construction preceded the share `try`. | Handle setup failures; log numeric MIME-match, own-process readability and handoff flags before launching. Never log the URI or exception text. |

### Reviewed behavior without evidence of a defect

- Plans loop source frames until at least 3 s, cap at 10 s and use strictly increasing input
  timestamps. Zero/nonpositive source delays become 10 ms. This is intentional timing policy,
  not evidence about any particular failing sticker.
- Canvas dimensions are positive, square, even and at most 480. That does not prove every
  device codec supports every small/even size. No speculative dimension/profile fallback was added.
- Sharing awaits synchronous prepare, which now requires output EOS, muxer stop/release,
  container validation, publication and a completed outgoing copy. The original normal path
  also awaited encoding; there is no demonstrated normal-path early-share defect.
- FileProvider exposes only `cache/outgoing/`, is not exported, and permits URI grants.
  ACTION_SEND includes the stream in both EXTRA_STREAM and ClipData plus a read grant.
  This is the documented grant mechanism; no extra permission or manual persistent grant was added.
  See [Intent read grants](https://developer.android.com/reference/android/content/Intent#FLAG_GRANT_READ_URI_PERMISSION).
- Outgoing files are unique and are not deleted after launch. Existing cleanup removes files
  older than 24 hours on a later start. Cache eviction/lifetime or target-side late reading has
  not been demonstrated as a cause here.

### Encoder EOS, MP4 end time and finalization

1. Feed only real planned frames, each with its source-derived presentation timestamp.
2. Queue a **zero-size encoder input EOS** at `plan.durationUs`; drain until the codec actually
   returns **output EOS**. An input EOS submission alone is not completion. Always release each
   dequeued output buffer, including on errors. Require a started muxer and nonempty video track.
3. After output EOS, pass an **additional empty sample to MediaMuxer** with END_OF_STREAM and
   timestamp `plan.durationUs`. This sets the final real sample's duration to the remaining
   interval. It is container timing metadata, not another encoded video frame and not an instruction
   to loop. Merely queueing EOS into MediaCodec does not set this muxer timestamp.
4. Call `muxer.stop()` to finalize the MP4, then `muxer.release()`. Only after those return,
   inspect the container and move the temporary file into the cache. Then finish/close the
   unique outgoing copy before URI setup and ACTION_SEND.

[MediaMuxer.writeSampleData](https://developer.android.com/reference/android/media/MediaMuxer#writeSampleData(int,%20java.nio.ByteBuffer,%20android.media.MediaCodec.BufferInfo))
documents the empty EOS timestamp and the alternative default last-sample duration. The former
duplicate closing-frame scheme could make the last real interval 1 microsecond, and the default
muxer duration then inferred from that interval did not reliably express the intended end time.
Container validation checks readable AVC track metadata, dimensions, positive duration and sample
timestamps/count. It does not decode payloads or prove Instagram compatibility or delivery.

## Diagnostics and remaining unknowns

Original SB-Video emitted only a post-prepare/share summary or a coarse exception class. It could
not distinguish an EOS stall, muxer stop/release failure, failed move or FileProvider setup failure.
It also had no delivery callback. New diagnostics use the existing debuggable-build guard, fixed
stage/error codes and numeric metadata only. No sticker names, paths, URIs, identifiers, typed
text, exception messages/stack traces or media bytes are emitted or copied into debug artifacts.

Expected cold path: SOURCE → CACHE(hit=0) → DECODE → PLAN → AUDIO → CONFIGURE → FEED / MUX_START
→ INPUT_EOS(queued=1) → OUTPUT_EOS → MUX_STOP(stopped=1) → RELEASE → VALIDATE(valid=1)
→ PUBLISH → COPY(complete=1) → URI → HANDOFF(launched=1). A warm path has CACHE(hit=1)
→ VALIDATE → COPY → URI → HANDOFF. CHOOSER is the fallback; CANCELLED prevents sharing.
MUX_WRITE identifies a muxer sample-write failure. Progress is logged every 30 input frames.

Error codes: CODEC (plus numeric `codec_code`), TIMEOUT, OOM, SECURITY, IO, ARGUMENT, STATE, OTHER.
Cleanup failures have a fixed CLEANUP/RELEASE code. Stage-entry lines are not completion markers:
`queued=1`, OUTPUT_EOS, `stopped=1`, the next VALIDATE, `valid=1`, `complete=1` and `launched=1`
separate completion milestones. Own-process `readable=1` does not prove Instagram read the URI;
HANDOFF/CHOOSER `launched=1` means startActivity returned, not that upload succeeded.

Architect needs the following sanitized fields from one attempt at a time:

- Last stage and any error/codec_code; CACHE hit; source_bytes.
- PLAN source_width/source_height, source_frames, video_frames, loops, duration_us, side, audio.
- FEED fed/pts_us, first input image y/u/v row and pixel strides; MUX_START width/height/profile/level.
- INPUT_EOS queued; OUTPUT_EOS samples/first_pts_us/last_pts_us; MUX_STOP eos/stopped/duration_us.
- VALIDATE bytes/valid/width/height/duration_us/samples/first_pts_us/last_pts_us/tracks.
- COPY source_bytes/complete/bytes/millis; URI mime_match/readable; HANDOFF read_grant/clip_items/launched,
  or CHOOSER/CANCELLED/error.

Also provide Samsung model, Android/One UI and Instagram versions, tested commit, whether the run
was cold/warm, and manual observations of failure/arrival/autoplay/viewer looping, without account,
chat, sticker or device identifiers. No supplied logs yet establish silent-audio requirements,
codec/profile/size incompatibility, target-side URI reads, corrupt payloads, upload/network or
Instagram-side processing failures. These remain hypotheses; no corresponding speculative fix was made.

## Exact owner build and Samsung reproduction

From the repository root in PowerShell, using the existing installations (adjust JAVA_HOME only
if the installed JDK 17 lives elsewhere). These set process-local variables and do not create
`local.properties` or install/upgrade anything:

```powershell
$env:JAVA_HOME = Join-Path ${env:ProgramFiles} 'Java\jdk-17.0.19'
$env:ANDROID_HOME = Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk'
Test-Path "$env:ANDROID_HOME\platforms\android-35\android.jar"
Set-Location android
.\gradlew.bat assembleDebug --offline --console=plain
.\gradlew.bat testDebugUnitTest --offline --console=plain
```

1. After a successful build, connect the Samsung with USB debugging enabled and install the
   debug APK from `android/` using the existing adb:

   ```powershell
   $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
   & $adb install -r '.\app\build\outputs\apk\debug\app-debug.apk'
   & $adb logcat -v raw -T 1 -s 'SB-Video:D' '*:S'
   ```

   Keep capture outside the repository. Raw formatting omits logcat process/thread identifiers.
   Share only the fixed stage/error codes and numeric metadata described above. Stop with Ctrl+C.
2. Keep silent audio disabled. Open the same test DM, focus the message field and switch to
   StickerBridge. No typed-text inspection is needed. Run one attempt at a time.
3. For case A (previously successful), long-press the same animated tile → **Send to DM as video**.
   Do not press another tile while preparing. Note the picker/composer shown and whether a DM
   target is available. Select the test chat and send once.
4. Observe arrival or retry icon and roughly how long it takes. Wait for the outcome before
   another attempt. In the received chat observe autoplay; then open the video and observe viewer
   looping separately. Do not export chat screenshots or the media.
5. Repeat exactly for B (retry-icon case) and C (separately reported failing sticker), keeping
   the same recipient/network/app versions. Associate each isolated trace with A/B/C in manual
   notes only; do not put names/identifiers into logging. Repeat each once to compare CACHE hit=0
   with hit=1 where available. Cache v2 makes the first preparation after this patch fresh.
6. If needed, separately try short (<1 s), long (>3 s) and many-frame synthetic animations.
   Record Android/iOS/Web recipient behavior only when actually observed; do not infer it.

## Validation of this diagnosis package

- Wrapper remains 8.11.1; AGP remains 8.7.3. No dependency/permission changes.
- Ten VideoPlanning tests actually passed using the already cached Kotlin 2.0.21 compiler and
  JUnit 4.13.2 on JDK 17, outside Gradle, before the full Android build.
- Added and executed: three VideoFiles publication tests using only generated synthetic byte
  arrays/temporary files, and one VideoDiagnostics test verifying exception messages/causes are
  excluded. The ten VideoPlanning tests also ran in the full Gradle suite.
- `assembleDebug --offline --console=plain`: PASSED on JDK 17, using the existing SDK, including
  native builds for arm64-v8a, armeabi-v7a and x86_64.
- `testDebugUnitTest --offline --console=plain`: PASSED, 100 tests, zero failures/errors/skips.
  These are JVM tests; they do not exercise
  Android MediaCodec/MediaMuxer/MediaExtractor, FileProvider or an Instagram upload on a device.
- Earlier attempts were blocked by sandbox access to the Gradle cache and then missing SDK
  configuration. The explicit expected SDK path and Android 35 platform were subsequently
  verified accessible; process-local ANDROID_HOME resolved configuration. Nothing was installed
  or upgraded, and no local.properties was created.
- No agent device test, Instagram upload verification or experiment verdict.

## Success / failure
- **Success:** the video reaches the DM in a few taps, autoplays and loops in the chat.
- **Partial:** it reaches the DM but does not autoplay or loop, or needs many taps.
- **Failure:** Instagram offers no DM target for shared videos, or rejects the file → DMs keep the still image.

## Remaining device results
| Check | Result |
|---|---|
| Instagram opens a share sheet / target picker | |
| Direct / Chats can be chosen as target | |
| Video is sent to the chat | Mixed reported results; see A/B/C above. |
| Autoplays in the chat | Reported no for a successful video. |
| Loops in the chat | Not established; reported looping when opened. |
| Quality (sharpness, colors, white background) | |
| Size (KB) and conversion time (ms), from SB-Video | |
| Needed the silent audio track? | |
| Recipient view (Android / iOS / Web) | |

Device: … · Android: … · Instagram version: … · Date: …
