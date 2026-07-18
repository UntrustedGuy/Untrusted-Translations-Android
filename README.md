# Untrusted Translations

**Translate and re-letter manga, manhwa, manhua, and comics on Android — without sending the whole job to a PC.**

This project started with a simple frustration: translating a chapter on a phone meant jumping between an OCR app, a translator, an image editor, and a file manager. Untrusted Translations keeps that work in one project. Import a volume, review the dialogue one page at a time, correct the translation, and place the finished text back on the artwork.

It is still a beta. OCR will miss unusual lettering, translations will sometimes sound wrong, and automatic cleanup cannot understand every speech bubble. The editor is there because the human pass matters.

[Download the latest beta](https://github.com/UntrustedGuy/Untrusted-Translations-Android/releases) · [Report a bug](https://github.com/UntrustedGuy/Untrusted-Translations-Android/issues)

> Only translate work you have permission to use. Always review a page before publishing it.

## A look at the app

| Import and projects | OCR and translation setup |
| --- | --- |
| <img src="docs/screenshots/home.png" width="320" alt="Import and projects screen"> | <img src="docs/screenshots/ocr-translation-settings.png" width="320" alt="OCR and translation provider settings"> |

| Page editor | Translation editor |
| --- | --- |
| <img src="docs/screenshots/page-editor.png" width="320" alt="Page editor with a selected dialogue block"> | <img src="docs/screenshots/text-editor.png" width="320" alt="Source and translated text editor"> |

## What it can do

- Import PNG, JPEG, WebP, PDF, CBZ, ZIP, or a folder of images.
- Keep archive pages in natural order, so page 2 comes before page 10.
- Detect dialogue while deliberately filtering out likely sound effects and decorative text.
- Let you fix both the recognized source text and its translation.
- Replace dialogue on the page, then tap a block to move, resize, or rotate it.
- Add your own text with the bundled manga font and an optional background.
- Change font, alignment, colour, bold, italic, and vertical-text settings when needed.
- Save the current page, return to a previous page, or use **Save & Next** when you are actually ready to move on.
- Recover editable projects from private app storage and export a separate translated copy.

The original import is never edited in place. Images export as PNG, PDF as PDF, CBZ as CBZ, and ZIP/folder projects as ZIP under `Downloads/Untrusted Translations`.

## Install

The current build is **0.6.0 Beta 1**. Most physical Android phones need the **ARM64** APK; the x86_64 build is mainly for emulators.

If you already have the app installed, update it directly. Do not uninstall first unless you also want to remove your projects, settings, and downloaded models.

Models are not hidden inside the APK. Optional OCR and translation packs are downloaded from their credited upstream projects only when you choose them.

## A normal translation session

1. Pick the text-detection script, source language, and target language.
2. Open **OCR & translation** and choose one dialogue recognizer and one translator.
3. Download any optional model you want, then tap **Use model**. Downloading a model does not activate it automatically.
4. Import a page, archive, PDF, or folder.
5. Check the detected dialogue. Try **Deep scan** if a real speech bubble was missed.
6. Open the editor, repair the OCR text and translation, then apply it.
7. Tap the replacement on the page to position, resize, or rotate it.
8. Save the page, go back, or choose **Save & Next**. On the final page, use **Save & Exit**.

There is no automatic page advance. A long archive moves forward only when you save and ask it to.

## Detection script vs. source language

These are separate because OCR and translation need different information.

- **Text detection script** chooses the alphabet/model family: Japanese, Korean, Chinese, or Latin.
- **Translate from** identifies the actual language that should be translated.

For example, a French comic should use **Latin** for detection and **French** as the source language. A Japanese manga normally uses Japanese for both.

## OCR choices

Every recognizer is paired with the shared comic-dialogue detector. That first stage finds bubble text and rejects likely free text/SFX; the selected OCR then reads the accepted crops. **Deep scan** relaxes the detector, but it still tries to avoid sound effects.

- **Baberu OCR** — a compact multilingual manga-bubble recognizer for Japanese, Chinese, and English.
- **Manga-OCR** — a Japanese specialist that handles vertical text, furigana, and manga lettering.
- **RapidOCR / PP-OCRv5** — small script-specific PaddleOCR-derived ONNX packs.
- **Qwen2-VL Vision High** — a much larger local vision model for difficult pages.
- **Google ML Kit** — the quick baseline for clean printed text.
- **Gemini** — optional online page OCR using the user's own API key and quota.

One recognizer is active at a time. Installing three models does not make the app silently combine all three.

## Translation choices

- **Local Qwen3** — fully on-device contextual translation in Low (about 485 MB), Mid (about 1.28 GB), and High (about 2.50 GB) sizes.
- **Google ML Kit** — lightweight offline translation after its language pack is available.
- **NLLB-200** — about 950 MB, ARM64 only, and intended for stronger offline multilingual translation. The model is **CC BY-NC 4.0 and non-commercial**.
- **Gemini** — official API access using the user's own key/free quota.
- **Google Translate (unofficial)** — experimental no-key endpoint that may be rate-limited or disappear.
- **OpenAI, Claude, or a custom OpenAI-compatible endpoint** — optional bring-your-own-key services. Provider billing and terms apply.

A ChatGPT or Claude website subscription cannot be reused as API credit. The app never includes a shared developer key and never switches someone to a paid service without their configuration.

## Local model sizes

| Pack | Approximate download | Suggested RAM |
| --- | ---: | ---: |
| Comic dialogue detector | 12 MB | 2 GB+ |
| Baberu OCR | 121 MB | 3 GB+ |
| Manga-OCR | 140 MB | 4 GB+ |
| Qwen3 translation Low | 485 MB | 3 GB+ |
| NLLB translation | 950 MB | 6 GB+ |
| Qwen3 translation Mid | 1.28 GB | 5 GB+ |
| Qwen2-VL Vision High | 1.70 GB | 6 GB+ |
| Qwen3 translation High | 2.50 GB | 7 GB+ |

Those figures are download sizes, not guarantees. Page resolution, Android memory pressure, and the phone's CPU affect speed and whether a large model can run comfortably. Downloads resume where the host supports it, and pinned large files are checked against their expected SHA-256 hashes before activation.

## Privacy

Local providers keep OCR and translation on the device. Online providers receive the image or text needed for the selected request and operate under their own privacy policies.

API keys are encrypted with AES-GCM using a non-exportable Android Keystore key. They can be removed from settings. Originals and working project files stay in app-controlled storage; only an exported copy is written to Downloads.

## Known beta limitations

- OCR is not perfect, especially with handwriting, very stylized fonts, low-resolution scans, or unusual bubble shapes.
- Dialogue filtering can reject a real line or admit an SFX. Deep scan helps with misses but is intentionally not a “detect everything” mode.
- Automatic text removal and lettering will not match every original font or painted background.
- Large local models can take time to load and may be impractical on lower-memory phones.
- CBR/RAR import is not supported yet.
- This is currently a debug-signed prerelease. Keep backups of important projects.

## Building from source

You need JDK 17, Android SDK 36, Android NDK `27.3.13750724`, CMake `3.31.6`, Ninja, and Git with submodule support.

```bash
git clone --recurse-submodules https://github.com/UntrustedGuy/Untrusted-Translations-Android.git
cd Untrusted-Translations-Android
./gradlew :app:assembleDebug
```

On Windows, use `.\gradlew.bat :app:assembleDebug`. If you cloned without submodules, run `git submodule update --init --recursive` first. The first build is slow because llama.cpp is compiled for ARM64 and x86_64.

## Thanks and credits

This app would not exist in its current form without a lot of open work from other people:

Untrusted Translations is developed and maintained by [UntrustedGuy](https://github.com/UntrustedGuy).

- [ImageTrans](https://www.basiccat.org/imagetrans/) and [BallonsTranslator](https://github.com/dmMaze/BallonsTranslator) showed what a good computer-assisted comic translation workflow could look like. This project is an original Android implementation, not a port of either codebase.
- [ogkalu](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) published the RT-DETR-v2 comic text/bubble detector used to separate dialogue from free text.
- [kha-white](https://github.com/kha-white/manga-ocr) created Manga-OCR; [jzhang533](https://huggingface.co/jzhang533/manga-ocr-base-2025) produced the 2025 fine-tune; and [l0wgear](https://huggingface.co/l0wgear/manga-ocr-2025-onnx) exported the ONNX files used here.
- [genshiai-daichi](https://huggingface.co/genshiai-daichi/baberu-ocr) created Baberu OCR and credits DINOv2, Manga-OCR, and PaddleOCR-VL as its upstream components/teachers.
- The [RapidAI team](https://github.com/RapidAI/RapidOCR), [PaddleOCR contributors](https://github.com/PaddlePaddle/PaddleOCR), and [monkt](https://huggingface.co/monkt/paddleocr-onnx) provide the OCR models and ONNX conversions behind the RapidOCR options.
- [niedev](https://github.com/niedev/RTranslator) published RTranslator's Android-optimized NLLB files. The underlying NLLB-200 model is from Meta's NLLB team and remains non-commercial.
- The [Qwen team](https://huggingface.co/Qwen) created Qwen3 and Qwen2-VL; [bartowski](https://huggingface.co/bartowski) published the Qwen3 GGUF quantizations; and [ggml-org](https://huggingface.co/ggml-org/Qwen2-VL-2B-Instruct-GGUF) published the Qwen2-VL GGUF model/projector.
- [llama.cpp](https://github.com/ggml-org/llama.cpp) provides local GGUF inference, [ONNX Runtime](https://github.com/microsoft/onnxruntime) runs the ONNX models, and Android/Kotlin/Jetpack Compose/Google ML Kit provide the platform and lightweight mobile ML pieces.
- [Comic Neue](https://github.com/crozynski/comicneue), by the Comic Neue project authors, is the bundled lettering font under the SIL Open Font License 1.1.

The launcher icon was created specifically for this project from the book/translation mark and the user's pierced-heart artwork direction, with OpenAI's image-generation tool used during production.

For exact licenses, model restrictions, upstream links, and service terms, read [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Bundled texts include the [llama.cpp MIT license](third_party/llama.cpp/LICENSE), [Comic Neue OFL-1.1](app/src/main/assets/fonts/OFL-ComicNeue.txt), and the [Apache License 2.0](RTRANSLATOR_LICENSE.txt).

## License

The application source is available under [GNU GPL-3.0](LICENSE). Downloaded models, bundled libraries, fonts, and online services keep their own licenses and terms. The GPL does not remove NLLB's non-commercial restriction or grant rights to the comics you translate.
