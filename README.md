# Ghost v1.0

Privacy-first on-demand screen analysis for Samsung Galaxy S25+ (Android 16 / One UI 8.0)

## What is Ghost?

Ghost is a side-loaded Android application that provides instant, private screen analysis using a locally-hosted LLM. Triggered by double-tapping the Side Key, it captures a single screenshot, streams it to a local Gemma 4 E2B model running on your device's Hexagon NPU, and displays the answer in a floating Picture-in-Picture window.

### Privacy-First Design
- **Zero network access**: No `INTERNET` permission declared
- **Zero background drain**: No services, no notifications when closed
- **Zero data retention**: Screenshots exist only in memory

## Quick Start

```bash
cd ghost
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release-unsigned.apk
```

See [ghost/README.md](ghost/README.md) for detailed installation and usage instructions.

## Project Structure

```
ghost/
├── app/
│   ├── build.gradle.kts          # Module build config (Android 16, LiteRT-LM)
│   └── src/main/
│       ├── AndroidManifest.xml   # App manifest (no INTERNET permission)
│       ├── java/com/ghost/app/
│       │   ├── GhostActivity.kt  # Main orchestrator
│       │   ├── GhostApplication.kt
│       │   ├── capture/          # MediaProjection screen capture
│       │   ├── inference/        # LiteRT-LM inference engine
│       │   ├── ui/               # Compose PiP window UI
│       │   └── utils/            # Permissions, memory management
│       └── res/                  # Resources (themes, strings, icons)
├── build.gradle.kts              # Project build config
├── settings.gradle.kts           # Project settings
└── README.md                     # Full documentation
```

## Key Components

| Component | Purpose |
|-----------|---------|
| `GhostActivity` | Entry point, orchestrates capture → inference → UI |
| `ScreenCaptureManager` | MediaProjection wrapper for single-frame capture |
| `InferenceEngine` | LiteRT-LM wrapper with Hexagon NPU/GPU fallback |
| `GhostWindowManager` | Floating PiP window with drag gestures |
| `ThermalMonitor` | NPU→GPU fallback on thermal throttling |
| `MemoryManager` | Aggressive cleanup to minimize memory footprint |

## Technical Specifications

- **Target**: Android 16 (API 36) / One UI 8.0
- **Architecture**: arm64-v8a
- **Memory Budget**: <3GB peak (2.5GB model + 400MB app + 10MB buffers)
- **Capture Resolution**: 1280×720 RGBA_8888
- **LLM**: Gemma 4 E2B (2.5GB .litertlm file)

## License

Private use only. Not for redistribution.
