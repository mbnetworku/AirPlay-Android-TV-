<div align="center">

<img src="docs/screenshot.png" alt="AirMirror TV" width="100%">

# 📺 AirMirror TV

### Turn any Android TV or TV box into an AirPlay receiver

Mirror your **iPhone, iPad or Mac** to the big screen, with picture and sound.<br>
No app to install on the Apple device. No account. No Google services.

<br>

<a href="https://github.com/mbnetworku/AirPlay-Android-TV-/releases/latest/download/AirMirrorTV.apk">
  <img src="https://img.shields.io/badge/⬇_Download_APK-latest-0A84FF?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" height="48">
</a>
&nbsp;
<a href="https://github.com/mbnetworku/AirPlay-Android-TV-/releases/latest">
  <img src="https://img.shields.io/badge/📦_Latest_Release-GitHub-24292F?style=for-the-badge&logo=github&logoColor=white" alt="Latest release" height="48">
</a>

<a href="https://github.com/mbnetworku/AirPlay-Android-TV-/archive/refs/heads/main.zip">
  <img src="https://img.shields.io/badge/💾_Source_Code-ZIP-6E40C9?style=for-the-badge" alt="Download source code" height="36">
</a>
&nbsp;
<a href="#-install-in-3-steps">
  <img src="https://img.shields.io/badge/📖_Install_Guide-32D74B?style=for-the-badge" alt="Install guide" height="36">
</a>
&nbsp;
<a href="https://github.com/mbnetworku/AirPlay-Android-TV-/issues/new">
  <img src="https://img.shields.io/badge/🐞_Report_a_Bug-FF453A?style=for-the-badge" alt="Report a bug" height="36">
</a>

<br><br>

