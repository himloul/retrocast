# Implementation Plan - Phase 2: Local Rendering & Libretro Bridge

## Objective
Implement the C++ bridge to handle Libretro cores and set up the rendering pipeline to display game frames on an Android Surface.

## Key Components
- **`libretro.h`**: The standard Libretro API header.
- **`RetroBridge.cpp`**: Core logic for loading `.so` files and handling callbacks.
- **`EmulatorView.kt`**: A custom view to display the emulator output.

## Implementation Steps

### 1. Libretro API Definition
- Define the essential `libretro.h` constants and structures in C++.
- Implement the callback functions:
    - `video_refresh`: Receives pixel data and copies it to a buffer.
    - `audio_sample_batch`: Pipes audio to the Android audio system.
    - `input_poll` / `input_state`: Connects the Touchpad UI to the core.

### 2. The Native Loop
- Create a dedicated thread for `retro_run()`.
- Synchronize the loop with the core's frame rate (usually 60Hz).

### 3. Rendering Pipeline
- Implement a mechanism to pass an Android `Surface` to the C++ layer.
- Use OpenGL ES to draw the raw pixel buffer from Libretro onto the Surface.

### 4. Sample Core Integration
- Set up a "Mock Core" or integrate a lightweight core (e.g., Gambatte or Nestopia) for verification.

## Verification
- Load a core and ROM.
- Verify that `video_refresh` is being called.
- See the game rendered on the phone's screen before we move to remote casting.
