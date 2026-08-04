# NOTICE — foss video editor

This source set (`app/src/foss/`) adds an in-app video editor to Goodwy Gallery.

## Provenance & licensing

- All Kotlin/XML code in this directory and in `app/src/foss/kotlin/` is an
  **original implementation** written for this project and released under the
  project's existing license, **GPLv3** (see `/LICENSE`).
- The **feature set and UX flow** (tool categories, aspect-ratio toggles,
  undo/redo, save-as/overwrite flow) were designed for parity with
  [Simple Mobile Tools — Simple Gallery](https://github.com/SimpleMobileTools/Simple-Gallery)
  (GPLv3). Upstream's actual video-editor screen was a thin wrapper around the
  proprietary [img.ly VideoEditor SDK](https://img.ly/) and could not be reused
  in this free flavor; **no upstream source code and no img.ly code/assets were
  copied** — only behavioral patterns, which were re-implemented.
- The 16 color-filter presets reuse the parameter data (tone-curve knots,
  brightness/contrast/saturation values) of the Zomato `photofilters` FilterPack
  that is **already bundled with this app** (`app/src/main/kotlin/com/zomato/photofilters`),
  re-expressed for GPU rendering.
- The sticker "Shapes" set and overlay presets ("light leaks") are drawn
  procedurally by `OverlayBitmapFactory` — no third-party artwork is shipped.

## Runtime dependencies introduced by this feature

| Library | License |
|---|---|
| androidx.media3:media3-transformer | Apache-2.0 |
| androidx.media3:media3-effect | Apache-2.0 |
| androidx.media3:media3-ui | Apache-2.0 |

All are declared **foss-flavor only** (`fossImplementation`) and are free/open
source; no proprietary dependency (no img.ly, no Google Play Services) was
introduced.
