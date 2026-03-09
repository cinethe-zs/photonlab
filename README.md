# PhotonLab

A fast, non-destructive Android photo editor with a Neon Night aesthetic. Built entirely with Jetpack Compose and AGSL shaders — no native libraries, no cloud dependencies.

---

## Features

### Tone & Color
| Adjustment | Range | Notes |
|---|---|---|
| Exposure | −5 to +5 EV | Multiplier on linear light |
| Luminosity | −100 to +100 | Brightness offset |
| Contrast | −100 to +100 | S-curve |
| Highlights | −100 to +100 | Tone-range selective |
| Shadows | −100 to +100 | Tone-range selective |
| Saturation | −100 to +100 | HSL channel |
| Vibrance | −100 to +100 | Protects already-saturated colors |
| Temperature | −100 to +100 | Cool → Warm |
| Tint | −100 to +100 | Green → Magenta |

### Detail
| Adjustment | Range |
|---|---|
| Sharpening | 0 to 100 |
| Noise / Grain | −100 (denoise) to +100 (grain) |

### LUT
- Import `.cube` (3D LUT) or HaldCLUT PNG files from device storage
- LUTs applied via AGSL tetrahedral interpolation (hardware-accelerated)
- One LUT at a time; easily removed

### Transform
- 90° rotation (CW / CCW)
- Free rotation: −45° to +45° in 0.1° steps
- Non-destructive crop with free or ratio-locked rectangle

### Frame / Border
- White or custom-color border
- Configurable width (0–30% of shorter side)
- Optional aspect ratio target (e.g. 1:1, 4:5, 16:9)

### Batch Workflow
- Open multiple images at once
- Swipe left/right to navigate — per-image edits are preserved in memory
- Export queued in the background; jump to the next image immediately
- Save a preset and optionally auto-apply it to every subsequent image in the batch

### Histogram
- Toggleable RGB histogram overlay on the image
- Logarithmic scale for detail in dark/bright regions
- Uses a frame-free render so border pixels never contaminate the data

### Presets
- Save any set of adjustments as a named preset
- Choose which parameters to include per preset
- Apply to current image or auto-apply to entire batch

### History
- Full undo/redo with parameter-key compression (continuous drag = 1 history step)
- Jump to any point in history via the history dialog

### Compare
- Hold the image to see the unedited original (geometry-matched)

---

## Tech Stack

| Area | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Rendering | AGSL RuntimeShader (API 33+) |
| Architecture | MVVM — ViewModel + StateFlow |
| DI | Hilt |
| Image loading | Coil 3 |
| Build | Gradle Kotlin DSL |

---

## Requirements

- Android 8.0+ (API 26)
- AGSL hardware acceleration used automatically on API 33+ devices
- Permissions: `READ_MEDIA_IMAGES` (API 33+) or `READ_EXTERNAL_STORAGE` (API ≤ 32)
- No internet permission required

---

## Build

```bash
# Debug APK → app/build/outputs/apk/debug/photonlab-v1.0-debug.apk
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug

# Release APK (requires signing config in build.gradle.kts)
gradlew.bat assembleRelease
```

Output is saved to `Pictures/PhotonLab/` on the device via MediaStore.

---

## Project Structure

```
photonlab/
├── app/src/main/
│   ├── java/com/photonlab/
│   │   ├── ui/
│   │   │   ├── editor/          # EditorScreen, EditorViewModel, CropScreen
│   │   │   ├── components/      # AdjustmentSlider, ToolPanel
│   │   │   └── theme/           # Neon Night Material 3 theme
│   │   ├── domain/
│   │   │   ├── model/           # EditState, LutFile, NormalizedRect
│   │   │   └── EditHistoryManager
│   │   ├── data/
│   │   │   ├── lut/             # LutParser (.cube + HaldCLUT)
│   │   │   └── repository/      # LutRepository
│   │   └── rendering/
│   │       └── EditPipeline     # AGSL shader chain
│   └── res/
│       ├── drawable/            # Adaptive icon (Neon Night)
│       ├── raw/                 # AGSL shader sources
│       └── values/              # Strings, theme
└── README.md
```

---

## Rendering Pipeline

```
Source Bitmap (full resolution)
    │
    ├─► Quick preview (512px) — rendered immediately on every change
    │
    └─► Full preview (1280px) — rendered after 250ms debounce
            │
            └─► EditPipeline (AGSL RuntimeShader chain)
                    ├── Geometry: rotation + fine rotation + crop
                    ├── Tone:     exposure → luminosity → contrast → highlights → shadows
                    ├── Color:    saturation → vibrance → temperature → tint
                    ├── Detail:   sharpening → noise/grain
                    ├── LUT:      3D tetrahedral interpolation
                    └── Frame:    border compositing
```

Export runs the pipeline at full source resolution on `Dispatchers.IO`.

---

## License

Personal / private project. Not licensed for redistribution.
