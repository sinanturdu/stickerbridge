# Experiments

Each experiment has a hypothesis, setup, steps, success/failure criteria and an anonymized results table.
Results are written here as text tables — **no screenshots of real chats, no real stickers, no device logs**.

| ID | Doc | Code |
|---|---|---|
| E0 | [E0-whatsapp-folder.md](E0-whatsapp-folder.md) | adb commands only |
| E1 | [E1-ime-probe.md](E1-ime-probe.md) — historical results; DM success provenance incomplete | [`experiments/e1-ime-probe/`](../../experiments/e1-ime-probe/) |
| E5 | [E5-linked-device.md](E5-linked-device.md) — EXPERIMENTAL, test account only | [`experiments/e5-linked-device/`](../../experiments/e5-linked-device/) |
| E7 | [E7-dm-gif.md](E7-dm-gif.md) — tested DM routes rejected; Story animation owner-verified | debug tools in git history (`feature/android-v0.1-a1`) |
| E8 | [E8-dm-video.md](E8-dm-video.md) — **paused**; video is not an inline-GIF solution | existing work preserved on `feature/android-dm-video` |
| E9 | [E9-gif-provider-access.md](E9-gif-provider-access.md) — evidence/source review complete; DM GIF implementation **blocked pending new concrete evidence**; Story control owner-verified | debug diagnostics in [`experiments/e1-ime-probe/`](../../experiments/e1-ime-probe/) |
| E2–E4, E6 | planned | – |

No supported route for arbitrary private inline DM GIFs was established; this is not a claim
of universal impossibility. Reopening requires a documented integration, relevant source
implementation or reproducible private-GIF success with known provenance under the
[E9 architect-review criterion](E9-gif-provider-access.md#reopening-criterion). No new experiment
is planned; MP4/static PNG do not meet the requirement, and E8 stays paused.
