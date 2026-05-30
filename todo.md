# RetroCast Development Todo List

## 🟢 Completed
- [x] **Low-Level Multi-Touch Gamepad**: Implementation of `pointerInput` for simultaneous button presses.
- [x] **8-Way D-Pad**: Vector-based input handling for diagonal movement.
- [x] **WebRTC Handshake**: Full SDP Offer/Answer and ICE candidate exchange.
- [x] **mDNS Discovery**: Automatic service registration for easy TV connection.
- [x] **Native-to-Stream Pipeline**: Linking Libretro C++ bridge to WebRTC Hardware Encoder.
- [x] **60fps Game Loop**: Thread-safe synchronization on high-priority background thread.
- [x] **ROM Management Loader**: File picker UI and core mapping for GBA.
- [x] **ZIP ROM Support**: Automatic extraction and loading of ROMs from ZIP archives.
- [x] **Pixel Fidelity & Color Correction**: Universal converter for RGB565/0RGB1555/XRGB8888 with natural GBA saturation.
- [x] **Auto-Core Downloader**: Zero-config engine fetching for different CPU architectures.
- [x] **Atomic Stability Overhaul**: Mutex-protected native bridge to prevent SIGSEGV crashes.

## 🟡 In Progress
- [ ] **Audio Streaming**: Refinement of the native-to-Kotlin audio samples bridge.
- [ ] **Frame Scaling**: Moving from software converter to OpenGL/Vulkan shaders in `native-lib.cpp`.

## 🔴 Upcoming (Backlog)
- [ ] **Security Layer**: Add PIN-based pairing for the signaling server.
- [ ] **ROM Manager**: UI for selecting cores and game files from storage.
- [ ] **Input Latency Profiling**: Tooling to measure end-to-end "glass-to-glass" latency.
- [ ] **Dynamic Layout**: Allow users to resize and reposition gamepad buttons.
- [ ] **Save State Support**: JNI bridge for `retro_serialize` and `retro_unserialize`.
