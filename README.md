# AirMirror TV

An Android TV app that lets an iPhone, iPad or Mac mirror its screen to an Android TV box
using Apple's built-in **Screen Mirroring** (AirPlay). You don't need anything on the iPhone.

## Install on the TV box

1. Copy `AirMirrorTV.apk` to the box using a USB stick or a file-sharing app, then open it.
   You may need to allow "Install unknown apps" for your file manager.
2. Or use adb over the network (turn on Developer options, then USB/Network debugging):

   ```
   adb connect <tv-box-ip>
   adb install -r AirMirrorTV.apk
   ```

## Use it

1. Connect the TV box and the iPhone to the **same Wi‑Fi network**. The box can also use Ethernet on the same router.
2. Open **AirMirror TV** on the box. It shows the receiver name.
3. On the iPhone, open Control Center, tap **Screen Mirroring**, and pick that name.
4. To stop, tap **Stop Mirroring** on the iPhone, or press Back on the remote.

## How it works

- `airplay/`: the AirPlay receiver protocol (mDNS discovery, pairing, FairPlay handshake,
  decrypting the mirror stream), vendored from
  [serezhka/java-airplay](https://github.com/serezhka/java-airplay) (MIT, see
  `airplay/LICENSE-java-airplay`). It was patched for Android: Netty 4.1 with NIO only,
  no Java 21-only APIs, a stable device id because Android hides the Wi‑Fi MAC, and safe start/stop.
- `app/`: `MainActivity` runs the receiver while it is on screen. `VideoDecoder` sends the
  H.264 stream to the hardware decoder and draws it on a SurfaceView, letterboxed to the
  right shape. `AudioDecoder` decodes AAC-ELD, AAC or ALAC audio and plays it through AudioTrack.

## Build

Needs JDK 17, Android SDK 34, and Gradle 8.7. On this machine that is `..\tools\gradle-8.7`.

```
gradle assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
```

`gradle.properties` tells Java to use the Windows certificate store, because the default Java
trust store can't reach Maven over HTTPS on this machine.

## Limits

- The app must be open on the TV to receive. It doesn't run in the background.
- Playing a video from an app such as YouTube with the AirPlay button (HLS casting) is turned off.
  Screen Mirroring works with every app.
- Sound from mirroring uses AAC-ELD, which works on every Android 5+ device. Music-only AirPlay
  uses ALAC, and only plays if the box has an ALAC decoder.
- Works on Android 5.0 (Lollipop) and newer.

## License

Copyright (c) 2026 mbnetworku. Released under the MIT License, see `LICENSE`.
The `airplay/` module is derived from serezhka/java-airplay (MIT), whose notice is kept in
`airplay/LICENSE-java-airplay`.