[![Latest release](https://img.shields.io/github/v/release/mbnetworku/AirPlay-Android-TV-?label=latest&color=0A84FF)](https://github.com/mbnetworku/AirPlay-Android-TV-/releases/latest)
![Android](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)
![Android TV](https://img.shields.io/badge/Android_TV-ready-3DDC84?logo=androidtv&logoColor=white)
![AirPlay](https://img.shields.io/badge/AirPlay-Mirror_·_Video_·_Audio-000000?logo=apple&logoColor=white)
![Size](https://img.shields.io/badge/APK-~1.7_MB-lightgrey)
[![Build APK](https://github.com/mbnetworku/AirPlay-Android-TV-/actions/workflows/build-apk.yml/badge.svg)](https://github.com/mbnetworku/AirPlay-Android-TV-/actions/workflows/build-apk.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

<br>

[Features](#-features) •
[Install](#-install-in-3-steps) •
[Connect](#-connect-your-iphone) •
[Remote](#-remote-control) •
[Settings](#%EF%B8%8F-settings) •
[Help](#-troubleshooting) •
[Build](#%EF%B8%8F-build-from-source) •
[Changelog](#-changelog)

</div>

---

## ✨ Features

<table>
<tr>
<td width="50%" valign="top">

### 🖥️ Screen mirroring
Your whole iPhone screen on the TV, full-screen, with hardware H.264 decoding. Letterboxed to fit, and you can rotate it with the remote.

</td>
<td width="50%" valign="top">

### 🎬 Video casting
Tap the **AirPlay** icon in Safari, YouTube and other apps to send just the video, not your whole screen.

</td>
</tr>
<tr>
<td valign="top">

### 🔊 Sound
AAC-ELD, AAC and ALAC audio. ALAC is decoded in pure Java, so music works even on boxes with a broken built-in decoder.

</td>
<td valign="top">

### 🔐 You decide who connects
A new device has to be **allowed on the TV** first. Or switch to *Anyone can connect* at home.

</td>
</tr>
<tr>
<td valign="top">

### 🧊 Liquid Glass design
Translucent glass panels over your wallpaper, built for the remote's D-pad, readable from the sofa.

</td>
<td valign="top">

### 🔄 Always ready, opens by itself
Starts with the TV and waits quietly in the background. When you start AirPlay, the app opens on its own.

</td>
</tr>
<tr>
<td valign="top">

### ✏️ Rename
Give the TV a friendly name like *Living Room*. That's what your iPhone shows.

</td>
<td valign="top">

### ⬆️ Updates itself
Checks this page for new versions and shows an **Update** button. One press downloads and installs it.

</td>
</tr>
<tr>
<td valign="top">

### 🪶 Light on resources
Nothing runs while idle except a small network listener. No polling, a capped number of threads, decoding only while you watch.

</td>
<td valign="top">

### 🕰️ Works on old boxes
Android 5.0 (Lollipop) and newer. One small APK, no Google Play services needed.

</td>
</tr>
</table>

---

## 📥 Install in 3 steps

<table>
<tr>
<th>1️⃣ Download</th>
<th>2️⃣ Copy to the TV</th>
<th>3️⃣ Install</th>
</tr>
<tr>
<td valign="top">

Click **[⬇ Download APK](https://github.com/mbnetworku/AirPlay-Android-TV-/releases/latest/download/AirMirrorTV.apk)**.

</td>
<td valign="top">

Put the APK on a **USB stick** and plug it into the TV box, or use **ADB** (below).

</td>
<td valign="top">

Open the APK with a file manager on the TV and allow **Install unknown apps** when asked.

</td>
</tr>
</table>

<details>
<summary><b>💻 Install with ADB from a computer (Windows, Mac, Linux)</b></summary>

<br>

1. On the TV: **Settings → Device Preferences → About**, press **Build** 7 times to unlock Developer options.
2. In **Developer options**, turn on **USB debugging** / **Network debugging**.
3. On the computer, connect to the TV box (use the IP address shown in the app):

```bash
adb connect <tv-box-ip>
```

4. Install (or update) the app:

```bash
adb install -r AirMirrorTV.apk
```

</details>

<details>
<summary><b>🔁 Updating</b></summary>

<br>

From version 1.18, the app updates itself. It checks once a day in the background (never while you're mirroring) and downloads a new version quietly. When it's ready, an **⬆ Install** button appears on the home screen (and in **Settings → Check for updates**). Press it, then confirm the install. Android always asks for that confirmation.

The first time, Android may ask you to allow AirMirror TV to **install unknown apps**. Allow it and press **Update now** again.

> [!IMPORTANT]
> Version 1.18 is the first one signed with the permanent release key. If you have **1.17 or older**, uninstall it once and install 1.18. All later updates install over it, keeping your settings.

</details>

> [!TIP]
> The **⬇ Download APK** button always gives the newest release.

---

## 📱 Connect your iPhone

| Step | On the… | Do this |
|:---:|:---:|---|
| 1 | 📶 Both | Connect the TV box and the iPhone to the **same Wi‑Fi network** |
| 2 | 📺 TV | Open **AirMirror TV**. Note the name in the big blue box |
| 3 | 📱 iPhone | Open **Control Center → Screen Mirroring** and pick that name |
| 4 | 📺 TV | The first time, choose **Allow** on the prompt |
| 5 | 📱 iPhone | Tap **Stop Mirroring** when you're done |

**Other ways to send:**

- 🎬 **One video only:** tap the **AirPlay** icon in the video player (Safari, YouTube, …).
- 🎵 **Music only:** Control Center → audio output → pick the TV.

---

## 🎮 Remote control

| Button | Home screen | While mirroring |
|:---:|---|---|
| **OK** | Opens **Settings** (focused when the app starts) | — |
| **◀ ▶** | Move between buttons | **Rotate** the picture 90° |
| **▲ ▼** | Move between settings | — |
| **Back** | Closes Settings, or leaves the app | — |

---

## ⚙️ Settings

Settings opens right on the home screen, in the right-hand panel. Select **Done** or press **Back** to close it.

| Setting | What it does |
|---|---|
| ✏️ **Rename this receiver** | Changes the name your iPhone shows. **Default** restores the original name. |
| ✅ **Ask on the TV** *(default)* | A new device shows an **Allow / Deny** prompt on the TV first. |
| 🔢 **Require a PIN** | *Coming soon.* Works like *Ask on the TV* for now. |
| 🌐 **Anyone can connect** | No prompt. Every device on the network connects straight away. |
| 🔌 **Start when the TV turns on** *(on)* | Starts the receiver in the background at power-on, so the iPhone always finds the TV. |
| 🚀 **Open automatically** *(on)* | Brings the app to the front when you start AirPlay. On Android 10+ this needs **Display over other apps** (see below). |
| ⬆️ **Check for updates** | Looks for a newer version on GitHub and installs it. |

### 🚀 Making "Open automatically" work on Android 10 and newer

Android 10+ only lets an app open itself if it may **display over other apps**. In Settings, select **Open automatically** and allow it on the screen that opens.

Some TVs hide that screen. Then run this once from a computer with ADB:

```bash
adb shell appops set com.airmirror.tv SYSTEM_ALERT_WINDOW allow
```

Android 5–9 needs nothing extra.

---

## 🐞 Troubleshooting

<details>
<summary><b>The TV isn't in the Screen Mirroring list</b></summary>

- Make sure both devices are on the **same Wi‑Fi** (not a guest network).
- Turn off **client isolation / AP isolation** on your router.
- Open the app on the TV once after installing.

</details>

<details>
<summary><b>The app doesn't open by itself when I start AirPlay</b></summary>

- In **Settings**, check that **Open automatically** shows **On**.
- On Android 10+, allow **Display over other apps** ([how](#-making-open-automatically-work-on-android-10-and-newer)).
- Some TV brands also have their own "auto-start" or "background apps" setting. Allow AirMirror TV there too.

</details>

<details>
<summary><b>Update says "App not installed"</b></summary>

You're updating from version 1.17 or older, which used a different signing key. Uninstall AirMirror TV once, then install the newest APK.

</details>

<details>
<summary><b>Stuck on "Connecting…"</b></summary>

- Look at the TV: there may be an **Allow / Deny** prompt waiting.
- If there isn't one, close and reopen the app, then try again.

</details>

<details>
<summary><b>Black screen</b></summary>

Stop mirroring on the iPhone, reopen the app on the TV and mirror again.

</details>

<details>
<summary><b>YouTube says "Connected to AirPlay" but stays on an ad at 0:00</b></summary>

Known issue being investigated. For now, use **Screen Mirroring** from Control Center instead of YouTube's own AirPlay button, or stop casting and start again after the ad.
Screenshots are in [`docs/screenshots/`](docs/screenshots/).

</details>

<details>
<summary><b>No sound</b></summary>

Check the TV volume and the iPhone's silent switch, then stop and start mirroring again.

</details>

**Still stuck?** [Open an issue](https://github.com/mbnetworku/AirPlay-Android-TV-/issues/new) and attach a log from the TV box:

```bash
adb logcat -d > airmirror-log.txt
```

---

## 🛠️ Build from source

**Automatic:** every push to `main` builds the APK with GitHub Actions ([Actions page](https://github.com/mbnetworku/AirPlay-Android-TV-/actions/workflows/build-apk.yml)).

### 🚢 Publishing an update

1. In `app/build.gradle.kts`, change one line, for example `val appVersion = "1.19"`.
2. Push to `main` (or merge a pull request).

GitHub Actions builds the APK and publishes **Release v1.19** with it attached. Every installed app then shows the **⬆ Update** button the next time it's opened. If you push without changing the version, nothing is released.

### 🔑 One-time setup: the signing key

Android only installs an update over an app signed with the **same private key**. The key is kept out of the repository, in GitHub Secrets, and releases are published only when it's there.

1. On a Windows PC with Android Studio or a JDK installed, double-click **[`tools/create-signing-key.bat`](tools/create-signing-key.bat)**.
2. Choose a password. The tool creates the key in `%USERPROFILE%\AirMirrorTV-signing\`, copies its text to your clipboard, and opens GitHub's **New secret** page.
3. Add two **repository secrets**:
   - `AIRMIRROR_KEYSTORE_BASE64`: paste with Ctrl+V
   - `AIRMIRROR_KEYSTORE_PASSWORD`: your password
4. On GitHub, open **Actions → Build APK → Run workflow** on `main`. This publishes the release for the current version.

> [!CAUTION]
> Back up the `AirMirrorTV-signing` folder and never share or commit it. If it's lost, every TV has to uninstall and reinstall the app once.

To sign a local build with the same key, set the `AIRMIRROR_KEYSTORE` (file path) and `AIRMIRROR_KEYSTORE_PASSWORD` environment variables before running Gradle.

**Manual:** needs **JDK 17**, **Android SDK 34** and **Gradle 8.7+**.

```bash
gradle assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
```

The APK is written to `app/build/outputs/apk/release/`. It's signed with the debug key, so it installs directly on a TV box.

<details>
<summary><b>🧩 How it works</b></summary>

<br>

```
iPhone ──mDNS──▶ discovery ──▶ pairing ──▶ FairPlay handshake ──▶ encrypted stream
                                                                       │
                     TextureView ◀── MediaCodec H.264 ◀── decrypt ◀────┤
                     AudioTrack  ◀── AAC (MediaCodec) / ALAC (Java) ◀──┘
```

| Path | Role |
|---|---|
| `airplay/` | AirPlay receiver protocol, based on [serezhka/java-airplay](https://github.com/serezhka/java-airplay) (MIT) and patched for Android 5 |
| `app/.../MirrorService.java` | Background service: runs the receiver, the Allow/Deny flow and the notification |
| `app/.../MainActivity.java` | Home screen, Settings panel, popups and the video surface |
| `app/.../GlassTheme.java` | Shared Liquid Glass colours, panels and buttons |
| `app/.../UpdateChecker.java`, `ApkProvider.java` | In-app updates from GitHub Releases |
| `app/.../BootReceiver.java` | Starts the receiver when the TV turns on |
| `app/.../MirrorPlayback.java` | Routes mirroring, casting and audio to the decoders |
| `app/.../VideoDecoder.java` · `AudioDecoder.java` · `AlacDecoder.java` | Hardware video, AAC and pure-Java ALAC decoding |

</details>

---

## 📝 Changelog

| Version | What's new |
|:---:|---|
| **1.19** | ⬆️ Updates download quietly in the background (checked once a day, never while mirroring), so **Install** is instant · 🔔 "Update ready" notification · 🔐 Signing secrets are trimmed and checked before building |
| 1.18 | 🚀 Opens automatically when AirPlay starts · 🔌 Starts with the TV · ⬆️ In-app updates from GitHub · 🪶 Fewer background threads; restarts only when the network changes instead of polling · 🛠️ Fixed the iPhone sometimes losing the TV after a Rename · ⚙️ New Background and About & updates settings |
| 1.17 | 🧊 Rename popup redesigned in the glass style · ⬅️ **Back** closes Settings instead of leaving the app · ✅ Settings button shows **Done** while open · 📖 New README with download buttons · 🤖 Automatic APK builds |
| 1.16 | Liquid Glass redesign; Settings merged into the home screen |
| 1.11 | Fixed audio going silent after one dropped network packet |
| 1.10 | Fixed a video-casting leak that froze mirroring later (e.g. on YouTube ads) |
| 1.9 | ALAC audio decoded in pure Java |
| 1.3 – 1.7 | Stable receiver identity ("unable to connect" fix), Android 5/6 casting crashes |

---

<div align="center">

### 📄 License

Copyright © 2026 mbnetworku · Released under the [MIT License](LICENSE)<br>
The `airplay/` module is derived from serezhka/java-airplay (MIT); see [`airplay/LICENSE-java-airplay`](airplay/LICENSE-java-airplay).

<br>

<a href="https://github.com/mbnetworku/AirPlay-Android-TV-/releases/latest/download/AirMirrorTV.apk">
  <img src="https://img.shields.io/badge/⬇_Download_AirMirror_TV-0A84FF?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" height="40">
</a>

⭐ If this app helps you, give the repo a star!

</div>
