# Third-party notices

This document credits the software, models, fonts, conversions, services, and design references used by Untrusted Translations. A component's inclusion here does not change its license; each item remains governed by its upstream terms.

## Bundled/runtime software

| Component | Use | License / credit |
| --- | --- | --- |
| Android Open Source Project and AndroidX/Jetpack Compose | Android platform, UI, lifecycle, activity, DataStore | Apache License 2.0. Copyright their respective Android Open Source Project contributors. <https://source.android.com/docs/setup/about/licenses> |
| Kotlin and kotlinx.coroutines | Language/runtime and asynchronous work | Apache License 2.0. Copyright JetBrains and Kotlin contributors. <https://github.com/JetBrains/kotlin>, <https://github.com/Kotlin/kotlinx.coroutines> |
| Material Icons | In-app interface icons | Apache License 2.0. Copyright Google LLC. <https://github.com/google/material-design-icons> |
| ONNX Runtime Android | Local ONNX inference | MIT License. Copyright Microsoft Corporation. <https://github.com/microsoft/onnxruntime> |
| llama.cpp / ggml / mtmd and Android AI-chat JNI source | Local GGUF text and vision inference | MIT License. Copyright 2023–2026 the ggml authors. Pinned as a git submodule; full text: [third_party/llama.cpp/LICENSE](third_party/llama.cpp/LICENSE). The local JNI compatibility copy is derived from the pinned upstream Android example and remains MIT-licensed. |
| Google ML Kit text recognition and translation SDKs | Baseline OCR and translation | Proprietary Google SDK/services, subject to the [ML Kit terms](https://developers.google.com/ml-kit/terms) and applicable Google APIs Terms. ML Kit is not claimed as open source. |
| Comic Neue Bold | Manga lettering font | Copyright 2014 The Comic Neue Project Authors; SIL Open Font License 1.1. Full text: [app/src/main/assets/fonts/OFL-ComicNeue.txt](app/src/main/assets/fonts/OFL-ComicNeue.txt). Source: <https://github.com/crozynski/comicneue>. |

The project also uses standard Java/Kotlin/Android platform facilities for ZIP archives, JSON, cryptography, graphics, and PDF rendering; these are not separately bundled third-party libraries.

## Optional downloadable OCR models

These weights are not in the APK. The app downloads the selected files from the named upstream host.

| Pack/source | Credit and license |
| --- | --- |
| RapidOCR converted PaddleOCR models | RapidAI team and PaddleOCR/Baidu contributors. RapidOCR engineering and PaddleOCR are Apache License 2.0; upstream notes that OCR model copyright is held by Baidu. <https://github.com/RapidAI/RapidOCR>, <https://github.com/PaddlePaddle/PaddleOCR>, <https://www.modelscope.cn/models/RapidAI/RapidOCR> |
| `monkt/paddleocr-onnx` PP-OCRv5 conversions | Credited to monkt and the PaddleOCR authors; model/conversion repository: <https://huggingface.co/monkt/paddleocr-onnx>. PaddleOCR upstream is Apache License 2.0. |
| Manga-OCR Japanese | Original Manga OCR by kha-white, Apache License 2.0: <https://github.com/kha-white/manga-ocr>. ONNX conversion/files by l0wgear: <https://huggingface.co/l0wgear/manga-ocr-2025-onnx>. |
| Comic text and bubble detector | Model by ogkalu, fine-tuned RT-DETR-v2 comic dialogue detector; Apache License 2.0: <https://huggingface.co/ogkalu/comic-text-and-bubble-detector>. |
| Baberu OCR | Model/code by genshiai-daichi; Apache License 2.0. The model card also acknowledges DINOv2, Manga-OCR, and PaddleOCR-VL teachers: <https://huggingface.co/genshiai-daichi/baberu-ocr>. |

Apache License 2.0 full text is included in [RTRANSLATOR_LICENSE.txt](RTRANSLATOR_LICENSE.txt). That file is the standard unmodified Apache-2.0 license text.

## Optional downloadable translation and vision models

| Pack/source | Credit and license |
| --- | --- |
| RTranslator optimized NLLB-200 Distilled 600M ONNX files and tokenizer | Conversion/optimization by niedev/RTranslator; RTranslator code is Apache License 2.0: <https://github.com/niedev/RTranslator>. The underlying Meta NLLB model is **CC-BY-NC 4.0 / non-commercial use only**. Selecting this pack does not grant commercial-use rights. Model card: <https://huggingface.co/facebook/nllb-200-distilled-600M>. |
| Qwen3 0.6B, 1.7B, and 4B | Copyright Alibaba Cloud/Qwen team; Apache License 2.0. <https://huggingface.co/Qwen/Qwen3-0.6B>, <https://huggingface.co/Qwen/Qwen3-1.7B>, <https://huggingface.co/Qwen/Qwen3-4B>. |
| Qwen3 GGUF Q4_K_M quantizations | Quantized and published by bartowski from the Qwen models; retain the upstream Apache-2.0 model license. <https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF>, <https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF>, <https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF>. |
| Qwen2-VL 2B Instruct | Copyright 2024 Alibaba Cloud/Qwen team; Apache License 2.0. <https://huggingface.co/Qwen/Qwen2-VL-2B-Instruct>. |
| Qwen2-VL GGUF model/projector | Conversion published by the ggml-org community for llama.cpp/mtmd; underlying model remains Apache License 2.0. <https://huggingface.co/ggml-org/Qwen2-VL-2B-Instruct-GGUF>. |

Model URLs in the app are pinned to specific repository revisions where practical, and selected large packs include expected SHA-256 hashes. Hosting platforms and upstream authors are not affiliated with or responsible for Untrusted Translations.

## Online and external services

- Gemini API is a Google service governed by Google's Gemini API/Google APIs terms. The app uses only a user-supplied key.
- OpenAI API is governed by OpenAI's API terms and billing; the app uses only a user-supplied key.
- Anthropic Claude API is governed by Anthropic's API terms and billing; the app uses only a user-supplied key.
- The custom OpenAI-compatible endpoint is selected by the user and is governed by that server/provider's terms.
- The no-key Google Translate endpoint is **unofficial and experimental**. It is not endorsed by Google, has no availability guarantee, and may be rate-limited or removed.

No online service's name or mark implies sponsorship.

## Design references

- [ImageTrans](https://www.basiccat.org/imagetrans/) — computer-aided image translation workflow reference.
- [BallonsTranslator](https://github.com/dmMaze/BallonsTranslator) — open-source comic translation/editor workflow reference; its code is not copied into this Android implementation.
- Launcher artwork — created specifically for this project with OpenAI's image-generation tool, following the user's book, pierced-heart, pin, and dark violet/cyan/coral design direction. The user-supplied visual was a motif/reference, not redistributed as a separate third-party asset.

## Project license relationship

The application source is GPL-3.0 under [LICENSE](LICENSE). Apache-2.0, MIT, OFL-1.1, proprietary SDK/service terms, and model-specific licenses continue to apply to their respective material. In particular, the NLLB pack's non-commercial restriction is independent of the app's GPL license.
