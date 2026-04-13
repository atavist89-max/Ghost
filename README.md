# Ghost v1.0

> ⚠️ **PLACEHOLDER IMPLEMENTATION** ⚠️  
> This is a placeholder UI implementation until the correct API is released. The current version demonstrates the visual design, interaction patterns, and mascot behaviors, but uses stub responses instead of actual LLM inference. The production API integration is pending final SDK release.

---

## What is Ghost?

Ghost is a privacy-first, on-demand screen analysis assistant for Samsung Galaxy S25+ (Android 16 / One UI 8.0). Triggered by double-tapping the Side Key, it captures your screen and provides instant AI-powered insights through a floating Picture-in-Picture window—completely offline.

### Key Features

- **Iris Mechanical Mascot**: Bracket-shaped mechanical eyes with cursor-tracking pupils and 7 expressive states (IDLE, LISTENING, FOCUSED, THINKING, ANALYZING, SUCCESS, CONFUSED)
- **Cyberpunk PiP Interface**: Phosphor green CRT aesthetic with scanline effects, mechanical typography, and spring-physics animations
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
4. Ask Ghost anything about your current screen

---

## How It Works

### Trigger Flow

1. **Double-tap Side Key** → Accessibility Service captures silent screenshot
2. **Iris awakens** → Mechanical eyes transition from IDLE to LISTENING
3. **Type your question** → Pupils track cursor; brackets morph to FOCUSED state
4. **Press Send** → Iris enters THINKING/ANALYZING with scanning animations
5. **Receive response** → SUCCESS state with satisfying checkmark pupils

### Iris Expression States

| State | Visual | Trigger |
|-------|--------|---------|
| **IDLE** | Slow mechanical blink, breathing scale | App open, awaiting input |
| **LISTENING** | Pupils track cursor X position | User typing |
| **FOCUSED** | Vertical line pupils, sharpened brackets | Paused typing |
| **THINKING** | Sweeping scan animation, tilted brackets | Send pressed |
| **ANALYZING** | Solid bar pupils, rapid glow pulse | Inference active |
| **SUCCESS** | Checkmark ✓ pupils, satisfied nod | Response complete |
| **CONFUSED** | Question mark ?, rapid 3× blink | Error state |

---

## Project Structure

```
ghost/
├── app/src/main/java/com/ghost/app/
│   ├── GhostActivity.kt          # Entry point & orchestration
│   ├── ChatActivity.kt           # PiP overlay UI
│   ├── GhostAccessibilityService.kt  # Silent screenshot capture
│   ├── ui/
│   │   ├── IrisView.kt           # Mechanical eye mascot (Canvas)
│   │   ├── GhostInterface.kt     # Main PiP Compose UI
│   │   ├── GhostWindowManager.kt # Window management
│   │   └── theme/                # Cyberpunk colors & typography
│   └── inference/
│       └── InferenceEngine.kt    # LiteRT-LM integration
├── app/src/main/res/font/
│   └── xanti_typewriter_regular.ttf  # Typewriter response font
└── build.gradle.kts
```

---

## Technical Specifications

| Spec | Value |
|------|-------|
| **Target** | Android 16 (API 36) / One UI 8.0 |
| **Architecture** | arm64-v8a |
| **PiP Window** | 340dp × 600dp, right-edge anchored |
| **Font** | Xanti Typewriter (responses), Monospace (UI) |
| **Colors** | Phosphor green `#39FF14` on gunmetal `#0A0F0A` |
| **Animation** | Spring physics (stiffness 300, damping 0.8) |
| **LLM** | Gemma 4 E2B via LiteRT-LM (placeholder) |

---

## Placeholder Notice

This build demonstrates the complete visual and interaction design but uses placeholder responses. The production version will integrate:

- [ ] Actual LiteRT-LM inference engine
- [ ] Gemma 4 E2B model loading
- [ ] Hexagon NPU acceleration
- [ ] Streaming token generation

The UI, animations, Iris mascot behaviors, and PiP mechanics are production-ready.

---

## License

Private use only. Not for redistribution.
