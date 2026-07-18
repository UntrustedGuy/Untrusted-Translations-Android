#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <algorithm>
#include <string>
#include <vector>

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "UntrustedVision"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model * model = nullptr;
static llama_context * lctx = nullptr;
static mtmd_context * vision = nullptr;
static common_chat_templates_ptr templates;
static std::string last_error;

static void vision_log_callback(enum ggml_log_level level, const char * text, void *) {
    __android_log_print(
        level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_DEBUG,
        TAG,
        "%s",
        text);
    if (level == GGML_LOG_LEVEL_ERROR || (level == GGML_LOG_LEVEL_CONT && !last_error.empty())) {
        last_error += text;
        if (last_error.size() > 2048) last_error.erase(0, last_error.size() - 2048);
    }
}

static void free_all() {
    templates.reset();
    if (vision) { mtmd_free(vision); vision = nullptr; }
    if (lctx) { llama_free(lctx); lctx = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_untrustedtranslations_android_processing_VisionLlmRuntime_nativeLoad(
        JNIEnv * env, jobject, jstring model_path_j, jstring projector_path_j, jstring lib_dir_j) {
    free_all();
    last_error.clear();
    llama_log_set(vision_log_callback, nullptr);
    const char * lib_dir = env->GetStringUTFChars(lib_dir_j, nullptr);
    ggml_backend_load_all_from_path(lib_dir);
    env->ReleaseStringUTFChars(lib_dir_j, lib_dir);
    llama_backend_init();
    const size_t backend_count = ggml_backend_dev_count();
    LOGE("Loaded %zu backend devices for Vision High", backend_count);
    if (backend_count == 0) {
        last_error = "No llama.cpp CPU backend was extracted from the APK.";
        return 4;
    }

    const char * model_path = env->GetStringUTFChars(model_path_j, nullptr);
    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.use_extra_bufts = false;
    model_params.use_mmap = true;
    model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(model_path_j, model_path);
    if (!model) return 1;

    auto cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 256;
    cp.n_ubatch = 128;
    cp.n_threads = std::max(2, std::min(4, (int) sysconf(_SC_NPROCESSORS_ONLN) - 1));
    cp.n_threads_batch = cp.n_threads;
    lctx = llama_init_from_model(model, cp);
    if (!lctx) { free_all(); return 2; }

    const char * projector_path = env->GetStringUTFChars(projector_path_j, nullptr);
    auto mp = mtmd_context_params_default();
    mp.use_gpu = false;
    mp.print_timings = false;
    mp.n_threads = cp.n_threads;
    mp.warmup = false;
    mp.image_min_tokens = 64;
    mp.image_max_tokens = 256;
    vision = mtmd_init_from_file(projector_path, model, mp);
    env->ReleaseStringUTFChars(projector_path_j, projector_path);
    if (!vision || !mtmd_support_vision(vision)) { free_all(); return 3; }
    templates = common_chat_templates_init(model, "");
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_untrustedtranslations_android_processing_VisionLlmRuntime_nativeLastError(
        JNIEnv * env, jobject) {
    return env->NewStringUTF(last_error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_untrustedtranslations_android_processing_VisionLlmRuntime_nativeRead(
        JNIEnv * env, jobject, jbyteArray rgb_j, jint width, jint height, jstring prompt_j) {
    if (!model || !lctx || !vision) return nullptr;
    const jsize length = env->GetArrayLength(rgb_j);
    if (length != width * height * 3) return nullptr;
    std::vector<unsigned char> rgb(length);
    env->GetByteArrayRegion(rgb_j, 0, length, reinterpret_cast<jbyte *>(rgb.data()));

    mtmd_bitmap * bitmap = mtmd_bitmap_init(width, height, rgb.data());
    if (!bitmap) return nullptr;
    const char * prompt_chars = env->GetStringUTFChars(prompt_j, nullptr);
    common_chat_msg message;
    message.role = "user";
    message.content = std::string(mtmd_default_marker()) + prompt_chars;
    env->ReleaseStringUTFChars(prompt_j, prompt_chars);
    std::vector<common_chat_msg> history;
    std::string formatted = common_chat_format_single(templates.get(), history, message, true, false);

    mtmd_input_text input{formatted.data(), formatted.size(), true, true};
    const mtmd_bitmap * bitmaps[] = { bitmap };
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    int result = mtmd_tokenize(vision, chunks, &input, bitmaps, 1);
    llama_pos past = 0;
    if (result == 0) {
        const size_t count = mtmd_input_chunks_size(chunks);
        for (size_t i = 0; i < count; ++i) {
            const auto * chunk = mtmd_input_chunks_get(chunks, i);
            llama_pos next = past;
            result = mtmd_helper_eval_chunk_single(
                vision, lctx, chunk, past, 0, 512, i + 1 == count, &next);
            if (result != 0) break;
            past = next;
        }
    }
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);
    if (result != 0) { llama_memory_clear(llama_get_memory(lctx), true); return nullptr; }

    common_params_sampling sampling;
    sampling.temp = 0.1f;
    common_sampler * sampler = common_sampler_init(model, sampling);
    llama_batch batch = llama_batch_init(1, 0, 1);
    const llama_vocab * vocab = llama_model_get_vocab(model);
    std::string output;
    for (int i = 0; i < 192; ++i) {
        const llama_token token = common_sampler_sample(sampler, lctx, -1);
        common_sampler_accept(sampler, token, true);
        if (llama_vocab_is_eog(vocab, token)) break;
        output += common_token_to_piece(lctx, token);
        common_batch_clear(batch);
        common_batch_add(batch, token, past++, {0}, true);
        if (llama_decode(lctx, batch) != 0) break;
    }
    llama_batch_free(batch);
    common_sampler_free(sampler);
    llama_memory_clear(llama_get_memory(lctx), true);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_untrustedtranslations_android_processing_VisionLlmRuntime_nativeUnload(
        JNIEnv *, jobject) {
    free_all();
}
