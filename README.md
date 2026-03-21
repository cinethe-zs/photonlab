# PhotonLab

A fast, non-destructive photo editor with a Neon Night aesthetic. Available on **Android** and **Linux / Windows desktop**. Built entirely with Kotlin and Jetpack Compose — no native libraries, no cloud dependencies.

---

## Platforms

| Platform | UI toolkit | Rendering |
|---|---|---|
| Android | Jetpack Compose + Material 3 | AGSL RuntimeShader (API 33+); RenderScript Compat fallback |
| Linux / Windows | Compose Multiplatform (Desktop) | LWJGL/GLSL offscreen OpenGL; CPU fallback |

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
- Import `.cube` (3D LUT) or HaldCLUT PNG files
- Applied via tetrahedral interpolation (GPU-accelerated)
- One LUT at a time; easily removed

### Transform
- 90° rotation (CW / CCW)
- Free rotation: −45° to +45° in 0.1° steps
- Non-destructive crop with free or ratio-locked rectangle

### Frame / Border
- White or custom-color border
- Configurable width (0–30% of shorter side)
- Optional aspect ratio target (e.g. 1:1, 4:5, 16:9)

### Date Imprint
- Overlay a date stamp in classic film-camera style
- 18 format presets (Classic, ISO 8601, EU/US numeric, date+time, time-only…) via dropdown
- 6 colors, 5 fonts, 5 positions
- Size as a percentage of the longest image dimension (no upper cap)
- Configurable opacity, glow, and blur

### Batch Workflow
- Open multiple images at once
- Navigate per-image with swipe or arrows — edits preserved in memory
- **Export current** or **Export all** — processing queued in the background
- On close with pending exports: choose **Continue in background** (window hides, process exits when done), **Wait**, or **Close anyway**
- Save a preset and optionally auto-apply it to every image in the batch

### Histogram
- Toggleable RGB histogram overlay
- Logarithmic scale for shadow/highlight detail

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

## Desktop-specific features (Linux / Windows)

- **Two layout modes** (Settings): bottom panel (default) or side panel
- Resizable editor panel — drag the divider between image and controls
- Zoom in/out with scroll wheel or +/− keys; pan by dragging
- Adaptive preview resolution: `1920px × zoom factor`, updated after 250 ms debounce
- Drag-and-drop images onto the window
- Always dark theme
- Keyboard shortcuts: +/− to zoom

---

## Tech Stack

| Area | Android | Desktop |
|---|---|---|
| Language | Kotlin | Kotlin |
| UI | Jetpack Compose + Material 3 | Compose Multiplatform + Material 3 |
| Rendering | AGSL RuntimeShader | LWJGL/GLSL + CPU fallback |
| Architecture | MVVM — ViewModel + StateFlow | MVVM — ViewModel + StateFlow |
| DI | Hilt | — |
| Image loading | Coil 3 | Java ImageIO |
| Build | Gradle Kotlin DSL | Gradle Kotlin DSL + jpackage |

---

## Requirements

### Android
- Android 8.0+ (API 26)
- AGSL hardware acceleration on API 33+
- Permissions: `READ_MEDIA_IMAGES` (API 33+) or `READ_EXTERNAL_STORAGE` (API ≤ 32)

### Linux
- x86-64, any modern distribution
- Install the `.deb` package (tested on Ubuntu/Debian/GNOME)
- Java runtime bundled — no separate JDK needed

### Windows
- x86-64, Windows 10+
- Install the `.exe` / `.msi` package
- Java runtime bundled

---

## Build

### Android

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (requires signing config)
./gradlew assembleRelease
```

### Linux .deb

Requires [Zulu JDK 21](https://www.azul.com/downloads/) at `~/jdk/zulu21.42.19-ca-jdk21.0.7-linux_x64`.

```bash
bash build_deb.sh
# Output: dist/main/deb/photonlab_2.0.0_amd64.deb
```

### Windows .exe / .msi

```powershell
gradlew.bat :desktop:packageExe   # or packageMsi
```

---

## Project Structure

```
photonlab/
├── app/                          # Android module
│   └── src/main/java/com/photonlab/
│       ├── ui/editor/            # EditorScreen, EditorViewModel, CropScreen
│       ├── domain/model/         # EditState, LutFile, NormalizedRect
│       ├── data/lut/             # LUT parser (.cube + HaldCLUT)
│       └── rendering/            # AGSL EditPipeline
├── desktop/                      # Compose Desktop module (Linux + Windows)
│   └── src/main/kotlin/com/photonlab/
│       ├── Main.kt               # Application entry point
│       ├── ui/editor/            # EditorScreen, EditorViewModel, CropScreen
│       ├── domain/               # Shared model (EditState, DateImprintSettings…)
│       ├── rendering/            # EditPipeline, GlslToneRenderer, DateImprintProcessor
│       └── platform/             # DesktopBitmap, DesktopSaveManager, DesktopFilePicker
├── build_deb.sh                  # Linux .deb build + post-processing script
└── README.md
```

---

## Rendering Pipeline

```
Source Bitmap (full resolution, no downsampling)
    │
    ├─► Quick preview (512px) — rendered immediately on every change
    │
    └─► Adaptive preview (1920px × zoom) — rendered after 250ms debounce
            │
            └─► EditPipeline
                    ├── Geometry: rotation + fine rotation + crop
                    ├── Tone:     exposure → luminosity → contrast → highlights → shadows
                    ├── Color:    saturation → vibrance → temperature → tint
                    ├── Detail:   sharpening → noise/grain
                    ├── LUT:      3D tetrahedral interpolation
                    ├── Frame:    border compositing
                    └── Date:     imprint overlay (text, glow, blur)
```

Export runs the pipeline at full source resolution on `Dispatchers.IO`.

---

## License

Personal / private project. Not licensed for redistribution.
