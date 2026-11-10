# E9 — Synthetic GIF provider access from the E1 IME

Status: evidence record finalized for architect review: owner-confirmed Story control and DM rejection.
Private inline GIF delivery in DMs remains unresolved. No re-encoding or delivery variant is justified.
E8 remains paused; MP4 and static PNG do not satisfy the owner's inline-animation requirement.

## Current owner evidence

The following is a sanitized summary supplied by the owner, not a new agent device test.
No screenshot, personal media or raw device log is embedded or stored with this review.

| Evidence | What is confirmed / missing |
|---|---|
| Screenshot context reported by owner | Attempt 15 is Instagram DM; package `com.instagram.android`, fieldId `2131441646`. |
| DM UI | Instagram displays its prompt to use the sticker icon to find/send GIFs; the owner reports rejection. |
| Attempts 1-15, shared capture markers | FILE_READY size 52,937; WATCH_STARTED; COMMIT_RESULT value 1. |
| Prior DM capture, attempt 15 | No GET_TYPE, QUERY, OPEN or FS_ACCESS events captured. This is absence in capture, not proven absence of access or attempts. |
| Attempts 1-14 contexts | Unclassified. Do not assign them to DM or Story. |
| Attempts 17-19 | Preparation/commit markers only. Contexts and outcomes are unclassified; do not assign them to DM or Story or infer success/rejection. |
| Story control, attempt 20 | FILE_READY size 52,937; COMMIT_RESULT value 1; OPEN_CALL, OPEN_RETURN and FS_ACCESS captured. Owner confirms the Story workflow works. |
| Story UI, attempt 20 | Owner-supplied screenshot shows the synthetic numbered test GIF in the Story editor. The screenshot is not embedded; no published Story/recipient outcome is claimed. |
| Runtime provenance | Exact installed APK hash/manifest, current Android/Instagram versions and completeness of the capture are not established by these markers. |

COMMIT_RESULT=1 acknowledges the insertion request, not delivery. FILE_READY's length is not
a content hash. WATCH_STARTED shows that startWatching returned, not that a working watch or
any data read was demonstrated. The DM screenshot confirms the target field for attempt 15;
it does not independently establish provider registration or the actual outgoing URI. The
separate attempt-20 OPEN/FS_ACCESS evidence supplies a positive access control for Story.

### Controlled comparison and conclusion

| Confirmed workflow | API acknowledgement | Provider/read-access capture | Owner-observed outcome |
|---|---|---|---|
| Instagram DM, attempt 15 | COMMIT_RESULT=1 | No captured provider/read events | Rejection toast; not delivered as the required inline GIF. |
| Instagram Story, attempt 20 | COMMIT_RESULT=1 | OPEN_CALL, OPEN_RETURN, FS_ACCESS | Owner confirms workflow works; numbered synthetic GIF visible in Story editor. |

The harness can observe file opening and filesystem read-access notifications in the Story
workflow. The confirmed DM capture differs. This is positive evidence of observability for
those Story events, not proof of complete coverage of all access paths or of the DM window.
It does not establish reader identity, complete reads, the rejection cause or an allowlist.
Provider telemetry does not itself prove rendering or message delivery; the Story UI observation
and owner confirmation are separate evidence. Missing DM callbacks still do not prove that
Instagram never accessed or attempted to access the content.

No GIF re-encoding, URI-policy change, delivery variant or further experiment is justified by
this evidence record. E8 stays paused. Arbitrary private GIFs playing inline in Instagram DMs
remain unresolved. Historical E1 success is preserved as a reported observation with incomplete
provenance, not promoted into a current support guarantee.

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

## Recorded E9 protocol (reference only; no new run requested)

The confirmed comparison is now recorded above. The original two-workflow protocol and
commands below are retained for traceability, not as a request to repeat or extend testing.

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
The agent did not run device automation. Owner-supplied results are recorded separately above.

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
- The agent did not perform a device test. The owner subsequently supplied the confirmed
  DM attempt 15 and positive Story attempt 20 recorded above; these are owner evidence, not
  agent-executed tests. No code rebuild or unchanged unit suite was repeated to finalize the record.

