# PhotonLab — Android Photo Editor

## Project Overview
PhotonLab is a simple, fast Android photo editor focused on delivering real-time, non-destructive edits with a clean, accessible UI.

## Tech Stack
| Area | Choice | Notes |
|---|---|---|
| Language | Kotlin | Latest stable version |
| Min SDK | API 26 (Android 8.0) | ~95% device coverage |
| Target SDK | API 35 (Android 15) | Always keep up to date |
| UI Toolkit | Jetpack Compose + Material 3 | Declarative UI, system light/dark theme |
| Rendering | AGSL (Android Graphics Shading Language) | API 33+; fallback to RenderScript Compat for API 26-32 |
| Architecture | MVVM + Clean Architecture | ViewModel, StateFlow, Repository pattern |
| Build | Gradle Kotlin DSL (.kts) | Modern, type-safe build scripts |
| Image Loading | Coil 3 | Compose-native, async loading |
| DI | Hilt | Standard Android DI |

## Module Structure
```
photonlab/
├── app/
│   └── src/main/
│       ├── java/com/photonlab/
│       │   ├── ui/
│       │   │   ├── editor/          # Editor screen (main screen)
│       │   │   ├── components/      # Reusable Compose components
│       │   │   └── theme/           # Material 3 theme, colors, typography
│       │   ├── domain/
│       │   │   ├── model/           # EditState, LutFile, ExportConfig
│       │   │   └── usecase/         # ApplyEdits, ExportImage, ImportLut
│       │   ├── data/
│       │   │   ├── lut/             # LUT parser (3DLUT .cube, HaldCLUT PNG)
│       │   │   └── repository/      # EditRepository, LutRepository
│       │   └── rendering/
│       │       ├── EditPipeline     # Orchestrates shader passes
│       │       ├── shaders/         # AGSL shader sources
│       │       └── LutRenderer      # Applies LUT via AGSL or RenderScript
│       └── res/
│           ├── raw/                 # Bundled AGSL shader files (.agsl)
│           └── ...
└── CLAUDE.md
```

## Edit Functions
All edits are stored as parameters in an `EditState` data class. The original image is never modified.

| Function | Type | Parameter range | Notes |
|---|---|---|---|
| LUT | AGSL shader | LUT file reference | 3DLUT (.cube) and HaldCLUT (PNG); imported from device storage |
| Exposure | AGSL shader | -5.0 to +5.0 EV | Applied as multiplier on linear light |
| Luminosity | AGSL shader | -100 to +100 | Brightness offset |
| Contrast | AGSL shader | -100 to +100 | S-curve or linear scale |
| Saturation | AGSL shader | -100 to +100 | HSL saturation channel |
| Highlights | AGSL shader | -100 to +100 | Tone-range selective adjustment |
| Shadows | AGSL shader | -100 to +100 | Tone-range selective adjustment |
| Crop | Transform | Aspect ratio + rect | Non-destructive crop rect stored, applied on export |
| Rotate | Transform | 0 / 90 / 180 / 270 | Plus free rotation if needed |

## Rendering Pipeline
```
Source Bitmap
    └─► EditPipeline (AGSL RuntimeShader chain)
            ├── Pass 1: Exposure + Luminosity
            ├── Pass 2: Contrast + Highlights + Shadows
            ├── Pass 3: Saturation
            ├── Pass 4: LUT application (3D tetrahedral interpolation)
            └─► Preview Surface (Canvas / AndroidView)
```
- On devices API < 33: fall back to `RenderScript` compat library for shader-less operations; LUT applied via CPU lookup table.
- Preview is rendered at screen resolution for performance; export renders at full original resolution.

## Image Source
- **Gallery / Files only** — uses `ActivityResultContracts.GetContent` with `image/*` MIME type.
- Requires `READ_MEDIA_IMAGES` permission (API 33+) or `READ_EXTERNAL_STORAGE` (API 26–32).

## LUT Import
- User imports `.cube` files (3DLUT) or HaldCLUT PNG from device storage via file picker.
- Parsed and cached in app-internal storage for fast reload.
- No bundled LUTs; all LUTs are user-provided.

## Edit History (Undo/Redo)
- `EditHistoryManager` maintains a `List<EditState>` stack with a current index pointer.
- `undo()` decrements index, `redo()` increments, `push()` truncates forward history.
- Max stack depth: 50 states (configurable constant).

## Export
- Format: **JPEG only**, quality configurable (default 95).
- Output saved to `Pictures/PhotonLab/` via `MediaStore` (no WRITE_EXTERNAL_STORAGE needed on API 29+).
- Crop and rotation applied as final transform before encoding.

## UI / UX Principles
- **Single screen editor**: image preview takes the full screen; controls overlay at the bottom.
- **Bottom sheet tool panel**: swipeable categories (Tone, Color, LUT, Transform).
- **Slider controls**: each adjustment uses a horizontal slider with value label.
- **Tap to compare**: tap and hold on preview shows original.
- **Theme**: follows system light/dark (Material 3 Dynamic Color where supported).
- **No modal dialogs** for adjustments — everything inline.

## Key Dependencies (app/build.gradle.kts)
```kotlin
// Compose BOM
implementation(platform("androidx.compose:compose-bom:2024.xx.xx"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.activity:activity-compose:1.9.x")

// Lifecycle / ViewModel
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.x")

// Hilt
implementation("com.google.dagger:hilt-android:2.51.x")
kapt("com.google.dagger:hilt-compiler:2.51.x")

// Coil
implementation("io.coil-kt.coil3:coil-compose:3.x.x")

// RenderScript compat (for API < 33 fallback)
implementation("androidx.renderscript:renderscript-toolkit:0.1")
```

## Coding Conventions
- All UI in Jetpack Compose; zero XML layouts.
- `StateFlow` for all UI state; no `LiveData`.
- Coroutines + `Dispatchers.Default` for image processing; never block the main thread.
- Single `EditState` data class is the source of truth for all current adjustments.
- Shaders stored as `.agsl` files in `res/raw/`, loaded at runtime via `RuntimeShader`.
- All strings in `strings.xml`; no hardcoded UI strings.
- Use `@Preview` composables for all UI components.

## Permissions Required
```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />  <!-- API 33+ -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```
No internet permission needed.

## Out of Scope (v1)
- Batch processing
- Cloud sync
- Sharing to social networks
- Video editing
- Filters beyond LUT
- Healing / clone stamp
- Text overlays
