# Ghost v1.5

Privacy-first on-demand screen analysis for Android 16 / Samsung One UI 8.0

## Overview

Ghost is a side-loaded Android application that provides instant screen analysis using a locally-hosted LLM (Gemma 4 E2B). It captures your screen on-demand, analyzes the content using on-device AI running on Hexagon NPU/GPU, and streams the answer back - all without any network access.

### Key Features

- 🔒 **Privacy-First**: All LLM inference is local on Hexagon NPU/GPU
- ⚡ **Hardware Accelerated**: Uses Hexagon NPU with GPU fallback
- 🔊 **HAL 9000 Voice Synthesis**: Sherpa-ONNX Piper TTS with morphing Play/HAL button and staccato pulse animation
- 🖼️ **Visual / Text Mode Toggle**: `TXT` mode (text-only) is default while vision API is broken; tap to switch to `VIS` mode
- 🌐 **Optional Web Search**: Wikipedia API via MediaWiki (no API key required). Full article injected into prompt
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
Internal Storage/Download/GhostModels/gemma-4-e2b.litertlm
```

The file should be approximately 2.5GB. The app will not function without it.

## Phone-Only Setup Guide (No PC Required)

You can set up Ghost entirely from your Android phone without ever touching a desktop computer. This guide covers installing Termux, downloading the LLM and TTS models, converting the HAL 9000 voice, and installing the APK.

### What You Need
- Android 16 device (e.g., Samsung Galaxy S25+)
- 12GB+ RAM
- ~5GB free storage
- Stable Wi-Fi connection

---

### Step 1: Install Termux

Termux is a terminal emulator for Android. You need it to download large model files directly to the correct folder.

**Do NOT install Termux from the Google Play Store** — that version is outdated and unsupported.

**Install from F-Droid (recommended):**
1. Open your phone browser and go to: `https://f-droid.org/packages/com.termux/`
2. Tap **"Download APK"**
3. Once downloaded, tap the APK to install. If prompted, allow installation from your browser.
4. Open Termux.

**Alternative: Install from GitHub:**
1. Go to: `https://github.com/termux/termux-app/releases`
2. Find the latest release and download `termux-app_v*.apk`
3. Install and open it.

**Grant Storage Permission:**
Inside Termux, run:
```bash
termux-setup-storage
```
Tap **Allow** when Android asks for file access. This creates a link to your internal storage at `/storage/emulated/0/`.

---

### Step 2: Create the Model Folder

All models must live in `Internal Storage/Download/GhostModels/`. Create it now:

```bash
mkdir -p /storage/emulated/0/Download/GhostModels
```

---

### Step 3: Download the Gemma 4 E2B LLM Model

This is the brain of Ghost (~2.5 GB). Download it with `wget` inside Termux:

```bash
cd /storage/emulated/0/Download/GhostModels
wget https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm -O gemma-4-e2b.litertlm
```

> **Tip:** If `wget` is not installed, run `pkg install wget` first.  
> **Tip:** This download can take 10–30 minutes. Keep Termux in the foreground or plug in your charger so Android does not kill the process.

Verify the file is there:
```bash
ls -lh /storage/emulated/0/Download/GhostModels/gemma-4-e2b.litertlm
```

If the download is interrupted, resume it with:
```bash
wget -c https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm -O gemma-4-e2b.litertlm
```

---

### Step 4: Download the HAL 9000 TTS Model Files

Ghost speaks with a HAL 9000 voice using a Piper TTS model. Download the raw files:

```bash
cd /storage/emulated/0/Download/GhostModels
wget https://huggingface.co/campwill/HAL-9000-Piper-TTS/resolve/main/hal.onnx
wget https://huggingface.co/campwill/HAL-9000-Piper-TTS/resolve/main/hal.onnx.json
```

---

### Step 5: Convert the HAL Model (Google Colab on Your Phone)

The raw HAL model needs metadata patching before Sherpa-ONNX can use it. Because `pip install onnx` almost always fails inside Termux, the reliable phone-only method is **Google Colab** in your web browser.

1. Open **Chrome** on your phone and go to: `https://colab.research.google.com`
2. Sign in with a Google account (free to create)
3. Tap **"New notebook"**
4. Add and run these two cells:

**Cell 1 — Upload files:**
```python
from google.colab import files
uploaded = files.upload()  # Upload hal.onnx and hal.onnx.json
```
> Tap the upload widget and select `hal.onnx` and `hal.onnx.json` from `Internal Storage/Download/GhostModels/`.

