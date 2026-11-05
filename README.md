<div align="center">

# 📺 AirMirror TV

**Turn any Android TV or TV box into an AirPlay receiver.**
Mirror your iPhone, iPad or Mac screen with sound. Nothing to install on the Apple device.

![Version](https://img.shields.io/badge/version-1.17-0A84FF)
![Android 5.0+](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)
![AirPlay](https://img.shields.io/badge/AirPlay-Mirroring%20%7C%20Video%20%7C%20Audio-000000?logo=apple&logoColor=white)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)

</div>

<p align="center"><img src="docs/screenshot.png" alt="AirMirror TV home screen" width="800"></p>

---

## Contents

- [Features](#-features)
- [Quick start](#-quick-start)
- [Using the app](#-using-the-app)
- [Settings](#%EF%B8%8F-settings)
- [Troubleshooting](#-troubleshooting)
- [Build from source](#%EF%B8%8F-build-from-source)
- [How it works](#-how-it-works)
- [Changelog](#-changelog)
- [License](#-license)

## ✨ Features

| | |
|---|---|
| 🖥️ **Screen mirroring** | Full-screen, hardware H.264 decoding, letterboxed to fit, with manual rotation |
| 🎬 **Video casting** | The AirPlay button in Safari, YouTube and other apps sends just the video |
| 🔊 **Sound** | AAC-ELD, AAC and ALAC (decoded in pure Java, so it works even on boxes with a broken ALAC decoder) |
| 🔐 **Who can connect** | Ask on the TV before a new device connects, or let anyone connect |
| ✏️ **Rename** | Choose the name your iPhone shows under Screen Mirroring |
| 🧊 **Liquid Glass look** | Translucent glass panels, built for the remote's D-pad |
| 🔄 **Runs in the background** | The receiver keeps listening after you leave the app and comes to the front when a phone connects |
| 🕰️ **Old boxes welcome** | Android 5.0 (Lollipop) and newer, no Google services needed |

## 🚀 Quick start

1. **Install** the APK on your TV or box ([see below](#installing-the-apk)).
2. Connect the TV box and the iPhone to the **same Wi‑Fi network**.
3. Open **AirMirror TV** on the TV. It shows the receiver name.
4. On the iPhone: **Control Center → Screen Mirroring →** pick that name.
5. On the TV, press **Allow** the first time a new device connects.
6. To stop, tap **Stop Mirroring** on the iPhone.

### Installing the APK

With ADB from a computer:

```bash
adb connect <tv-box-ip>
```

```bash
adb install -r AirMirrorTV.apk
```

Or copy the APK to a USB stick, open it on the TV with a file manager, and allow *Install unknown apps*.

> [!NOTE]
> `AirMirrorTV.apk` in this repository may be older than the source code. To get the latest
> fixes, [build it yourself](#%EF%B8%8F-build-from-source).

## 📱 Using the app

| What you want | How |
|---|---|
| Mirror the whole screen | Control Center → **Screen Mirroring** → pick the TV |
| Cast just one video | Tap the **AirPlay** icon in the video player (Safari, YouTube, …) |
| Play music only | Control Center → audio output → pick the TV |
| Rotate the picture | While mirroring, press **◀ / ▶** on the remote |
| Open Settings | Press **OK** on the remote (Settings is focused when the app opens) |
| Leave Settings | Press **Back** or select **Done** |

## ⚙️ Settings

Settings opens in place on the home screen, in the right-hand panel.

| Setting | What it does |
|---|---|
| **Rename this receiver** | Changes the name shown on your iPhone. **Default** restores the original name. |
| **Ask on the TV** *(default)* | A new device shows an **Allow / Deny** prompt on the TV first. |
| **Require a PIN** | Coming soon. Works like *Ask on the TV* for now. |
| **Anyone can connect** | No prompt: every device on the network connects straight away. |

## 🐞 Troubleshooting

| Problem | Try |
|---|---|
| TV not in the Screen Mirroring list | Same Wi‑Fi on both? Turn off *client / AP isolation* on the router. Open the app on the TV once. |
| Stuck on "Connecting…" | Look for an **Allow / Deny** prompt on the TV. If there isn't one, restart the app and try again. |
| Black screen | Stop mirroring, reopen the app, and mirror again. |
| YouTube shows "Connected to AirPlay" but stays on an ad at **0:00** | Use **Screen Mirroring** instead of YouTube's AirPlay button, or stop casting and start it again after the ad. See [`docs/screenshots/`](docs/screenshots/). |
| No sound | Check the TV volume, then stop and start mirroring again. |

When opening an issue, attach a log from the TV box:

```bash
adb logcat -d > airmirror-log.txt
```

## 🛠️ Build from source

Requirements: **JDK 17**, **Android SDK 34**, **Gradle 8.7+**.

```bash
gradle assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
```

The APK is written to `app/build/outputs/apk/release/`. It is signed with the debug key, so it installs directly on a TV box.

## 🧩 How it works

```
iPhone ──mDNS──▶ discovery ──▶ pairing ──▶ FairPlay handshake ──▶ encrypted stream
                                                                       │
                     TextureView ◀── MediaCodec H.264 ◀── decrypt ◀────┤
                     AudioTrack  ◀── AAC (MediaCodec) / ALAC (Java) ◀──┘
```

| Path | Role |
|---|---|
| `airplay/` | AirPlay receiver protocol, based on [serezhka/java-airplay](https://github.com/serezhka/java-airplay) (MIT) and patched for Android 5 |
| `app/.../MirrorService.java` | Background service that runs the receiver, the approval flow and the notification |
| `app/.../MainActivity.java` | Home screen, Settings panel, popups and the video surface |
| `app/.../GlassTheme.java` | Shared Liquid Glass colours, panels and buttons |
| `app/.../MirrorPlayback.java` | Routes mirroring, casting and audio to the decoders |
| `app/.../VideoDecoder.java`, `AudioDecoder.java`, `AlacDecoder.java` | Hardware video, AAC and pure-Java ALAC decoding |

## 📝 Changelog

| Version | Changes |
|---|---|
| **1.17** | Rename popup redesigned in the glass style. **Back** now closes Settings instead of leaving the app. The Settings button reads **Done** while Settings is open. |
| 1.16 | Liquid Glass redesign; Settings merged into the home screen |
| 1.11 | Fixed audio going silent after one dropped network packet |
| 1.10 | Fixed a video-casting leak that froze mirroring later (e.g. on YouTube ads) |
| 1.9 | ALAC audio decoded in pure Java |
| 1.3 – 1.7 | Stable receiver identity ("unable to connect" fix), Android 5/6 casting crashes |

## 📄 License

Copyright (c) 2026 mbnetworku. Released under the [MIT License](LICENSE).
The `airplay/` module is derived from serezhka/java-airplay (MIT); its notice is kept in
[`airplay/LICENSE-java-airplay`](airplay/LICENSE-java-airplay).
