# Ghost v1.0

Privacy-first on-demand screen analysis for Android 16 / Samsung One UI 8.0

## Overview

Ghost is a side-loaded Android application that provides instant screen analysis using a locally-hosted LLM (Gemma 4 E2B). It captures your screen on-demand, analyzes the content using on-device AI running on Hexagon NPU/GPU, and streams the answer back - all without any network access.

### Key Features

- 🔒 **Zero Network Access**: No `INTERNET` permission; all inference is local
- ⚡ **Hardware Accelerated**: Uses Hexagon NPU with GPU fallback
- 🖼️ **Single Frame Capture**: Captures screen state exactly once (no video stream)
- 🔋 **Zero Background Drain**: No services, no notifications when closed
- 🎯 **Android 16 Compliant**: Uses official MediaProjection with permission dialog

## Requirements

### Device
- Samsung Galaxy S25+ (or equivalent Android 16 device)
- One UI 8.0 / Android 16 (API 36+)
- 12GB RAM recommended
- Hexagon NPU support

### Model File
Before using Ghost, you must place the model file:

```
Internal Storage/Downloads/GhostModels/gemma-4-e2b.litertlm
```

The file should be approximately 2.5GB. The app will not function without it.

## Installation

### 1. Build from Source

```bash
cd ghost
./gradlew assembleRelease
```

### 2. Install APK

```bash
adb install app/build/outputs/apk/release/app-release-unsigned.apk
```

Or manually copy the APK to your device and install via file manager.

### 3. Grant Permissions

On first launch, the app will redirect you to system settings to grant:
- **All files access** (for reading the model file)
- **Display over other apps** (for the floating PiP window)

### 4. Place Model File

Copy `gemma-4-e2b.litertlm` to `Internal Storage/Downloads/GhostModels/`

## Usage

### Trigger
1. Double-tap the Side Key (configurable in system settings)
2. Select "Ghost" as the target app

### Flow
1. **Permission Dialog**: System asks for screen capture permission
2. **Capture**: Single frame is captured (1280×720)
3. **PiP Window**: Floating window appears with the screenshot thumbnail
4. **Ask**: Type your question about the screen content
5. **Analyze**: Local LLM processes the image and streams the answer
6. **Close**: Tap × or swipe off-screen to dismiss

## Architecture

```
GhostActivity (Entry Point)
├── MediaProjectionManager → Capture single Bitmap (1280×720)
├── LiteRT-LM Inference Engine
│   ├── Model Loader (/sdcard/Download/GhostModels/)
│   ├── HexagonNpuDelegate (primary) / GpuDelegate (fallback)
│   └── AsyncTokenGenerator (streaming responses)
├── WindowManager Overlay (TYPE_APPLICATION_OVERLAY)
│   ├── 340dp×600dp PiP window
│   ├── Jetpack Compose UI (Material3 dark theme)
│   └── Draggable + dismissible
└── MemoryManager (aggressive cleanup on close)
```

## Memory Budget

| Component | Memory |
|-----------|--------|
| Model footprint | ~2.5GB (memory-mapped) |
| App + Compose overhead | ~400MB |
| Bitmap buffers | ~10MB |
| **Total target** | **<3GB peak** |

## Development

### Project Structure

```
ghost/
├── app/src/main/java/com/ghost/app/
│   ├── GhostActivity.kt          # Main orchestrator
│   ├── GhostApplication.kt       # Application class
│   ├── capture/
│   │   ├── ScreenCaptureManager.kt
│   │   └── BitmapConverter.kt
│   ├── inference/
│   │   ├── InferenceEngine.kt
│   │   ├── ModelValidator.kt
│   │   └── ThermalMonitor.kt
│   ├── ui/
│   │   ├── GhostWindowManager.kt
│   │   ├── GhostInterface.kt
│   │   └── DragHandler.kt
│   └── utils/
│       ├── GhostPaths.kt
│       ├── MemoryManager.kt
│       └── PermissionChecker.kt
└── ...
```

### Build Configuration

- **compileSdk**: 36
- **minSdk**: 36 (Android 16 only)
- **targetSdk**: 36
- **NDK**: 27.0.12077973
- **ABI**: arm64-v8a only

### Key Dependencies

- `com.google.ai.edge.litert:litert-support-api:1.2.0`
- `com.google.ai.edge.litert:litert-gpu:1.2.0`
- `com.google.ai.edge.litert:litert-hexagon-npu:1.2.0`
- `androidx.compose.material3:material3:1.3.0`

## Permissions

| Permission | Purpose | Required |
|------------|---------|----------|
| `MANAGE_EXTERNAL_STORAGE` | Read 2.5GB model file | Yes |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Screen capture on Android 16 | Yes |
| `SYSTEM_ALERT_WINDOW` | Floating PiP window | Yes |
| `INTERNET` | **NOT DECLARED** | N/A |

## Troubleshooting

### "Model not found" error
Ensure `gemma-4-e2b.litertlm` is placed in `Internal Storage/Downloads/GhostModels/`

### Permission denied
Grant both "All files access" and "Display over other apps" in system settings

### Screen capture not working
Check that no other app is currently using MediaProjection

### Thermal throttling
Device will automatically switch from NPU to GPU if it gets warm

## Safety & Privacy

- **No data leaves the device**: No network permission declared
- **No background activity**: App fully terminates when PiP is closed
- **No persistent storage**: Screenshots are held in memory only
- **No analytics/telemetry**: Zero external communication

## License

Private use only. Not for redistribution.

## Version History

### v1.0 (2024-XX-XX)
- Initial release
- MediaProjection screen capture
- LiteRT-LM local inference
- Hexagon NPU support with GPU fallback
- Floating PiP Compose UI
- Thermal monitoring
# Build Sat Apr 11 21:22:52 UTC 2026
