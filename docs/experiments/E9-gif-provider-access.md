# E9 — Synthetic GIF provider access from the E1 IME

Status: prepared for architect review before owner testing. No E9 device result yet.
E8 remains paused; MP4 and static PNG do not satisfy the owner's inline-animation requirement.

## Question and evidence boundary

Does the controlled Instagram DM or Story insertion cause MIME queries, metadata queries or
file opens for the synthetic GIF supplied by our IME? Can actual byte reads be observed while
preserving the existing FileProvider descriptor behavior?

`OPEN_CALL` means the provider method was entered; `OPEN_RETURN` means its original delegate
returned a descriptor. Neither establishes that any bytes were read, decoded or rendered.
A separate passive FileObserver ACCESS watch reports `FS_ACCESS` if Android notifies us that
the synthetic file's data was accessed for reading. It does not identify the reader, byte count,
offsets, number of read calls or completion. There is no byte-read count; unknown is not zero.

FileProvider's documented `openFile` returns a ParcelFileDescriptor for the regular file. Once
handed off, the receiver can read/seek/map the descriptor without another provider method call.
An OnCloseListener observes closure, not byte reads. The passive filesystem watch leaves the
descriptor unchanged. A pipe or proxy descriptor could change descriptor behavior, seekability,
timing or compatibility, so neither is introduced here. All instrumentation adds some overhead.

Primary references:

