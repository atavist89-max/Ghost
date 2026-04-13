# Ghost v1.0

> ⚠️ **PLACEHOLDER IMPLEMENTATION** ⚠️  
> This is a placeholder UI implementation waiting for the **LiteRT-LM API migration** from `litert-support-api:0.10.0` to `litertlm:0.11.0` for multimodal vision support. The current version demonstrates the visual design, interaction patterns, and mascot behaviors, but uses stub responses instead of actual on-device LLM inference with image input. The production API integration is pending Maven availability of `litertlm` with vision capabilities.

---

## What is Ghost?

Ghost is a privacy-first, on-demand screen analysis assistant for Samsung Galaxy S25+ (Android 16 / One UI 8.0). Triggered by double-tapping the Side Key, it captures your screen and provides instant AI-powered insights through a floating Pip-Boy-style Picture-in-Picture window—completely offline.

### Key Features

- **Iris Mechanical Mascot**: Bracket-shaped mechanical eyes with cursor-tracking pupils, mechanical servo LED-bar eyebrows, and 7 expressive states (IDLE, LISTENING, FOCUSED, THINKING, ANALYZING, SUCCESS, CONFUSED)
- **Pip-Boy Terminal Interface**: Compact 260dp×380dp wrist-mounted industrial terminal with heavy CRT scanlines, VT323 font, metallic bolts, and phosphor green glow
- **HAL 9000 Voice Synthesis**: Piper TTS integration with a morphing Play/HAL button that pulses in HAL's iconic staccato rhythm (short-short-long)
- **Visual / Text Mode Toggle**: Switch between vision (screenshot + text) and text-only assistant modes. **TEXT mode is default** while LiteRT-LM multimodal support is pending.
- **Zero Network Access**: All processing happens locally on your device's NPU
- **Zero Data Retention**: Screenshots exist only in memory, never stored
- **Zero Background Drain**: No services running when closed—trigger only when needed

---

## Quick Start

### Build from Source

```bash
cd ghost
./gradlew assembleRelease
```

### Install

```bash
adb install app/build/outputs/apk/release/app-release-unsigned.apk
```

### First Launch

1. Grant **Storage** permission (for model file access)
2. Enable **Ghost Accessibility Service** in Settings
3. Double-tap the **Side Key** to activate
4. Type `>` followed by your command in the terminal line

---

## How It Works

### Trigger Flow

1. **Double-tap Side Key** → Accessibility Service captures silent screenshot
2. **Iris awakens** → Mechanical eyes transition from IDLE to LISTENING
3. **Type your command** → Pupils track cursor; brackets morph to FOCUSED state
4. **Press ⏎ Execute** → Iris enters THINKING/ANALYZING with scanning animations
5. **Receive response** → SUCCESS state with satisfying checkmark pupils

### Iris Expression States

| State | Visual | Trigger |
|-------|--------|---------|
| **IDLE** | Slow mechanical blink, breathing scale, brows horizontal/dim | App open, awaiting input |
| **LISTENING** | Pupils track cursor X position, brows raised 15° with cursor-driven asymmetry | User typing |
| **FOCUSED** | Vertical line pupils, sharpened brackets, brows angled down 20° (intense V) | Paused typing |
| **THINKING** | Sweeping scan animation, tilted brackets, left brow raised 30° (quizzical) | Execute pressed |
| **ANALYZING** | Solid bar pupils, rapid glow pulse, brows low and serious (-15°) | Inference active |
| **SUCCESS** | Checkmark ✓ pupils, satisfied nod, brows raised 25° with bright pulse | Response complete |
| **CONFUSED** | Question mark ?, rapid 3× blink, brows wave alternately in perplexed motion | Error state |

### Pip-Boy Terminal UI

| Element | Description |
|---------|-------------|
| **Window** | 260dp × 380dp, anchored top-right (120dp from top) |
| **Frame** | Industrial housing with 4 metallic bolt heads, 2dp square corners |
| **Font** | VT323 terminal font, 24sp for body text |
| **CRT Effects** | 40% opacity scanlines, vignette darkening, phosphor bloom |
| **Header** | 40×24dp Iris with servo eyebrows + tabs `[VISUAL] [DATA] [STAT]` |
| **Mode Toggle** | `TXT` / `VIS` button next to Iris. TXT highlighted by default |
| **Play/HAL Button** | Morphing terminal play button (⏵) ↔ pulsing red HAL 9000 eye |
| **Terminal** | Line-numbered response area (01, 02, 03...) + flat `>` command line |
| **Cursor** | Blinking block `█` cursor |

