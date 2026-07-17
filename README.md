# Untrusted Translations

> A dark, mobile-first, computer-aided manga and comic translation editor for Android.

Untrusted Translations turns your phone into a focused comic translation workspace. Import a complete comic or a single image, detect text on the current page, review the OCR and machine translation against the original, correct the wording, then replace the original lettering on the image with a styled translation.

The app never changes pages by itself. **Only Save & Next advances to the next page.**

## Features

### Import
- Import single images (PNG, JPEG, WebP), multi-page PDFs, CBZ comic archives, and ZIP files.
- Import an entire folder of images directly from the file picker.
- Natural filename sorting (`page-02` before `page-10`).
- 1000-page limit per archive; each extracted page is checked for size and safety.

### Text detection (OCR)
| Engine | Quality | Cost |
|---|---|---|
| **Gemini Free** | Best — reads the whole page with context, ignores SFX. Requires a free Gemini API key. | Free (quota-based) |
| **RapidOCR** | Excellent — on-device neural OCR for Japanese, Korean, Chinese, and Latin. Downloadable model packs (~16–18 MB each). | Free (download required) |
| **Google ML Kit** | Good for clean print. Built-in, no download needed. | Free (always available) |

- Deep scan mode for difficult text.
- Sound effects are intentionally excluded from detection.
- Automatic sound-effect rejection by character repetition, rotation, and length heuristics.
- Overlapping detection merging with long-text deduplication.

### Translation
| Engine | Quality | Cost |
|---|---|---|
| **Gemini Free** | Context-aware, respects names and tone. Requires a free Gemini API key. | Free (quota-based) |
| **NLLB (offline)** | High-quality on-device neural translation. ~950 MB download, ARM64 only. | Free (download required) |
| **Google ML Kit** | Fast on-device, ~180 languages. | Free (always available) |
| **Google Translate (Unofficial)** | Experimental. No key needed. May stop working without notice. | Free (experimental) |
| **OpenAI API** | Connect your own paid key (gpt-5-mini). | Paid (your key) |
| **Claude API** | Connect your own paid key (claude-sonnet-4-5). | Paid (your key) |
| **Custom OpenAI-compatible** | Bring your own endpoint, key, and model name. | Paid (your server) |

### Editor
- Review one detected text region at a time.
- Edit incorrect OCR text directly.
- Machine-translated text is fully editable.
- Re-translate a single block without re-running OCR for the whole page.
- Adjust font, size, rotation, alignment, bold, italic, vertical layout, and text color.
- Add new text blocks from scratch with configurable background.
- Delete unwanted blocks.
- Undo/redo for all editor changes (50-state history).

### Page canvas
- Drag text boxes to reposition them directly on the image.
- Resize by dragging edge handles (left, top, right, bottom).
- Rotate with a dedicated rotation handle.
- Live preview of the translated text overlaid on the image.
- Automatic font-size scaling when text boxes are resized.
- The page re-renders with updated placement after each change.

### Text removal (inpainting)
- Contrast-based glyph detection using the speech bubble's border color as reference.
- Texture-aware fill propagates surrounding artwork into removed areas.
- Dilated removal mask covers anti-aliased glyph edges.
- No external ML model needed — runs on-device instantly.

### Lettering style estimation
- Automatically detects text color (black vs. white / dark vs. light).
- Estimates base font size from bounding-box height.
- Detects CJK vertical writing direction.
- Chooses between Sans, Condensed, and Casual font families.
- Detects bold weight from ink density.
- Estimates rotation from OCR corner points.

### Export
| Format | Import | Export |
|---|---|---|
| PNG, JPEG, WebP | Yes | PNG |
| PDF | Yes | PDF |
| CBZ | Yes | CBZ |
| ZIP of images | Yes | ZIP |

- Exported files go to `Downloads/Untitled Translations` (Android Q+).

### Projects
- Autosave with 300 ms debounce.
- Project library: resume or delete saved projects.
- JSON-based metadata + per-page rendered images stored in app-private storage.
- Non-destructive workflow — the original import is never overwritten.

### Security
- All API keys (Gemini, OpenAI, Claude, custom) are encrypted at rest using Android Keystore AES-GCM.
- Keys are stored per-user in private app preferences; the app ships with no bundled API key.
- Remove / switch account by deleting the stored key in AI settings.