Full diff and the built APK path are returned separately for architect review. No APK, logs,
local.properties, generated build outputs or personal data is committed.

## E1 provenance and E9 instrumentation review

This review changes documentation only. Reviewed E9 source: `62d5fce`; E8 `e1842e2` remains
its ancestor and is unchanged/paused. The owner's current DM evidence does not retroactively
identify the historical successful build or establish a cause of rejection.

### Historical fixture and implementation

- `6ef3d4b` introduced `experiments/e1-ime-probe/app/src/main/res/raw/test_gif.gif` and its
  synthetic generator. Git blob: `b29df9084ce61cd9f69261749e7d7564ea7d0f92`.
- `298c91b` introduced `ProbeImeService.commitAsset` / `ensureAssetFile`. The GIF button selects
  `R.raw.test_gif`, with the fixed synthetic basename and `image/gif`. The source has no
  intervening insertion changes at `45a1b66`, which recorded the November 9 E1 success.
- Fixture bytes at `6ef3d4b`, `298c91b`, `45a1b66`, `62d5fce` and in the existing local E9 APK
  all match the SHA-256 above: 52,937 bytes, 512x512, four frames at 400 ms each.
- This identifies the repository fixture/source associated with the report, **not the exact
  successful device artifact**. No historical APK hash, installed source commit or cached-output
  hash was recorded. Exact Instagram/Android versions and independent recipient confirmation
  are missing. E1's source reused any existing nonempty cache file without comparing its hash.
- E1 and E9 both retain versionCode 1 / versionName `0.1-e1`; that label alone cannot identify
  which implementation was installed. No archived successful APK was established by this review.

### Delivery comparison: same repository bytes, changed URI/preparation

These are source-derived structures for the synthetic fixture, not captured device URI strings.

| Aspect | Historical E1 (`298c91b`, unchanged at results commit) | E9 (`62d5fce`) |
|---|---|---|
| Payload | Bundled synthetic GIF, copied only when cache file is absent/empty | Same bundled bytes copied afresh for every attempt |
| Cache-relative structure | `stickers/test_gif.gif` | `stickers/e9_<attempt>/test_gif.gif` |
| URI path under the same probe authority | `/stickers/test_gif.gif`, reused | `/stickers/e9_<attempt>/test_gif.gif`, fresh per attempt |
| Provider | androidx FileProvider | Debug subclass delegates to the same FileProvider implementation |
| Authority / provider root | Probe applicationId + `.fileprovider`; cache-path name `stickers`, path `stickers/` | Unchanged |
| MIME / description label | `image/gif` / `GIF` | Unchanged |
| Flags / linkUri / opts | INPUT_CONTENT_GRANT_READ_URI_PERMISSION / null / null | Unchanged |
| Input connection | Current InputConnection and EditorInfo, passed to InputConnectionCompat.commitContent | Same API and arguments, after preparation |
| Compatibility path | androidx.core 1.15.0; API 25+ platform commitContent path | Same declared library/version and path; both projects require API 26+ |
| Advertised MIME check | Used for reporting, not a gate before commit | No new gate |
| Lifetime | Cache file retained; no explicit grant revocation or post-send deletion | Same lifetime policy; previously issued attempt files are not overwritten |
| Work before commit | Copy on cache miss; legacy field/commit logging | Persistent attempt allocation, fresh copy, passive ACCESS watch; legacy debug logging suppressed |
| Build settings | min/target 26/35, core 1.15.0, appcompat 1.7.0, AGP 8.7.3, Kotlin 2.0.21 | Same declarations; debug provider selection added |

The InputConnectionCompat 1.15.0 source directly invokes platform commitContent on all API
levels supported here; no alternate pre-25 private-command path is selected by E9. Equal
payload hashes therefore do not make this an identical delivery replay: URI reuse/depth,
provider subclass and pre-commit work changed. No supplied observation implicates any one
of them. Unchanged dependency declarations are not a recovered historical resolved APK.

### Can the current instrumentation observe provider access?

