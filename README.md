# Untrusted Translations

> A mobile-first, computer-aided manga and comic translation editor for Android.

Untrusted Translations is being built for translators who want automation without losing editorial control. Import a comic, detect its text, review the original and translated lines in a focused editor, correct the wording, then apply the finished lettering back onto the page.

The app never rushes you to the next page. **Only Save & Next advances the project.**

## The workflow

1. Import an image, PDF, CBZ, or ZIP archive.
2. Keep every page in natural filename order.
3. Detect text regions on the current page.
4. Tap **Open Editor** to review one text block at a time.
5. Compare the original OCR with the machine translation.
6. Correct wording, font size, position, rotation, color, and styling.
7. Apply the corrected text to the cleaned image.
8. Save freely, then tap **Save & Next** when the page is truly finished.

## Product principles

- **Human-reviewed by default** — machine translation is a draft, not the final word.
- **Mobile-first editing** — page preview and editor are separate full-screen experiences.
- **Non-destructive projects** — original pages, cleaned layers, text blocks, and edits stay separate.
- **Explicit navigation** — no automatic page changes while a translator is working.
- **Replaceable engines** — OCR, translation, detection, and inpainting providers are modular.
- **Private where possible** — on-device engines are preferred when quality and device limits allow.

## Import and export plan

| Format | Import | Export |
| --- | --- | --- |
| PNG, JPEG, WebP | Planned | Planned |
| PDF | Planned | Planned |
| CBZ | Planned | Planned |
| ZIP of images | Planned | Planned |
| Editable project | Planned | Planned |

CBR may be added later because RAR extraction brings extra licensing and platform considerations.

## Current prototype

The current Kotlin/Jetpack Compose prototype includes:

- project, ordered-page, text-block, bounds, and text-style models;
- a six-page demonstration project;
- a page preview with detected-region count;
- separate original and editable translation fields;
- font-size adjustment with a live text preview;
- Apply, Save, text-block navigation, and Save & Next actions;
- ML Kit dependencies for Latin, Japanese, Korean, and Chinese OCR;
- a custom visual system and native vector launcher icon.

The import button currently opens sample data. Real extraction, OCR execution, translation, inpainting, compositing, project persistence, and export are still under development.

## Technical direction

- Kotlin and Jetpack Compose
- Unidirectional screen state with Android ViewModel
- Storage Access Framework for user-selected files
- ML Kit as the first on-device OCR provider
- Provider interfaces for translation services
- Android Canvas/Compose rendering for editable lettering
- A layered project format so edits remain reversible

```text
Comic project
└── Ordered pages
    ├── Original image
    ├── Cleaned/inpainted image
    └── Text blocks
        ├── OCR source
        ├── Editable translation
        ├── Relative bounds and rotation
        ├── Removal mask
        └── Lettering style
```

## Build

The repository includes the Gradle wrapper.

```bash
./gradlew assembleDebug
```

Android SDK 36 and Java 21 are recommended. Cloud development is currently done in GitHub Codespaces on the `agent/android-mvp` branch.

## Roadmap

- [x] Android project and Gradle wrapper
- [x] Core project/page/text-block state
- [x] Translator review flow prototype
- [x] Explicit Save & Next navigation
- [x] Initial visual identity and launcher icon
- [ ] Real image import
- [ ] ZIP and CBZ extraction with natural page ordering
- [ ] PDF page rendering
- [ ] OCR execution and selectable language models
- [ ] Translation provider interface
- [ ] Text mask generation and inpainting
- [ ] Editable canvas bounds and typography
- [ ] Persistent project database
- [ ] Image, PDF, CBZ, and ZIP export
- [ ] Batch processing and long-webtoon support

## Inspiration

The product direction is informed by the workflows of [ImageTrans](https://www.basiccat.org/imagetrans/) and [BallonsTranslator](https://github.com/dmMaze/BallonsTranslator). This is an original Android implementation; it does not copy their source code.

## License

See [LICENSE](LICENSE). Users are responsible for having the right to translate and redistribute the source material they process. Machine-translated releases should be labeled clearly when they have not received experienced human proofreading.