## Workflow

1. Open the app — choose the OCR script, source language, and target language.
2. Tap **AI settings** to select your preferred OCR and translation providers. For Gemini, paste a free API key from Google AI Studio (keep billing disabled).
3. Import an image, PDF, CBZ, ZIP, or image folder.
4. Wait while the current page is detected and translated.
5. Tap **Editor** to review one text region at a time.
6. Correct the original OCR or translated wording.
7. Adjust the text box and lettering style, then tap **Replace original text on page**.
8. Tap **Save & Next** only when you want to move forward to the next page.
9. On the final page, tap **Save & Exit** to export the translated comic and return to the project library.

## Privacy

- OCR and translation run either on-device (ML Kit, RapidOCR, NLLB) or through a provider you explicitly configure (Gemini, OpenAI, Claude, custom).
- The app ships with no bundled API keys and no telemetry.
- Imported comics and saved projects stay in the app's private storage unless the user exports them.
- Internet access is required only for Gemini OCR/translation, API-based providers, and downloading optional model packs.

## Build

Requires Android SDK 36 and Java 17.

```bash
./gradlew assembleDebug
```
On Windows:
```powershell
.\gradlew.bat assembleDebug
```

The debug APK is signed with the default debug keystore. For a release build, configure signing in `app/build.gradle.kts`.

## Architecture

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3 dark theme
- **State:** Android ViewModel with coroutines
- **OCR:** Google ML Kit (`com.google.mlkit:text-recognition*`), Gemini 2.5 Flash API, RapidOCR via ONNX Runtime
- **Translation:** Google ML Kit Translate, Gemini API, NLLB-200 via ONNX (RTranslator tokenizer), Google Translate unofficial endpoint, OpenAI Responses API, Anthropic Messages API, custom OpenAI-compatible API
- **Inpainting:** Custom flood-fill + texture propagation (no external model)
- **Font rendering:** Canvas + StaticLayout, Comic Neue Bold for manga lettering
- **Export:** Android PdfRenderer, PdfDocument, ZipOutputStream, MediaStore
- **Persistence:** JSON project metadata, Android Keystore for secrets

## License

See [LICENSE](LICENSE). This project incorporates:
- **Comic Neue** font under the SIL Open Font License.
- **RTranslator** tokenizer code (NieRTranslator) under Apache 2.0.
- **ONNX Runtime** for Android under the MIT License.
- **RapidOCR / PaddleOCR** models used under their respective licenses; models are downloaded separately and not bundled in the APK.

Users are responsible for having the right to translate and redistribute the material they process. Machine-translated releases should be clearly labeled when they have not received human proofreading.

## Roadmap

- [x] Gradle Android project with custom launcher icon
- [x] Image, PDF, CBZ, ZIP, and folder import
- [x] Natural archive page ordering
- [x] ML Kit OCR (Latin, Japanese, Korean, Chinese)
- [x] ML Kit on-device translation (~180 languages)
- [x] Editable OCR and translation review flow
- [x] Manual Save & Next navigation
- [x] Text bounds and typography editor (font, size, rotation, color, alignment, bold, italic, vertical)
- [x] Undo, redo, autosave, resume, and delete projects
- [x] Image, PDF, CBZ, and ZIP export
- [x] Contrast-mask inpainting for text removal
- [x] Automatic font/style estimation from source text
- [x] Drag, resize, and rotate text boxes on the page canvas
- [x] Gemini Free whole-page OCR + translation
- [x] RapidOCR on-device neural detection (downloadable packs)
- [x] NLLB high-quality offline translation (downloadable pack)
- [x] Google Translate unofficial (experimental, free)
- [x] OpenAI, Anthropic, and custom API providers
- [x] Encrypted API key storage with Android Keystore
- [ ] Glossary and translation-memory support
- [ ] Batch translate all remaining pages
- [ ] Performance profiling for extremely long comics and webtoons

## Inspiration

The workflow is informed by [ImageTrans](https://www.basiccat.org/imagetrans/) and [BallonsTranslator](https://github.com/dmMaze/BallonsTranslator). This repository is an original Android implementation and does not copy their source code.
