# StickerBridge

**Use your own WhatsApp stickers inside Instagram — with a sticker keyboard that keeps itself in sync.**

> 🇹🇷 Türkçe özet: [README.tr.md](README.tr.md)

StickerBridge is an open-source sticker keyboard for Android (first) and iOS (later).
It has a customizable sticker key: tap it inside Instagram and your WhatsApp stickers appear, ready to send in DMs or place on Stories.
New stickers show up automatically after a short delay, and there is always a manual ↻ refresh button.

> **Status: research & experiment phase (pre-alpha).** Nothing is installable yet.
> We are first proving the critical technical assumptions with small experiments before building the product.
> See [docs/ROADMAP.md](docs/ROADMAP.md).

---

## Why this project?

WhatsApp and Instagram are both owned by Meta, but there is **no built-in way** to use your WhatsApp sticker collection in Instagram.
Existing workarounds are one-time exports: send stickers to yourself, export a chat, upload files to a website.
StickerBridge aims for something different: **a synced library** that follows your WhatsApp collection.

## How it is supposed to work

```
WhatsApp sticker sources ──► StickerBridge library (on your phone) ──► StickerBridge keyboard ──► Instagram DM / Story
   • sticker folder (Android, official storage access)
   • linked-device sync (optional, experimental)
   • manual add / share menu
```

1. Install the StickerBridge keyboard and grant access to WhatsApp's sticker folder once.
2. Open Instagram, tap the text field, switch to StickerBridge with the 🌐 key.
3. Tap the sticker key → your stickers, grouped and ordered → tap one to send.

## Honest limitations (read this first)

We publish what is verified and what is not. Details and sources: [docs/RESEARCH.md](docs/RESEARCH.md).

| Topic | Reality |
|---|---|
| Official WhatsApp API to read your sticker collection | **Does not exist.** |
| Favorites & their order | Stored privately inside WhatsApp. Only reachable through an *unofficial* linked-device method (optional, off by default, account-risk warning). |
| Android sticker folder | Readable with your permission, but probably contains only stickers seen in chats — not a full mirror of favorites/packs. *(Experiment E0)* |
| iOS | No app can read WhatsApp's data on iOS. iOS support = keyboard + manual import + copy/paste. |
| Instagram's built-in sticker tray | Cannot be extended by third parties. StickerBridge is a **custom keyboard**, not a tray add-on. |
| Adding a key to Gboard / Samsung Keyboard | Not possible. The sticker key lives in the StickerBridge keyboard. |
| Transparency & animation in Instagram | Tested on one Android device: DMs show stickers on a white card (no transparency); Stories keep transparency. GIF animates in both; animated WEBP stays static, so animated stickers are converted to GIF. iOS/Web recipient view not yet checked. *(E1; E2–E3 pending)* |

## Privacy principles

- Everything runs **on your device**. No StickerBridge server, no account, no analytics.
- We **never** ask for your WhatsApp or Instagram password.
- The keyboard **never** logs or uploads what you type.
- Open source ≠ public stickers: your stickers stay on your phone.

More: [docs/PRIVACY.md](docs/PRIVACY.md)

## Platform plan

| Platform | Plan | Sync level |
|---|---|---|
| Android | v0.1 target | Folder scan (auto) + optional linked-device sync + manual add |
| iOS | Later | Manual import, copy/paste flow |
| Desktop (Windows/macOS) | Much later | Global hotkey opens the sticker library |

## Repository layout

```
stickerbridge/
├── android/        Android keyboard app (not started)
├── ios/            iOS keyboard (later)
├── desktop/        Desktop hotkey helper (much later)
├── experiments/    Small, throw-away technical experiments (E0, E1, …)
├── docs/           Research, architecture, roadmap, privacy, experiment results
└── .github/        Issue & PR templates
```

## Experiments

| ID | Question | Status |
|---|---|---|
| E0 | What is really inside WhatsApp's Android sticker folder? | **Next** |
| E1 | Which image types do Instagram's input fields accept from a keyboard? | Done — DM and Stories accept PNG/WEBP/GIF via `commitContent`; comments accept none |
| E2 | How does each format look to the recipient (transparency, animation)? | Planned |
| E3 | Does Instagram "Share to Stories" keep a transparent sticker? | Planned |
| E4 | iOS copy/paste behavior in DM and Stories | Planned |
| E5 | Can favorites be read via linked-device sync (test account only)? | Planned |
| E6 | What does WhatsApp Web store about favorite stickers? | Planned |

Full plans: [docs/experiments/](docs/experiments/)

## Contributing

Contributions are welcome, especially **experiment results on different phones** (Android version, OEM, Instagram version).
Please read [CONTRIBUTING.md](CONTRIBUTING.md) first. Never attach real stickers, screenshots of private chats or device logs.

## Disclaimer

StickerBridge is an independent project and is **not affiliated with, endorsed by, or sponsored by Meta, WhatsApp or Instagram**.
WhatsApp and Instagram are trademarks of Meta Platforms, Inc. Optional experimental features may conflict with WhatsApp's Terms of Service; they are off by default and clearly marked.

## License

[MIT](LICENSE)
