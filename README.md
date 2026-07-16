# Untrusted Translations

> A dark, mobile-first, computer-aided manga and comic translation editor for Android.

Untrusted Translations turns a phone into a focused comic translation workspace. Import a complete comic or one image, detect text on the current page, review the OCR and machine translation, correct the wording, then replace the original lettering on the image.

The app never changes pages by itself. **Only Save & Next advances to the next page.**

## What works

- Import PNG, JPEG, WebP, PDF, CBZ, and ZIP files through Android's system file picker.
- Extract archive pages safely and sort filenames naturally (`2.png` before `10.png`).
- Render PDFs into editable page images.
- Detect Latin, Japanese, Korean, or Chinese text with ML Kit OCR.
- Translate into every language supported by ML Kit's on-device translation API.
- Edit both incorrect OCR text and the translated result.
- Add missed text blocks or delete unwanted detections.
- Move and resize each text box with precise controls.
- Change font size, rotation, family, alignment, weight, italics, vertical layout, and color.
- Re-translate a single corrected text block without repeating OCR for the page.
- Replace detected lettering on the page while keeping the untouched original page.
- Undo and redo editor changes.
- Autosave projects and resume or delete them from the project library.
- Export an imported image as PNG, PDF as PDF, ZIP as ZIP, and CBZ as CBZ.
- Return to the import/project screen after exporting the final page.

## Workflow

1. Choose the OCR script, source language, and target language.
2. Import an image, PDF, CBZ, or ZIP.
3. Wait while the current page is detected and translated.
4. Tap **Editor** to review one text region at a time.
5. Correct the original OCR or translated wording.
6. Adjust the text box and lettering style, then apply it to the page.
7. Tap **Save & Next** only when you want to move forward.
8. On the final page, tap **Save & Exit** to export and return to your projects.

## Import and export

| Format | Import | Export |
| --- | --- | --- |
| PNG, JPEG, WebP | Yes | PNG |
| PDF | Yes | PDF |
| CBZ | Yes | CBZ |
| ZIP of images | Yes | ZIP |

CBR is not supported yet because RAR extraction has additional licensing and Android packaging considerations.

## Privacy and downloads

OCR and translation run on the Android device. The app needs internet access to download ML Kit language models the first time a language pair is used. Imported comics and saved projects stay in the app's private storage unless the user exports them.

## Current limitations

- Text removal uses a contrast mask and propagates surrounding texture into detected glyphs. It works best inside speech bubbles; detailed art behind stylized text can still need manual cleanup.
- Font size, rotation, weight, color, family, and vertical layout receive automatic starting estimates. These are heuristics, not exact source-font identification, and remain fully editable.
- OCR quality depends on scan quality, text direction, stylization, and the selected source script.
- Very large PDFs and archives are limited by available device memory and storage.
- Export is raster-based; editable text layers are preserved in the saved app project, not in exported PDF/CBZ files.

## Build

The repository includes the Gradle wrapper. Install Android SDK 36 and Java 17, then run:

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

## Architecture

- Kotlin and Jetpack Compose dark UI
- Android ViewModel screen and project state
- Storage Access Framework imports
- ML Kit text recognition and on-device translation
- Android `PdfRenderer`, `PdfDocument`, and Canvas rendering
- JSON project metadata plus private per-project page files
- Non-destructive originals with versioned rendered pages

## Roadmap

- [x] Gradle Android project and custom dark launcher icon
- [x] Image, PDF, CBZ, and ZIP import
- [x] Natural archive page ordering
- [x] Current-page OCR and on-device translation
- [x] Editable OCR and translation review flow
- [x] Manual Save & Next navigation
- [x] Text bounds and typography editor
- [x] Undo, redo, autosave, resume, and delete
- [x] Image, PDF, CBZ, and ZIP export
- [x] Masked, texture-aware local text removal
- [ ] Optional neural inpainting for highly detailed artwork
- [x] Automatic initial font/style estimation
- [x] Drag and resize text boxes directly inside the editor preview
- [ ] Glossary and translation-memory support
- [ ] Optional alternative OCR and translation providers
- [ ] Performance profiling for extremely long comics and webtoons
- [ ] Automated UI and device compatibility test suite

## Inspiration

The workflow is informed by [ImageTrans](https://www.basiccat.org/imagetrans/) and [BallonsTranslator](https://github.com/dmMaze/BallonsTranslator). This repository is an original Android implementation and does not copy their source code.

## License

See [LICENSE](LICENSE). Users are responsible for having the right to translate and redistribute the material they process. Machine-translated releases should be clearly labeled when they have not received experienced human proofreading.
