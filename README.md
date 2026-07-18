# Untrusted Translations

Free, open-source comic translation and lettering for Android.

Import an image, PDF, CBZ, ZIP, or image folder; detect dialogue; correct the source and translation; then place, resize, and rotate the replacement text directly on the page. Multi-page projects advance only when you choose **Save & Next**.

> **Beta notice:** OCR and machine translation can be wrong. Review every page before publishing it, and only translate material you have permission to use.

## Features

- Imports PNG, JPEG, WebP, PDF, CBZ, ZIP, and folders selected through Android's system picker.
- Sorts archive and folder pages naturally (`2` before `10`).
- Processes one page at a time; it never forces automatic page changes.
- Provides a phone-friendly editor with source text and translated text in separate fields.
- Lets you tap an applied text block to select it, drag it to move it, resize it from four sides, or switch to the rotation handle.
- Keeps font and size stable while moving or rotating. Resizing is the action that changes the text size.
- Supports manually added text with the manga font and an optional background; after adding it, size, position, and rotation are adjusted on the page. Added text is not placed in the translation review list.
- Includes undo/redo, project autosave, previous-page navigation, **Save page**, **Save & Next**, and **Save & Exit**.
- Exports a new translated file into `Downloads/Untrusted Translations`; the imported original is never overwritten.
- Intentionally favors speech-bubble dialogue and excludes sound effects where the selected comic OCR provider can distinguish them.
- Stores editable project data privately on the device. Exported files are flattened images/PDF pages, not editable text layers.

## Source script and source language are different

The import screen has two source settings because OCR and translation solve different problems:

- **Text detection script** chooses the OCR alphabet/model family. There are four choices: Japanese, Korean, Chinese, and English/Latin.
- **Translate from** tells the translator which actual language the detected words use. It has many more choices because many languages share one script.

For example, a French comic uses **English / Latin** for text detection and **French** for **Translate from**. A Japanese manga normally uses **Japanese** for both.

## Typical workflow

1. Choose the detection script, source language, and target language.
2. Open **OCR & translation** and select providers. Download only the optional packs you want.
3. Import a supported file or folder.
4. Review the detected boxes on the page. Use **Deep scan** if a dialogue bubble was missed.
5. Open **Editor**, correct OCR/translation text, and apply the replacements.
6. Tap a replacement on the page to move, resize, or rotate it.
7. Use **Save page** to stay on the current page, **Previous page** to go back, or **Save & Next** to advance.
8. On the last page, use **Save & Exit** to create a new translated output and return to import/projects.

## Import and export

| Import | Export |
| --- | --- |
| PNG, JPEG, WebP | PNG |
| PDF | PDF |
| CBZ | CBZ |
| ZIP | ZIP |
| Image folder | ZIP |

CBR/RAR is not supported. The app does not edit an archive in place. It writes a separate `*-translated` output, preserving the original project source.

## OCR providers

| Provider | Network | Notes |
| --- | --- | --- |
| Comic AI | Download once | Recommended dialogue-first pipeline. Uses the comic detector plus Japanese Manga-OCR or the matching RapidOCR recognizer. Designed to avoid free text/SFX. |
| Comic AI Vision | Download once | High-tier local Qwen2-VL crop reader with the same comic detector and RapidOCR fallback. Large download and higher RAM use. |
| RapidOCR | Download once | Small script-specific PaddleOCR/RapidOCR ONNX packs. |
| RapidOCR PP-OCRv5 | Download once | Experimental newer detector/recognizers. |
| Google ML Kit | Offline after SDK/model availability | Fast baseline for clean print; weaker on stylized bubbles and some vertical layouts. |
| Gemini Free | Online | Context-aware page OCR using the user's own Gemini API key/free quota. Billing can be left disabled. |

No OCR can guarantee every stylized bubble. Deep scan broadens the search, while dialogue-only providers deliberately reject likely SFX and non-dialogue art text.

## Translation providers

