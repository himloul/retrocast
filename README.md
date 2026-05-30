# ShareScreen Console

Android GBA emulator that streams video and audio to any browser on the local network. The device serves as controller, emulator host, and WebRTC streaming source — no cloud, no servers, no setup.

## Overview

Runs GBA ROMs via the mGBA Libretro core. Renders at 60fps on the device while simultaneously streaming H.264 video over RTP and PCM16 audio over a WebRTC DataChannel to a browser-based receiver. Audio travels over a DataChannel instead of a WebRTC audio track to avoid Opus encoding latency. The receiver uses Web Audio API with a jitter buffer for glitch-free scheduling.

Supports local display, external HDMI output via Android Presentation API, and streaming — all three render the same frame simultaneously.

## Quick start

```bash
./gradlew assembleDebug
```

Install the APK on an Android device (minSdk 26, arm64-v8a). Open the app, select a `.gba` ROM file, tap the cast button, and open the displayed URL in a browser on the same network.

The first launch will prompt the app to download the mGBA Libretro core from the official buildbot.

