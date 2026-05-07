# ShareScreen Development Todo List

## 🟢 Completed
- [x] **Low-Level Multi-Touch Gamepad**: Implementation of `pointerInput` for simultaneous button presses.
- [x] **8-Way D-Pad**: Vector-based input handling for diagonal movement.
- [x] **WebRTC Handshake**: Full SDP Offer/Answer and ICE candidate exchange.
- [x] **mDNS Discovery**: Automatic service registration for easy TV connection.
- [x] **Native-to-Stream Pipeline**: Linking Libretro C++ bridge to MediaCodec input surface.
- [x] **60fps Game Loop**: Synchronization using Android `Choreographer`.
- [x] **ROM Management Loader**: File picker UI and core mapping for GBA.
- [x] **ZIP ROM Support**: Automatic extraction and loading of ROMs from ZIP archives.

## 🟡 In Progress
- [ ] **Libretro Core Loading**: Refining the JNI bridge for various core architectures.
- [ ] **Frame Scaling**: Moving from software `memcpy` to OpenGL/Vulkan shaders in `native-lib.cpp`.

## 🔴 Upcoming (Backlog)
- [ ] **Audio Streaming**: Implement WebRTC `AudioTrack` and pipe Libretro audio samples.
- [ ] **Security Layer**: Add PIN-based pairing for the signaling server.
- [ ] **ROM Manager**: UI for selecting cores and game files from storage.
- [ ] **Input Latency Profiling**: Tooling to measure end-to-end "glass-to-glass" latency.
- [ ] **Dynamic Layout**: Allow users to resize and reposition gamepad buttons.
- [ ] **Save State Support**: JNI bridge for `retro_serialize` and `retro_unserialize`.