| Audit item | Result and limit |
|---|---|
| Local merged debug manifest | Selects GifDiagnosticFileProvider, matching probe authority; debuggable=true, exported=false, grantUriPermissions=true. |
| Existing local E9 APK | Binary manifest inspected with installed aapt2; also selects that provider. Its class is present in DEX. APK SHA-256: `d3e0cf8de3ec117ceb077cd8a45a2cc3092a5705e891ee813dc49573ef33eaec`. This is a local artifact, not a verification of the installed device artifact. |
| URI classification | FileProvider's root/tag construction produces three decoded segments: `stickers`, canonical `e9_<positive integer>`, fixed GIF basename. The sender-generated source path matches all classifier predicates for attempts 1-15. No static mismatch found; the agent executed no runtime URI round-trip. Owner attempt 20 subsequently supplies positive Story access evidence. Other shapes are deliberately silent. |
| Overrides | javap confirms compiled getType(Uri), five-argument query and openFile(Uri,String) overrides. Logging occurs before delegation for a matching route with attached debug context. |
| Normal overload dispatch | Framework Bundle/cancellation queries delegate to the five-argument query. Cancellation/asset-file opens delegate to openFile; normal typed opens use getType/openAssetFile. These paths should reach the overrides once dispatched; transport permission checks may reject earlier. This is source/API reasoning, not an OEM runtime test. |
| **Anonymous MIME blind spot** | The actual cached core-1.15.0 FileProvider source implements getTypeAnonymous(Uri) returning application/octet-stream. E9 does not override it. On applicable Android versions, anonymous MIME lookup can therefore produce no GET_TYPE log. That inherited behavior is present in E1's same dependency too; no evidence shows it was used in either device result. |
| Other entry points | getStreamTypes/canonicalization and transport-level rejection are not separate E9 events. Do not read the event list as coverage of every provider interaction. |
| Log whitelist | The documented tag/priority/raw-format command and regex include all 19 emitted codes. Generated-schema checks accepted every code at attempt 15 with values -1, 0, 1 and 52,937. They did not use device logs. An owner-side different command, missing capture window or dropped lines is not excluded. |
| FS_ACCESS | Separate passive notification; WATCH_STARTED is not a health check. No notification cannot exclude reads, especially mmap, event loss or process/watch lifetime limits. |

Provider source checked: the existing cached AndroidX core 1.15.0 sources.jar entries for
FileProvider and InputConnectionCompat. Public API/source references for the dispatch audit:

- [FileProvider.getTypeAnonymous](https://developer.android.com/reference/androidx/core/content/FileProvider#getTypeAnonymous(android.net.Uri))
- [ContentProvider.getTypeAnonymous](https://developer.android.com/reference/android/content/ContentProvider#getTypeAnonymous(android.net.Uri))
- [Framework ContentProvider source](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/content/ContentProvider.java)

The initial source review established viable paths to observe normal provider calls. The
owner's subsequent attempt 20 now provides a **positive Story control for OPEN_CALL,
OPEN_RETURN and FS_ACCESS**. It does not validate every metadata path, eliminate the anonymous
MIME blind spot, or establish complete capture of the DM attempt. Missing DM callbacks do not
prove that Instagram never accessed or attempted to access the content. Caller identity, full
reads, rendering, delivery and an allowlist remain unproved by provider telemetry; the owner
confirmation and screenshot of the Story editor are separate UI evidence.

### Review disposition after the Story control

The previously missing Story control is supplied by attempt 20. The observed Story access and
the confirmed DM rejection/capture differ; the cause remains unknown. Attempts 1-14 and 17-19
remain unclassified. No context or outcome is inferred for them, and no new experiment is started.

The fixed-versus-fresh URI difference is concrete but has no causal evidence yet; it does not
justify changing URI policy now. The anonymous MIME gap is an instrumentation-coverage finding,
not evidence to alter a MIME return value. The evidence does not justify re-encoding the GIF
or changing delivery. No encoding, delivery, MIME, grant, label, URL, clipboard or product change
is implemented. E8 stays paused; private inline GIF delivery in DMs remains unresolved.

No build or unit suite was repeated: implementation is unchanged. Review checks were Git/blob
hash comparisons, local APK/manifest inspection, compiled signatures, primary-source dispatch
inspection and the generated-schema log-filter check. Finalization adds only the owner's
sanitized evidence summary; no screenshots, media or raw device logs are included.