- [FileProvider API](https://developer.android.com/reference/androidx/core/content/FileProvider#openFile(android.net.Uri,java.lang.String))
- [ParcelFileDescriptor API](https://developer.android.com/reference/android/os/ParcelFileDescriptor)
- [ProxyFileDescriptorCallback API](https://developer.android.com/reference/android/os/ProxyFileDescriptorCallback#onRead(long,int,byte[]))
- [FileObserver ACCESS](https://developer.android.com/reference/android/os/FileObserver#ACCESS)
- [inotify limitations](https://man7.org/linux/man-pages/man7/inotify.7.html)
- [InputConnection.commitContent](https://developer.android.com/reference/android/view/inputmethod/InputConnection#commitContent(android.view.inputmethod.InputContentInfo,int,android.os.Bundle))

`commitContent=true` accepts a request that may still be handled in the background. It is not a
delivery receipt. Provider access cannot establish rendering, message delivery or an allowlist.
Caller identities are deliberately neither inspected nor logged: access is correlated with the
controlled Instagram workflow, not independently attributed to a particular caller/process.

## Prior observations and the one uncontrolled variable addressed

- E1 reported its synthetic GIF sent and animated in a DM; that Instagram version was not recorded.
- E7 recorded rejection on Instagram 448.0.0.52.84 despite `commitContent=true`, including the
  synthetic E1 control. Size, label, MIME, linkUri, Gallery and sharing variants already failed.
- Samsung Keyboard's successful reference came from built-in catalog search/results. Exact
  item/provider was unrecorded; importing a private local GIF was never tested.
- The owner opened the synthetic GIF in Gallery but could not complete the proposed Samsung
  sticker-creation flow. **No Instagram delivery test occurred through that flow.**

E9 does not repeat those variant sweeps. It observes the previously unmeasured provider access
stage, with a fresh outgoing URI per insertion to avoid reusing the same URI as a cache key.
This does not eliminate Instagram's byte/content-level caching or other internal decisions.

## Implementation scope and preserved delivery behavior

All implementation is inside `experiments/e1-ime-probe`, based on repository commit `e1842e2`.
The product Android app and E8 files are unchanged. Existing uncommitted E1 diagnostic work was
reviewed and completed in place, without switching branches or discarding it.

- A debug-source-set subclass overrides getType, query and openFile, delegating unchanged
  arguments to FileProvider. It returns the same MIME, cursor and descriptor and rethrows the
  same exceptions. No caller identity or query arguments/results are inspected or logged.
- A build-type manifest placeholder selects that subclass only for debug; release selects
  the original androidx.core.content.FileProvider. Authority, exported=false, grantUriPermissions,
  provider root, permissions, dependencies, SDK targets, AGP and wrapper versions are unchanged.
- The GIF button in a debuggable build copies the existing bundled GIF into a new attempt
  subdirectory beneath the existing cache/provider root. The original basename and extension
  remain. Parent creation works on first use; an existing attempt directory is never reused.
- An app-private persistent synthetic integer advances before preparation. Process restarts
  do not reuse an attempt number/URI while app data is retained. No files are removed or grants
  revoked after insertion. Do not clear app data or uninstall during the two-attempt experiment.
- An ACCESS-only FileObserver starts after the copied output is closed and before commit.
  The probe never reads that outgoing copy itself. Watch objects remain referenced through the
  session; they disappear on process death and are not reconstructed for previous attempts.
  WATCH_STARTED means startWatching returned, not proof that the OS registered a working watch.
  Watch-start exceptions emit WATCH_ERROR and do not intentionally change the commit path.
- Payload settings remain `image/gif`, description `GIF`, null linkUri, null opts and
  INPUT_CONTENT_GRANT_READ_URI_PERMISSION. No preflight resolver query/open is added by the probe.
- Legacy E1 field/exception logging is suppressed in debug, so no field hints, identities,
  exception payloads or arbitrary text enter the new capture. The field display is unchanged.
  The old launcher log/share view may retain earlier records; do not use it for E9.
- No pipe, proxy descriptor, Samsung impersonation, network access, new permission/dependency,
  personal asset, typed-text read or product change is introduced.

Synthetic fixture: existing `app/src/main/res/raw/test_gif.gif`, 52,937 bytes, 512x512,
four numbered frames at 400 ms each, generated by the committed E1 asset generator.
Do not regenerate, resize, transcode or publish it for E9.

SHA-256 of this synthetic fixture:

```text
7d195165d572c6c3619363a42477ef8fc6644c4323af842af9c7167d08fc7241
```

## Sanitized event schema

Tag: `SB-GifAccess`, debug only. Every line has exactly this shape:

```text
event=OPEN_RETURN attempt=1 count=1 value=-1
```

The example is schema illustration, not a device result. `attempt` is a synthetic sequence,
not an account/chat/device identifier. `count` is per attempt/event and resets if the probe
process restarts; attempts persist. Event order can interleave across Binder threads.

| Event | Meaning / numeric value |
|---|---|
| ATTEMPT | A new synthetic attempt was allocated. |
| FILE_READY | Copy finished and stream closed; value is copied file size. This is not a receiver byte-read count. |
| PREPARE_ERROR | Fixture preparation failed. No exception text. |
| NO_CONNECTION | No current editor/input connection; nothing committed. |
| COMMIT_CALL | About to invoke the unchanged commitContent API. |
| COMMIT_RESULT | API returned: value=1 for true, 0 for false. |
| COMMIT_ERROR | Preparation/URI/commit path threw; attempt=0 if allocation itself failed. |
| GET_TYPE_CALL / RETURN / ERROR | MIME method entered / returned / threw. |
| QUERY_CALL / RETURN / ERROR | Metadata method entered / returned / threw. Cursor contents are not logged or read by diagnostics. |
| OPEN_CALL / RETURN / ERROR | File-open method entered / descriptor returned / threw. Not a byte-read event. |
| WATCH_STARTED / WATCH_ERROR | Passive watch start returned / threw. Not a watch health check. |
| FS_ACCESS | Filesystem read-access notification for the synthetic output. Count is notification count, not bytes or read calls. |

Other values are -1 (unspecified), not zero bytes read. Counts include matching calls from any
caller; Android may perform metadata lookups and a receiver can open more than once. No required
GET_TYPE/QUERY/OPEN ordering or one-open-per-commit assumption is made. Non-E9 routes are unlogged.
ACCESS notifications may be coalesced, delayed, lost, or unavailable for some access paths
(including memory-mapped access). No FS_ACCESS does not establish that no bytes were read.
Process death ends existing watches; a later provider open does not recreate them. These limits
apply even when WATCH_STARTED was logged. A positive signal still has no caller attribution.

## Exact build and installation (PowerShell)

Use the checkout containing E9 and `e1842e2`, not the older Projects checkout. With the verified
existing installations, no local.properties, downloads or tool upgrades are needed:

```powershell
Set-Location (Join-Path $env:USERPROFILE 'OneDrive\Belgeler\ChatGPT\stickerbridge_\experiments\e1-ime-probe')
$env:JAVA_HOME = Join-Path ${env:ProgramFiles} 'Java\jdk-17.0.19'
$env:ANDROID_HOME = Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk'
.\gradlew.bat assembleDebug lintDebug testDebugUnitTest processReleaseMainManifest --offline --console=plain
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb install -r '.\app\build\outputs\apk\debug\app-debug.apk'
& $adb shell ime enable 'dev.stickerbridge.e1.imeprobe/.ProbeImeService'
```

Enable/select **IME Probe** with the system keyboard picker in each target field. The probe's
launcher can open keyboard settings. Installing this separate application does not replace the
StickerBridge product app. Do not export/share old probe logs, screenshots or app-private files.

## One controlled experiment: exactly one DM and one Story insertion

Start the following live capture **before** either insertion; keep it running through both.
Raw format omits process/thread identifiers, and the whitelist emits only E9 codes and numbers:

```powershell
$events = 'ATTEMPT|FILE_READY|PREPARE_ERROR|NO_CONNECTION|COMMIT_CALL|COMMIT_RESULT|COMMIT_ERROR|GET_TYPE_CALL|GET_TYPE_RETURN|GET_TYPE_ERROR|QUERY_CALL|QUERY_RETURN|QUERY_ERROR|OPEN_CALL|OPEN_RETURN|OPEN_ERROR|WATCH_STARTED|WATCH_ERROR|FS_ACCESS'
& $adb logcat -v raw -T 1 -s 'SB-GifAccess:D' '*:S' |
    Where-Object { $_ -cmatch "^event=($events) attempt=[0-9]+ count=[0-9]+ value=-?[0-9]+$" }
```

Ignore any historical first line supplied by `-T 1`; use only new ATTEMPT numbers allocated
after capture starts. Do not clear system logs or app data to reset the sequence.

1. Record the tested source commit, Samsung model, Android/One UI and Instagram versions in
   manual notes, without device/account/chat identifiers. Do not update apps mid-experiment.
2. Open the Instagram test DM and focus its ordinary message composer. Switch to IME Probe.
   Tap **GIF exactly once**. Map the displayed attempt number to DM in manual notes only.
   Observe the API result separately from any toast and whether the message actually appears
   and animates inline for sender/recipient. Do not type diagnostic text or tap other asset buttons.
3. Wait 30 seconds after the tap with capture running. Treat this as a bounded observation
   window, not proof that no later access is possible. Do not send/share the served URI elsewhere.
4. Open an Instagram Story using a blank/synthetic background, activate the text tool and
   switch to IME Probe. Tap **GIF exactly once**. Map this new attempt number to Story. Record
   whether a movable animated sticker layer appears. Do not publish the Story for E9.
5. Wait another 30 seconds, stop capture with Ctrl+C, then discard the draft if desired. Return
   only sanitized lines plus the two attempt mappings and manual UI observations to the architect.
   Keep any capture outside the repository. No Gallery, size, label, MIME, URL or clipboard sweep.

Stop after these two insertions. If preparation/connection/commit fails, stop and report the
fixed codes; do not silently substitute another asset/settings or start additional variants.
No owner test or device automation is run by this package before architect review.

## Interpretation matrix

| Observed result | Establishes | Does not establish |
|---|---|---|
| No ATTEMPT/FILE_READY or setup error | Probe preparation/capture needs investigation; no valid access comparison. | Instagram rejection, permission failure or an allowlist. |
| COMMIT_RESULT value=0, or COMMIT_ERROR | No successful commit acknowledgement for that attempt. | Which receiver stage failed; messages may be processed asynchronously. |
| COMMIT_RESULT value=1, no provider events | No instrumented provider method observed in the captured window. | That no call was attempted: framework permission checks can fail before provider entry, capture can be incomplete, or access may occur later. |
| GET_TYPE/QUERY only | Metadata path was used for that attempt. | File open, any byte read, decode, render or delivery. |
| OPEN_CALL then OPEN_ERROR | The delegated open failed inside the provider. | Exception cause/category (not logged), or failures blocked before provider entry. |
| OPEN_CALL with no RETURN/ERROR | Only method entry observed. | A completed open; missing tail/capture loss must be considered. |
| OPEN_RETURN | FileProvider returned a descriptor for the attempt. | Actual byte reads, read completion, valid decoding or delivery. |
| FS_ACCESS | Android reported data-read access to that synthetic output. | That Instagram was the reader, how much was read, a complete read, decoding, rendering or delivery. |
| OPEN_RETURN without FS_ACCESS | An open completed, with no observed read notification. | That no reads occurred; watch failure/lifetime, event loss and unobserved access paths remain possible. |
| DM metadata-only, Story OPEN_RETURN | The two controlled paths differ in observed access. | An allowlist, exact caller identity, or the reason for the difference. |
| OPEN_RETURN in both, DM rejects and Story animates | Both paths reached file-open return; UI outcomes differ after/around that point. | That both read identical bytes, or a specific policy/codec/allowlist cause. |
| FS_ACCESS in both, DM rejects and Story animates | Read-access notifications occurred for both synthetic outputs; visible outcomes differ. | Exact reader identity, complete reads, successful decoding or a specific rejection cause. |
| Manual inline animation or Story layer observed | That particular visible outcome on the tested configuration. | Unchanged GIF transport bytes, all-device support, or a general third-party integration contract. |

Exact byte counts, completeness and reader identity remain **unobserved** under the
unchanged-descriptor design. More instrumentation that replaces the descriptor would be a
separate experiment requiring architect review, not an automatic E9 follow-up.

## Build and review record

- `assembleDebug --offline --console=plain`: PASSED on existing JDK 17 / SDK 35, including a
  rebuild after adding the passive ACCESS signal.
- `lintDebug`: PASSED with zero errors and 17 warnings (application icon/data-extraction
  configuration and existing-style UI/button/string issues). No unrelated warning cleanup.
- `testDebugUnitTest`: NO-SOURCE. This E1 project has no existing unit-test sources/dependency;
  no unit tests executed and no passing suite is claimed. The product/E8 suite was not repeated.
- `processReleaseMainManifest`: PASSED. Inspection confirms original provider in release,
  diagnostic subclass/debuggable flag in debug, and unchanged authority/read-grant attributes.
- Standard-library packaging checks PASSED: fixture hash, 512x512 dimensions, four 400 ms
  frames, identical bundled APK GIF bytes, and presence of the diagnostic provider class.
- A first packaging assertion incorrectly expected zero merged uses-permission elements.
  Inspection verified the sole element is the unchanged AndroidX core app-private signature
  permission for non-exported dynamic receivers. The corrected check verifies its library
  origin, unchanged debug/release set and absence of Android system permissions/INTERNET.
- Wrapper remains 8.11.1, AGP 8.7.3 and Kotlin 2.0.21; no dependency or permission additions.
- No device test, provider callback, FS_ACCESS result, Instagram rendering or delivery result
  was observed by the agent. Runtime callbacks and watch behavior await owner testing.

Full diff and the built APK path are returned separately for architect review. No APK, logs,
local.properties, generated build outputs or personal data is committed.