---

## Project Structure

```
ghost/
├── app/src/main/java/com/ghost/app/
│   ├── GhostActivity.kt          # Entry point & orchestration
│   ├── ChatActivity.kt           # Pip-Boy overlay UI
│   ├── GhostAccessibilityService.kt  # Silent screenshot capture
│   ├── ui/
│   │   ├── IrisView.kt           # Mechanical eye mascot (Canvas)
│   │   ├── GhostInterface.kt     # Pip-Boy terminal Compose UI
│   │   ├── GhostWindowManager.kt # Window management
│   │   └── theme/                # Terminal colors & VT323 typography
│   └── inference/
│       ├── InferenceEngine.kt    # LiteRT-LM integration (pending API)
│       └── PiperTTS.kt           # HAL 9000 voice synthesis (ONNX placeholder)
├── app/src/main/res/font/
│   ├── vt323_regular.ttf         # VT323 terminal font
│   └── xanti_typewriter_regular.ttf  # Legacy typewriter font
└── build.gradle.kts
```

---

## Technical Specifications

| Spec | Value |
|------|-------|
| **Target** | Android 16 (API 36) / One UI 8.0 |
| **Architecture** | arm64-v8a |
| **PiP Window** | 260dp × 380dp, right-edge anchored, 120dp from top |
| **Font** | VT323 terminal font (24sp body) |
| **Colors** | Phosphor green `#39FF14` on gunmetal `#0A0F0A` |
| **Animation** | Spring physics (stiffness 300, damping 0.8) |
| **LLM** | Gemma 4 E2B via LiteRT-LM (pending API migration) |

---

## Placeholder Notice

This build demonstrates the complete visual and interaction design but uses placeholder responses. The production version is waiting for:

### Pending API Migration
- [ ] **LiteRT-LM Maven availability** — `litertlm:0.11.0` with multimodal vision
- [ ] **API migration** — Replace `litert-support-api:0.10.0` imports with `litertlm`
- [ ] **Class migration** — `LlmInference` → `Engine` and `Conversation`
- [ ] **Method migration** — `generateAsync(prompt)` → `sendMessage(Contents.of(Content.ImageFile(tempFile), Content.Text(query)))`
- [ ] **Bitmap handling** — Save screenshot to temp file for vision model input
- [ ] **Gemma 4 E2B vision model** — Multimodal understanding of screenshots
- [ ] **Hexagon NPU acceleration** — Hardware-accelerated inference
- [x] **Sherpa-ONNX Android** — Piper TTS inference for HAL 9000 voice (requires desktop model conversion)
- [ ] **ONNX Runtime Mobile** — Direct Piper TTS inference without conversion

### Production Ready
The UI, animations, Iris mascot behaviors, Pip-Boy terminal mechanics, PiP window handling, AccessibilityService, and model path handling are complete and will remain unchanged.

---

## Implementation Instructions (When API is Released)

When `litertlm:0.11.0` (or latest available) is released on Maven, follow these steps:

### 1. Update `build.gradle.kts`

```kotlin
// BEFORE:
implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")

// AFTER (check Maven for latest version):
implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")
```

### 2. Update `InferenceEngine.kt`

**Import changes:**
```kotlin
// BEFORE:
import com.google.ai.edge.litert.*

// AFTER:
import com.google.ai.edge.litertlm.*
```

**Class changes:**
```kotlin
// BEFORE:
private var llmInference: LlmInference? = null

// AFTER:
private var engine: Engine? = null
private var conversation: Conversation? = null
```

**Initialization changes:**
```kotlin
// BEFORE:
val options = LlmInference.Options.builder()
    .setModelPath(modelPath)
    .setPreferredBackend(LlmInference.Backend.CPU) // or GPU, NPU
    .build()
llmInference = LlmInference.create(context, options)

// AFTER:
engine = Engine.create(context)
conversation = engine?.newConversation(modelPath)
```

**Inference changes:**
```kotlin
// BEFORE (text-only):
llmInference?.generateAsync(prompt)

// AFTER (multimodal with image):
val tempFile = saveBitmapToTempFile(bitmap)
val contents = Contents.of(
    Content.ImageFile(tempFile),
    Content.Text(query)
)
conversation?.sendMessage(contents)
```

### 3. Add Bitmap-to-File Helper

```kotlin
private fun saveBitmapToTempFile(bitmap: Bitmap): File {
    val tempFile = File.createTempFile("screenshot", ".jpg", context.cacheDir)
    FileOutputStream(tempFile).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
    }
    return tempFile
}
```

