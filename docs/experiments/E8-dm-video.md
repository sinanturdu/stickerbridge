# E8 — Animated stickers to Instagram DMs as a short video

Status: **done — not shipped.** The code is kept, unmerged, on branch `feature/android-dm-video` for possible later work.

## Background
Instagram DMs silently drop GIFs inserted by third-party keyboards; Stories animate them (E7). In DMs StickerBridge therefore sends animated stickers as a still image. E8 tested whether a short video is a better DM alternative.

## Hypothesis
Instagram's DM flow accepts a short MP4 shared through Android's share sheet (`ACTION_SEND`, `video/mp4`). An animated sticker converted to a looping MP4 reaches the chat and plays there, which is closer to "animated sticker in DM" than a still image.

## What was built (branch `feature/android-dm-video`)
- Long-press an animated sticker → **Send to DM as video**.
- The animated WebP is converted to MP4: H.264 via MediaCodec + MediaMuxer (no new dependencies), square canvas up to 480 px, **white background** (video has no transparency), source frame timing capped at 30 fps, **looped to at least 3 s** (at most 10 s), about 1.5 Mbps, no audio track.
- The MP4 is shared with `ACTION_SEND` (`video/mp4`, our FileProvider URI with a read grant) to Instagram.

## Results
*Device test reported by the owner: Samsung phone, Instagram 448.0.0.52.84, 2026-11-10. Logs were not collected.*

| Check | Result |
|---|---|
| Instagram opens a share flow and the DM can be chosen | Yes |
| Video is sent to the chat | **Intermittent:** some videos are posted, others stay stuck with Instagram's retry icon. The same sticker can succeed once and fail once. Cause not determined. |
| Autoplays in the chat | **No** |
| Loops | Yes, once opened |
| Quality | Fine |

## Findings
- Sharing an MP4 is a working path into DMs in principle, but uploads fail intermittently for reasons that were not determined (no logs were collected, so conversion, hand-off and upload cannot be told apart).
- Even when it works, the result is a video message that does not autoplay — not an animated sticker in the chat.

## Decision
- **Not shipped.** DMs keep the current behavior: animated stickers are sent as a still image (E7).
- The code stays on the unmerged branch `feature/android-dm-video` for possible later work; it is not merged into `main`.
