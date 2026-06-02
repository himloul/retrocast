# RetroCast Console

Android GBA emulator that streams lossless-quality video and audio to any browser on the local network. The device serves as controller, emulator host, and streaming source — no cloud, no servers, no setup.

## Overview

Runs GBA ROMs via the mGBA Libretro core. Renders at 60fps on the device while simultaneously streaming H.264 hardware-encoded video (MediaCodec, 20 Mbps peak) and PCM16 audio (DataChannel) to a browser-based receiver. Audio uses a DataChannel instead of a WebRTC audio track to avoid Opus encoding latency. The receiver uses Web Audio API with a jitter buffer for glitch-free scheduling.

Supports local display, external HDMI output via Android Presentation API, and casting — all three render the same frame simultaneously.

## Pipeline

- Native frame pointer is passed directly to the I420 converter — no intermediate memcpy or ARGB round-trip.
- The ARGB pixel conversion for local display is skipped entirely when casting without an attached screen, saving a full pixel walk per frame.
- Audio data is sent over the DataChannel from the JNI direct buffer without a redundant copy.
- Host-only ICE with no STUN dependency — connects in ~200ms on the same WiFi.
- Frame throttle at 16ms (62.5 FPS ceiling) prevents encoder backpressure without false drops at 60 FPS.

## Quick start

```bash
./gradlew assembleDebug
```

Install the APK on an Android device (minSdk 26, arm64-v8a). Open the app, select a `.gba` ROM file, tap the cast button, and open the displayed URL in a browser on the same network.

The first launch will prompt the app to download the mGBA Libretro core from the official buildbot.

