# PhotonLab — Kotlin Multiplatform Photo Editor

## Project Overview
PhotonLab is a fast, non-destructive photo editor targeting Android and Linux/Windows desktop. Both platforms share the same domain model and edit pipeline logic; UI is platform-specific (Jetpack Compose on Android, Compose Multiplatform on desktop).

---

## Module Structure

```
photonlab/
├── app/                    Android module (Jetpack Compose, AGSL)
├── desktop/                Compose Desktop module (Linux + Windows, LWJGL/GLSL)
├── build_deb.sh            Linux .deb builder + post-processing
└── CLAUDE.md
```

---

## Tech Stack

| Area | Android | Desktop |
|---|---|---|
| Language | Kotlin | Kotlin |
| UI | Jetpack Compose + Material 3 | Compose Multiplatform + Material 3 |
| Rendering | AGSL RuntimeShader (API 33+) / RenderScript Compat | LWJGL/GLSL offscreen OpenGL / CPU fallback |
| Architecture | MVVM — ViewModel + StateFlow | MVVM — ViewModel + StateFlow |
| DI | Hilt | — |
| Build tool | Gradle Kotlin DSL | Gradle Kotlin DSL + jpackage |
| Package format | APK / AAB | `.deb` (Linux), `.exe`/`.msi` (Windows) |

---

## Android Module (`app/`)

### Source layout
```
app/src/main/java/com/photonlab/
├── ui/
│   ├── editor/          # EditorScreen, EditorViewModel, CropScreen
│   ├── components/      # AdjustmentSlider, ToolPanel
│   └── theme/           # Material 3 Neon Night theme
├── domain/
│   ├── model/           # EditState, LutFile, NormalizedRect
│   └── EditHistoryManager
├── data/
│   ├── lut/             # LutParser (.cube + HaldCLUT)
│   └── repository/      # LutRepository
└── rendering/
    └── EditPipeline     # AGSL RuntimeShader chain
```

### Rendering pipeline (Android)
```
Source Bitmap
    └─► EditPipeline (AGSL RuntimeShader chain)
            ├── Pass 1: Exposure + Luminosity
            ├── Pass 2: Contrast + Highlights + Shadows
            ├── Pass 3: Saturation + Vibrance + Temperature + Tint
            ├── Pass 4: Sharpening + Noise/Grain
            ├── Pass 5: LUT (3D tetrahedral interpolation)
            └── Pass 6: Frame/border compositing
```
- API < 33: RenderScript Compat fallback for shader-less operations.
- Preview at screen resolution; export at full source resolution.

### Permissions
```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />   <!-- API 33+ -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```

---

## Desktop Module (`desktop/`)

### Source layout
```
desktop/src/main/kotlin/com/photonlab/
├── Main.kt                  # application{} entry point; WM_CLASS, drag-and-drop, icon
├── ui/
│   └── editor/
│       ├── EditorScreen.kt  # Full UI — top bar, image area, tool panel, dialogs
│       └── EditorViewModel.kt  # State + business logic
├── domain/
│   └── model/               # EditState, DateImprintSettings, LayoutMode, …
├── rendering/
│   ├── EditPipeline.kt      # Orchestrates GPU/CPU rendering passes
│   ├── GlslToneRenderer.kt  # LWJGL offscreen OpenGL tone rendering
│   └── DateImprintProcessor.kt  # Film-camera date stamp (AWT Graphics2D)
└── platform/
    ├── DesktopBitmap.kt     # Wraps java.awt.image.BufferedImage (TYPE_INT_ARGB)
    ├── DesktopSaveManager.kt  # JPEG export via ImageIO
    ├── DesktopFilePicker.kt  # JFileChooser-based file picker
    └── DesktopPreferences.kt  # Persistent key-value store (java.util.prefs)
```

### Two-phase preview rendering
```
Source Bitmap (full resolution — no downsampling on load)
    │
    ├─► Quick preview (512px) — rendered immediately on every param change
    │
    └─► Adaptive full preview — rendered after 250ms debounce
            Resolution = 1920px × currentZoom (zoom changes trigger re-render)
```