**Cell 2 — Convert:**
```python
import json, os
from typing import Any, Dict
import onnx

def add_meta_data(filename, meta_data):
    model = onnx.load(filename)
    while len(model.metadata_props):
        model.metadata_props.pop()
    for key, value in meta_data.items():
        meta = model.metadata_props.add()
        meta.key = key
        meta.value = str(value)
    onnx.save(model, filename)

config = json.load(open("hal.onnx.json"))

# Generate tokens.txt
with open("tokens.txt", "w", encoding="utf-8") as f:
    for symbol, ids in config["phoneme_id_map"].items():
        f.write(f"{symbol} {ids[0]}\n")

sample_rate = config["audio"]["sample_rate"]
if sample_rate == 22500:
    sample_rate = 22050

meta_data = {
    "model_type": "vits",
    "comment": "piper",
    "language": config.get("language", {}).get("code", "en-us").split("-")[0],
    "voice": config.get("espeak", {}).get("voice", "en-us"),
    "has_espeak": 1,
    "n_speakers": config.get("num_speakers", 1),
    "sample_rate": sample_rate,
}
add_meta_data("hal.onnx", meta_data)
print("Conversion complete! Downloading files...")
files.download("hal.onnx")
files.download("tokens.txt")
```

5. When Chrome prompts you to download, save both files **back into** `Internal Storage/Download/GhostModels/`, overwriting the old `hal.onnx`.

> **Important:** The app auto-generates `tokens.txt` on-device if `hal.onnx.json` is present, but the ONNX metadata patch **must** be done in Colab. Do not skip this step.

---

### Step 6: Download `espeak-ng-data`

Sherpa-ONNX needs phoneme data to speak. Download it in Termux:

```bash
cd /storage/emulated/0/Download/GhostModels
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2
tar xf espeak-ng-data.tar.bz2
rm espeak-ng-data.tar.bz2
```

Your `GhostModels` folder should now contain:
```
gemma-4-e2b.litertlm   (the LLM, ~2.5 GB)
hal.onnx               (patched HAL voice model)
hal.onnx.json          (HAL voice config)
tokens.txt             (phoneme tokens)
espeak-ng-data/        (phoneme data directory)
```

---

### Step 7: Get the Ghost APK

Because building an Android app requires the Android SDK and NDK, you need a pre-built APK.

**Option A — GitHub Actions Artifact (recommended)**
1. On your phone browser, go to the Ghost GitHub repository → **Actions** tab
2. Find the latest successful workflow run for **"Build Ghost APK"**
3. Tap it, scroll down to **Artifacts**, and download `ghost-pip-ui-apk`
4. Extract the ZIP (your file manager can usually do this) — you will find `app-release-signed.apk`

**Option B — Ask a friend**
Send them the repo link and ask them to run:
```bash
cd ghost
./gradlew assembleRelease
```
Then have them send you the APK via messaging app or cloud storage.

**Option C — Draft Release**
Check the repository's **Releases** page for a draft release that may contain the APK.

---

### Step 8: Install the APK

1. Open your file manager and navigate to the downloaded APK
2. Tap it. If Android blocks it, go to **Settings → Apps → Install unknown apps** and allow your file manager or browser.
3. Tap **Install**.

---

### Step 9: First Launch & Permissions

Open Ghost. You will be sent to system settings to grant:

1. **All files access** — so Ghost can read the 2.5GB model
2. **Display over other apps** — so the floating PiP window can appear

Grant both, then return to Ghost.

---

### Step 10: Trigger Ghost

1. Go to **Settings → Advanced features → Side key**
2. Set **Double press** to open an app, and choose **Ghost**
3. Double-tap the Side Key. A permission dialog for screen capture will appear — tap **Start now**
4. The Ghost terminal opens. You are ready to use it.

## Usage

### Trigger
1. Double-tap the Side Key (configurable in system settings)
2. Select "Ghost" as the target app

### Flow
1. **Permission Dialog**: System asks for screen capture permission
2. **Capture**: Single frame is captured (1280×720)
3. **PiP Window**: Floating terminal appears with Iris and mode toggle
4. **Select Mode**: `TXT` (default) for text-only, `VIS` for screenshot analysis
5. **Toggle Web Search** (optional, TXT mode only): Tap globe 🌐 to enable Wikipedia search. Hidden in VIS mode to reduce visual clutter
6. **Ask**: Type your question about the screen content
7. **Analyze**: Local LLM processes the query and streams the answer
8. **Close**: Tap × or swipe off-screen to dismiss

## Architecture

