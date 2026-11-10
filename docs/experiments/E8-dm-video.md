# E8 — Animated stickers to Instagram DMs as a short video

## Background
Instagram DMs silently drop GIFs inserted by third-party keyboards; Stories animate them (E7). So in DMs StickerBridge currently sends animated stickers as a still image.

## Hypothesis
Instagram's DM flow accepts a short MP4 shared through Android's share sheet (`ACTION_SEND`, `video/mp4`). An animated sticker converted to a looping MP4 (on white, because video has no transparency) reaches the chat and plays there, which is closer to "animated sticker in DM" than a still image.

## Setup
- Android phone with Instagram installed and logged in, a test chat
- StickerBridge from branch `feature/android-dm-video` (debug build)
- `adb logcat -s SB-Video SB-Field SB-Gif`

What the app does:
- Long-press an animated sticker (GIF badge) → **Send to DM as video**.
- The sticker is converted to MP4: H.264 via MediaCodec + MediaMuxer, square canvas up to 480 px, white background, exact frame timing, at most 30 fps, looped to at least 3 s (at most 10 s), about 1.5 Mbps, no audio track. A silent AAC track can be enabled with a code flag (`VideoSender.ADD_SILENT_AUDIO`) if Instagram turns out to require audio.
- The MP4 is shared with `ACTION_SEND` (`video/mp4`, `EXTRA_STREAM` = our FileProvider URI, read grant) to `com.instagram.android`; if Instagram is not installed, the system chooser opens.

## Steps
1. In an Instagram DM, switch to StickerBridge and long-press an animated sticker → **Send to DM as video**.
2. Note what Instagram opens (share sheet, story editor, feed composer, …) and whether a DM / chat target can be chosen.
3. Send it to the test chat. Note whether it arrives, whether it autoplays, whether it loops, how it looks (quality, white background) and its size (from `SB-Video`).
4. Repeat with a short animation (< 1 s), a long one (> 3 s) and a sticker with many frames.
5. If Instagram refuses the video, rebuild with `ADD_SILENT_AUDIO = true` and repeat steps 1–3.
6. Check what the recipient sees on Android, iOS and Web.

## Success / failure
- **Success:** the video reaches the DM in a few taps, autoplays and loops in the chat.
- **Partial:** it reaches the DM but does not autoplay or loop, or needs many taps.
- **Failure:** Instagram offers no DM target for shared videos, or rejects the file → DMs keep the still image.

## Results
| Check | Result |
|---|---|
| Instagram opens a share sheet / target picker | |
| Direct / Chats can be chosen as target | |
| Video is sent to the chat | |
| Autoplays in the chat | |
| Loops in the chat | |
| Quality (sharpness, colors, white background) | |
| Size (KB) and conversion time (ms), from SB-Video | |
| Needed the silent audio track? | |
| Recipient view (Android / iOS / Web) | |

Device: … · Android: … · Instagram version: … · Date: …