### 4. Add Cleanup

Delete temp file after inference:
```kotlin
.onComplete { 
    tempFile.delete()
}
.onError { 
    tempFile.delete()
}
```

### 5. Keep Unchanged

- All UI code (`GhostInterface.kt`, `IrisView.kt`, `ChatActivity.kt`)
- PiP logic (`GhostWindowManager.kt`)
- AccessibilityService (`GhostAccessibilityService.kt`)
- Model path handling (`GhostPaths.kt`)

---

## Visual vs Text Mode

Ghost now supports both visual (multimodal) and text-only inference:

| Mode | Behavior | Default |
|------|----------|---------|
| **TEXT** | Pure text assistant. No screenshot required. Works immediately. | ✅ Yes |
| **VISUAL** | Sends screenshot to model for image analysis. Requires valid bitmap. | Manual toggle |

### Why TEXT is default
The vision API in LiteRT-LM `0.10.0` is non-functional for multimodal input. TEXT mode lets Ghost work as a fully capable text-only assistant today. When `0.11.0` fixes vision support, tap the toggle to switch to VIS mode.

### UI indicator
The terminal response area displays:
- `[TEXT MODE]` — when in text mode
- `[VISUAL MODE - NO SCREENSHOT]` — when visual mode is on but no bitmap is available

---

## HAL 9000 Voice Synthesis (Piper TTS)

Ghost now integrates **Sherpa-ONNX** for local Piper TTS inference.

### Prerequisites

Before HAL can speak, the raw Piper model must be **converted**. You can do this on a desktop machine **or directly on your phone via Termux**.

#### Option A: Desktop Conversion

1. **Run the conversion script** (requires Python):
   ```bash
   cd /path/to/Ghost
   pip install onnx==1.17.0
   python3 scripts/convert_hal_model.py /path/to/your/model/dir
   ```

2. **Download `espeak-ng-data`**:
   ```bash
   cd /path/to/your/model/dir
   wget https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2
   tar xf espeak-ng-data.tar.bz2
   ```

3. **Copy to Android device** next to the Gemma model:
   - `hal.onnx` (patched with metadata)
   - `hal.onnx.json`
   - `tokens.txt` (auto-generated by script)
   - `espeak-ng-data/` directory

#### Option B: Google Colab on Your Phone (No Desktop Needed)

If you don't have a PC, use **Google Colab** in your phone's web browser. It comes with `onnx` pre-installed.

1. Open **Chrome** and go to: https://colab.research.google.com
2. Tap **"New notebook"**
3. Run these two cells:

**Cell 1 — Install dependency:**
```python
!pip install onnx==1.17.0
```

**Cell 2 — Upload files:**
```python
from google.colab import files
uploaded = files.upload()  # Upload hal.onnx and hal.onnx.json
```

**Cell 3 — Convert:**
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
print("Conversion complete!")
files.download("hal.onnx")
files.download("tokens.txt")
```

4. Download the patched `hal.onnx` and `tokens.txt` to your phone.
5. In Termux, download `espeak-ng-data`:
   ```bash
   cd /storage/emulated/0/Download/GhostModels
   wget https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2
   tar xf espeak-ng-data.tar.bz2
   rm espeak-ng-data.tar.bz2
   ```
6. Ensure `hal.onnx`, `hal.onnx.json`, `tokens.txt`, and `espeak-ng-data/` are all in `/storage/emulated/0/Download/GhostModels/`.

#### Option C: Termux (Advanced — Often Fails)

> **Warning:** `pip install onnx` must compile C++ extensions. This **almost always fails on Android/Termux**. Only try this if Options A and B are impossible.

```bash
pkg update
pkg install -y python wget tar clang cmake make python-numpy python-pip
# The next line usually fails:
pip install --no-cache-dir onnx==1.17.0
```

If the pip install succeeds, continue with the script from Option A. If it fails, you **must** use Option B (Google Colab).

### How it works

- Tap the **⏵** button → it morphs into a pulsing red **HAL 9000 eye**
- Sherpa-ONNX runs inference on a background thread
- Generated audio is saved as a WAV file and played via `MediaPlayer`
- Tap again to stop playback

### Why conversion is needed

Sherpa-ONNX requires the ONNX file to contain a `"comment": "piper"` metadata property so it routes inference through the correct VITS code path. The conversion script also generates `tokens.txt` from the JSON `phoneme_id_map`. This cannot be done on-device because Android lacks the ONNX Python library.

---

## License

Private use only. Not for redistribution.