```
GhostActivity (Entry Point)
├── MediaProjectionManager → Capture single Bitmap (1280×720)
├── LiteRT-LM Inference Engine
│   ├── Model Loader (/storage/emulated/0/Download/GhostModels/)
│   ├── HexagonNpuDelegate (primary) / GpuDelegate (fallback)
│   ├── WikipediaSearchService (MediaWiki API client, no key needed)
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
│   │   ├── WikipediaSearchService.kt # MediaWiki API client
│   │   ├── PiperTTS.kt           # HAL 9000 voice synthesis (Sherpa-ONNX)
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
├── scripts/
│   └── convert_hal_model.py      # Desktop Piper → Sherpa-ONNX converter
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
| `INTERNET` | Optional Wikipedia web search API | No (offline mode works without it) |

## Troubleshooting

### "Model not found" error
Ensure `gemma-4-e2b.litertlm` is placed in `Internal Storage/Download/GhostModels/`

### HAL 9000 voice not playing
1. **Ensure you converted the model.** If you don't have a desktop, use **Google Colab** in your phone browser (see the [Phone-Only Setup Guide](#phone-only-setup-guide-no-pc-required) above).
   ```bash
   # Desktop (reliable)
   pip install onnx==1.17.0
   python3 scripts/convert_hal_model.py /path/to/model/dir
   ```
2. Ensure these files are in the same folder as `hal.onnx`:
   - `hal.onnx.json`
   - `tokens.txt`
   - `espeak-ng-data/`
3. Check logcat for `PiperTTS` initialization errors

### Permission denied
Grant both "All files access" and "Display over other apps" in system settings

### Screen capture not working
Check that no other app is currently using MediaProjection

### Thermal throttling
Device will automatically switch from NPU to GPU if it gets warm

### Termux download keeps failing
- Resume interrupted downloads with `wget -c <url>`
- Keep Termux in the foreground and disable battery optimization for it
- Use a strong Wi-Fi connection; mobile data may drop on files this large

## Safety & Privacy

- **No data leaves the device by default**: INTERNET permission is only used for optional Wikipedia web search. All LLM inference is local.
- **No background activity**: App fully terminates when PiP is closed
- **No persistent storage**: Screenshots are held in memory only
- **No analytics/telemetry**: Zero external communication

## License

Private use only. Not for redistribution.

## Version History

### v1.5.1 (2026-04-15)
- WEB toggle (`🌐`) is now visible only in `TXT` mode; hidden in `VIS` mode
- Automatically disables web search when entering `VIS` mode to prevent accidental queries
- Web search on/off state is preserved when switching back to `TXT` mode

### v1.5 (2026-04-13)
- Migrated from Tavily API to Wikipedia API (`WikipediaSearchService.kt`)
  - No API key required; always available when internet is present
  - Fetches full article text via MediaWiki `extracts` API
  - Injects full article into LLM prompt with critical formatting instruction
- Removed `SmartSearchPipeline.kt`, `TavilySearchService.kt`, and credit tracking UI
- Simplified web search path: single Wikipedia fetch → local LLM inference

### v1.4 (2026-04-13)
- Added `SmartSearchPipeline` — 3-stage hybrid web search (Tavily)
- Added file-based debug logging to `GhostModels/debug_log.txt`

### v1.3 (2026-04-13)
- Added Tavily web search integration (now removed in v1.5)

### v1.2 (2026-04-13)
- Added Visual/Text mode toggle in terminal header (`TXT` / `VIS`)
- **TEXT mode is default** — works without screenshot while LiteRT-LM vision API is broken
- Updated `InferenceEngine.kt` with `analyze()` supporting both text-only and visual modes
- Mode indicator in response area (`[TEXT MODE]` / `[VISUAL MODE - NO SCREENSHOT]`)
- Toggle persists for the session; HAL TTS works in both modes

### v1.1 (2026-04-13)
- Added Sherpa-ONNX Piper TTS integration (`PiperTTS.kt`) for HAL 9000 voice synthesis
- Replaced holotape thumbnail with morphing Play/HAL button in terminal header
- HAL 9000 red-eye pulse animation with staccato rhythm (short-short-long)
- Auto-parses `hal.onnx.json` config and generates `tokens.txt` on-device
- Added desktop conversion script (`scripts/convert_hal_model.py`) for ONNX metadata patching
- TTS lifecycle integrated into `ChatActivity`

### v1.0 (2024-XX-XX)
- Initial release
- MediaProjection screen capture
- LiteRT-LM local inference
- Hexagon NPU support with GPU fallback
- Floating PiP Compose UI
- Thermal monitoring
# Build Sat Apr 13 20:10:00 UTC 2026
