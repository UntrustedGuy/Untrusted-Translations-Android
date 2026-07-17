# Untrusted Translations

A translation workspace for Android. Import a comic or a single page, let the app find the text, fix what the traslator got wrong, and replace the original lettering — all on your phone.

App can also be used to change text on images.

---

## What's here

- **Import** PNG, JPEG, WebP, PDF, CBZ, ZIP files and folder with images through the system file picker and Folder picker. Archives are sorted naturally (page 2 comes before page 10).
- **PDF support** — renders PDFs into editable page images.
- **6 different OCR engines** — pick what works for your comic:
  - Gemini Free**Best as of now** — reads the whole page like a person would, ignores sound effects
  - Google ML Kit — built-in, no downloads, good enough for clean print
  - RapidOCR — neural OCR you download as a small pack (Japanese, Korean, Chinese, or Latin)
  - PP-OCRv5 — same idea but newer models from HuggingFace
  - Manga-OCR — a vision transformer made for manga, Japanese only
  - Baberu OCR — multilingual vision transformer for Japanese, Chinese, and English (experimental)
- **8 translation options** — from offline to paid APIs:
  - Gemini Free — context-aware, keeps names and tone in mind
  - Google ML Kit — fast, offline, ~180 languages
  - NLLB — high-quality neural translation, fully offline (~950 MB, ARM64 only)
  - Local AI — a smaller distilled NLLB model
  - Google Translate (unofficial) — experimental, no key needed, might break
  - OpenAI API — bring your own key (costs money)
  - Claude API — bring your own key (costs money)
  - Custom API — any OpenAI-compatible endpoint
- **Sound effects are intentionally ignored.** Cause I don't want SFX detected.
- **Everything is editable** — wrong OCR text, bad translation, font, size, rotation, alignment, color, bold, italic, vertical layout.
- **Move, resize, rotate** each text box directly on the page.
- **Undo and redo** in the editor.
- **Autosave** — pick up where you left off, or delete old projects.
- **Export** keeps the original format: PNG stays PNG, PDF stays PDF, etc.
- **Home button** in the page viewer to jump back to your project list. This will auto save your progess.

---

## Quick start

1. Pick the source script(This is the lang your source is in. This is for OCR.), source language(This is the lang you are translating from.), and target language(The lang you want to translate to.) on the import screen.
2. Tap the gear icon to choose your OCR and translation engines. Download any model packs you want.
3. Import a file or folder.
4. The app detects and translates the current page.
5. Tap **Editor** to check each text block one at a time.
6. Fix whatever the machine messed up. Tweak the style.
7. Tap **Apply** to put the translated text on the page.
8. **Save & Next** moves you forward. On the last page, **Save & Exit** exports and sends you home.

---

## Import / export

| Format | Import | Export |
|--------|--------|--------|
| PNG, JPEG, WebP | Yes | PNG |
| PDF | Yes | PDF |
| CBZ | Yes | CBZ |
| ZIP | Yes | ZIP |
| Folder of images | Yes | Folder |

CBR isn't supported. RAR licensing makes it complicated for an open-source app to bundle.

---

## Privacy

Most of the processing happens on your device. Online providers (Gemini, OpenAI, Claude) send images or text to their servers — but only if you bring your own API key. The app ships with **zero embedded keys**. Your keys are stored encrypted and you can delete them whenever you want.

Downloadable model packs (RapidOCR, PP-OCRv5, Manga-OCR, Baberu, NLLB, Local AI) are fully offline. No data leaves your phone when using those.

**The app never silently switches to a paid service.** You have to explicitly turn those on.

---

## What it can't do (yet)

- Text removal uses a contrast mask and blends surrounding pixels. Works fine inside speech bubbles, but detailed art behind text can still look rough.
- Font heuristics are guesses, not exact matches. You can always tweak them.
- OCR quality varies by scan quality, text direction, and stylization. Different engines handle different comics better.
- Big PDFs and archives depend on how much RAM your device has.
- Export is raster-based. The editable text layers are only inside the app's saved projects, not in the exported files.
- Model packs need a stable connection while downloading. NLLB needs ~6 GB RAM and an ARM64 chip.

---

## Building

Gradle wrapper is included. You need Android SDK 36 and Java 17.

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

---

## How it's put together

- Kotlin + Jetpack Compose — all dark UI
- ViewModel for screen state and project state
- Storage Access Framework for file imports
- ML Kit for baseline OCR and translation
- Provider pattern for pluggable OCR and translation backends
- ONNX Runtime for on-device neural models (RapidOCR, PP-OCRv5, Manga-OCR, Baberu)
- RTranslator's optimized NLLB decoder for offline translation
- ModelPackManager handles downloads and verifies SHA-256 checksums
- PdfRenderer for PDF pages
- EncryptedSharedPreferences for API keys
- JSON project metadata with per-page files in private storage
- Original pages are never overwritten

---

## What's next

- [x] Gradle build, launcher icon
- [x] Import PNG, JPEG, WebP, PDF, CBZ, ZIP
- [x] Natural archive sorting
- [x] OCR + translation per page
- [x] Editable text review
- [x] Manual page navigation
- [x] Text box editing (position, size, rotation, font, color, etc.)
- [x] Undo, redo, autosave, resume, delete
- [x] Export in original format
- [x] Local text removal with texture blending
- [ ] Neural inpainting for tricky artwork
- [x] Font and style estimation
- [x] Drag, resize, rotate text boxes on the page
- [x] Multiple OCR providers (Gemini, ML Kit, RapidOCR, PP-OCRv5, Manga-OCR, Baberu)
- [x] Multiple translation providers (Gemini, ML Kit, NLLB, Local AI, OpenAI, Claude, Custom API)
- [x] Downloadable model packs with integrity checks
- [x] Tier labels (LOW / MID / HIGH) in the provider picker
- [x] Sound effects excluded by default
- [x] Home button in the page viewer
- [ ] Glossary and translation memory
- [ ] Performance logging for long comics
- [ ] Automated UI tests

---

## Credits

The workflow borrows ideas from [ImageTrans](https://www.basiccat.org/imagetrans/) and [BallonsTranslator](https://github.com/dmMaze/BallonsTranslator). This is an original Android implementation, not a port.

---

## License

See [LICENSE](LICENSE). You're responsible for having the right to translate whatever you throw at this. If you publish machine-translated work, label it clearly.