| Provider | Cost/network | Notes |
| --- | --- | --- |
| Local AI LLM | Optional 485 MB–2.5 GB download | Qwen3 GGUF models run fully on the phone and retain page-dialogue context. Low, Mid, and High tiers are selectable. |
| Google ML Kit | Free/offline after language download | Fast and broad language coverage; sentence quality varies. |
| NLLB | Optional ~950 MB download | Fully local, ARM64, about 6 GB RAM recommended. **Non-commercial model license.** |
| Gemini Free | Online free quota | Context-aware translation with the user's Gemini API key. Quota and availability are controlled by Google. |
| Google Translate (unofficial) | Online, no key | Experimental unofficial endpoint; may be rate-limited or stop working without notice. |
| OpenAI API | Paid API | Optional bring-your-own API key. API billing is separate from ChatGPT subscriptions/free chat access. |
| Claude API | Paid API | Optional bring-your-own API key. API billing is separate from Claude website subscriptions/free chat access. |
| Custom OpenAI-compatible API | Depends on server | For a user-selected HTTPS endpoint; can be a local/free server or a billed provider. |

Signing into ChatGPT or Claude in a browser cannot legally or reliably transfer website chat credits into this app. Only official API keys/endpoints are supported for those services. The app contains no shared secret keys and never silently changes to a paid provider.

## Device tiers and downloads

AI models are **not bundled in the APK**. The app recommends a tier from available RAM/CPU architecture, but the user chooses what to download.

| Tier | Local translation | Suggested memory |
| --- | --- | --- |
| Low | Qwen3 0.6B Q4 (~485 MB) | 3 GB+ RAM |
| Mid | Qwen3 1.7B Q4 (~1.28 GB) | 5 GB+ RAM |
| High | Qwen3 4B Q4 (~2.50 GB) | 7 GB+ RAM |
| Vision High | Qwen2-VL 2B + projector (~1.70 GB) | 6 GB+ RAM |

Downloads resume when possible and pinned packs with supplied checksums are SHA-256 verified before activation. Actual speed depends on the phone and page resolution.

## Privacy and keys

- Local OCR/translation providers process pages on the device.
- Online providers receive the image or text necessary for the selected operation. Their own privacy policies apply.
- API secrets are encrypted with AES-GCM using a non-exportable Android Keystore key and can be removed in settings.
- Imported originals and working projects stay in app-controlled storage; exported copies are written to the Downloads collection.
- The repository and APK contain no developer API key.

## Building

Requirements:

- Git with submodule support
- JDK 17
- Android SDK 36
- Android NDK `27.3.13750724`
- CMake `3.31.6`
- Ninja

Clone with the pinned llama.cpp submodule:

```bash
git clone --recurse-submodules https://github.com/UntrustedGuy/Untrusted-Translations-Android.git
cd Untrusted-Translations-Android
```

If the repository was cloned without submodules:

```bash
git submodule update --init --recursive
```

Build with the included Gradle wrapper:

```bash
./gradlew :app:assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

The first build takes longer because llama.cpp and the local vision bridge compile for ARM64 and x86_64. Model weights are downloaded by users inside the app, not during the build.

## Architecture

- Kotlin, Jetpack Compose Material 3, coroutines, and Android Storage Access Framework
- Android `PdfRenderer`/`PdfDocument` for PDF import and export
- ONNX Runtime for downloadable OCR and NLLB packs
- ML Kit for lightweight OCR and translation
- llama.cpp for optional Qwen3 translation and Qwen2-VL vision OCR
- JSON project metadata plus per-page working images in private app storage
- Provider interfaces so OCR and translation can be selected independently

## Credits and licenses

The workflow is inspired by [ImageTrans](https://www.basiccat.org/imagetrans/) and [BallonsTranslator](https://github.com/dmMaze/BallonsTranslator). This repository is an original Android implementation, not a port.

The icon combines the project's book/translation mark with the user-provided pierced-heart motif. The final artwork was created for this project with OpenAI's image-generation tool and integrated as a transparent Android launcher asset.

All libraries, models, fonts, conversions, and quantizers are credited in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), with license links and the important NLLB non-commercial restriction. Bundled license texts include [RTranslator/Apache-2.0](RTRANSLATOR_LICENSE.txt), [llama.cpp MIT](third_party/llama.cpp/LICENSE), and [Comic Neue OFL-1.1](app/src/main/assets/fonts/OFL-ComicNeue.txt).

## Project license

Untrusted Translations is released under [GNU GPL-3.0](LICENSE). Third-party components and separately downloaded models remain under their own licenses. You are responsible for reviewing those terms and for having permission to translate and publish the source material.