### Layout modes
`LayoutMode` enum stored in `DesktopPreferences` (`layout_mode` key):
- `BOTTOM` (default) — image fills screen, tool panel overlays at the bottom
- `SIDE` — image left, editor panel right; divider draggable horizontally

Panel sizes are resizable via drag handles (`detectVerticalDragGestures` / `detectHorizontalDragGestures`).

### Export queue
- `exportChannel: Channel<ExportJob>(UNLIMITED)` — single consumer coroutine on `Dispatchers.IO`
- `pendingExports: AtomicInteger` — incremented at **send time** (not at consume time), so it accurately reflects all queued + in-progress jobs
- On close with pending exports: dialog offers **Continue in background** / **Wait** / **Close anyway**
- "Continue in background": sets `backgroundExporting = true` → `Window(visible = false)` in `Main.kt`; when counter reaches 0, `closeWindow = true` → `exitApplication()`

### Linux packaging (`build_deb.sh`)
1. Runs `./gradlew :desktop:packageDeb` (jpackage, Zulu JDK 21 required at `~/jdk/zulu21…`)
2. Post-processes the `.deb` with `dpkg-deb -R / --build`:
   - Adds `StartupWMClass=photonlab` to the `.desktop` file
   - Adds `xdg-icon-resource install --novendor` to `postinst` / `postrm`
   - Adds AppStream metadata (`.appdata.xml`) + cached 64×64 icon for GNOME Software
3. Output: `dist/main/deb/photonlab_<version>_amd64.deb`

### Linux icon / taskbar
Three-layer fix to ensure correct icon everywhere:
1. `System.setProperty("sun.awt.app.class", "photonlab")` before AWT init → X11 WM_CLASS
2. `StartupWMClass=photonlab` in `.desktop` → GNOME taskbar matching
3. `ProcessHandle`-based icon load from installed path (`/opt/photonlab/lib/photonlab.png`) with classpath fallback; also sets `java.awt.Taskbar.iconImage`

### Build version
Current: **2.0.0** — set in `desktop/build.gradle.kts` as `packageVersion = "2.0.0"`.

---

## Shared Domain Model

`desktop/src/main/kotlin/com/photonlab/domain/model/`:

| File | Contents |
|---|---|
| `EditState.kt` | All adjustment parameters (exposure, contrast, LUT ref, date imprint settings, …) |
| `DateImprintSettings.kt` | `DateImprintStyle` (18 formats), `DateImprintColor`, `DateImprintFont`, `DateImprintPosition` |
| `LutFile.kt` | LUT reference (path + parsed data) |
| `GridMode.kt` | None / Rule-of-thirds / Golden ratio |

---

## Edit Functions

| Function | Range | Notes |
|---|---|---|
| Exposure | −5 to +5 EV | Linear light multiplier |
| Luminosity | −100 to +100 | Brightness offset |
| Contrast | −100 to +100 | S-curve |
| Highlights / Shadows | −100 to +100 | Tone-range selective |
| Saturation | −100 to +100 | HSL channel |
| Vibrance | −100 to +100 | Protects already-saturated colors |
| Temperature | −100 to +100 | Cool → Warm |
| Tint | −100 to +100 | Green → Magenta |
| Sharpening | 0 to 100 | |
| Noise / Grain | −100 to +100 | Negative = denoise |
| LUT | file reference | 3DLUT `.cube` or HaldCLUT PNG |
| Rotation | 0 / 90 / 180 / 270° + free −45°…+45° | |
| Crop | rect + optional ratio | Non-destructive |
| Frame | 0–30% width, custom color | Optional aspect ratio target |
| Date imprint | 18 style formats | Size = % of longest side, min 6px, no upper cap |

---

## Coding Conventions

- All UI in Jetpack Compose / Compose Multiplatform; zero XML layouts.
- `StateFlow<UiState>` for all UI state; single source of truth.
- Coroutines + `Dispatchers.Default` for image processing; `Dispatchers.IO` for file I/O.
- Never block the main thread.
- Intermediate `DesktopBitmap` objects must be `.recycle()`d after use to avoid heap pressure.
- `DesktopBitmap.recycle()` calls `BufferedImage.flush()`.
- Desktop theme is always dark (`DarkColorScheme`); Android follows system theme.
