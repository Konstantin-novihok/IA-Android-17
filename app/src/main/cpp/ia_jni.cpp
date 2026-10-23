// JNI-обёртка над llama.cpp для IA-Android-17.
// Использует только публичный C API (llama.h). Одна модель и один контекст за раз.
#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <algorithm>
#include <cstdint>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "ia-jni", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ia-jni", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGE(...) LOGI(__VA_ARGS__)
#endif

#include "llama.h"
#include "ggml-backend.h"

namespace {

std::mutex                 g_mutex;
llama_model              * g_model   = nullptr;
llama_context            * g_ctx     = nullptr;
std::vector<llama_token>   g_cache;            // токены, уже лежащие в KV-кэше
std::atomic<bool>          g_cancel{false};
int                        g_n_ctx   = 0;
int                        g_n_batch = 512;
bool                       g_backend_ready = false;

void llama_log_cb(enum ggml_log_level level, const char * text, void *) {
#ifdef __ANDROID__
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) return;
    __android_log_write(prio, "llama", text);
#else
    (void) level; std::fputs(text, stderr);
#endif
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

// Длина самого длинного префикса, оканчивающегося на границе символа UTF-8.
size_t utf8_complete_prefix(const std::string & s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) == 0x80) { i--; back++; continue; }   // байт-продолжение
        size_t need = 1;
        if      ((c & 0x80) == 0x00) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        size_t have = n - (i - 1);
        return have >= need ? n : i - 1;
    }
    return n;
}

std::vector<llama_token> tokenize(const std::string & text, bool parse_special) {
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    int32_t cap = (int32_t) text.size() + 16;
    std::vector<llama_token> out(cap);
    int32_t n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), cap, false, parse_special);
    if (n < 0) {
        out.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), (int32_t) out.size(), false, parse_special);
    }
    out.resize(n < 0 ? 0 : n);
    return out;
}

void free_all() {
    if (g_ctx)   { llama_free(g_ctx);         g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_cache.clear();
    g_n_ctx = 0;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_iaandroid17_engine_LlamaNative_nativeInit(JNIEnv * env, jobject, jstring libDir) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_backend_ready) return;
    llama_log_set(llama_log_cb, nullptr);
    std::string dir = jstr(env, libDir);
    if (!dir.empty()) {
        LOGI("loading backends from %s", dir.c_str());
        ggml_backend_load_all_from_path(dir.c_str());
    }
    llama_backend_init();
    g_backend_ready = true;
}

JNIEXPORT jstring JNICALL
Java_com_iaandroid17_engine_LlamaNative_nativeSystemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// Возвращает пустую строку при успехе, иначе текст ошибки.
JNIEXPORT jstring JNICALL
Java_com_iaandroid17_engine_LlamaNative_nativeLoadModel(JNIEnv * env, jobject, jstring jpath,
                                                        jint nCtx, jint nThreads, jint nBatch) {
    std::lock_guard<std::mutex> lk(g_mutex);
    free_all();
    std::string path = jstr(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) return env->NewStringUTF("Не удалось загрузить модель (файл повреждён или не хватает памяти)");

    int trained = llama_model_n_ctx_train(g_model);
    int ctx = nCtx;
    if (trained > 0 && ctx > trained) ctx = trained;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) ctx;
    cp.n_batch         = (uint32_t) nBatch;
    cp.n_ubatch        = (uint32_t) nBatch;
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        llama_model_free(g_model); g_model = nullptr;
        return env->NewStringUTF("Не удалось создать контекст: недостаточно памяти. Уменьшите лимит контекста.");
    }
    g_n_ctx   = (int) llama_n_ctx(g_ctx);
    g_n_batch = nBatch;
    g_cache.clear();
    LOGI("model loaded, n_ctx=%d threads=%d", g_n_ctx, (int) nThreads);
    return env->NewStringUTF("");
}

JNIEXPORT jint JNICALL
Java_com_iaandroid17_engine_LlamaNative_nativeContextSize(JNIEnv *, jobject) {
    return g_n_ctx;
}

JNIEXPORT jint JNICALL
Java_com_iaandroid17_engine_LlamaNative_nativeCountTokens(JNIEnv * env, jobject, jstring jtext) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_model) return -1;
    return (jint) tokenize(jstr(env, jtext), true).size();
}

JNIEXPORT void JNICALL
Java_com_iaandroid17_engine_LlamaNative_nativeCancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_iaandroid17_engine_LlamaNative_nativeResetCache(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true);
    g_cache.clear();
}

// Результат: >=0 — число сгенерированных токенов; -1 нет модели; -2 промпт не влез; -3 ошибка decode.
JNIEXPORT jint JNICALL
Java_com_iaandroid17_engine_LlamaNative_nativeGenerate(JNIEnv * env, jobject, jstring jprompt,
        jint maxNew, jfloat temperature, jfloat topP, jint topK, jfloat minP, jfloat repeatPenalty,
        jobject callback) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_model || !g_ctx) return -1;
    g_cancel.store(false);

    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
    if (!onToken) return -3;

    std::string prompt = jstr(env, jprompt);
    std::vector<llama_token> toks = tokenize(prompt, true);
    if (toks.empty()) return -2;
    if ((int) toks.size() >= g_n_ctx - 8) return -2;

    // Повторное использование KV-кэша: общий префикс с предыдущим запросом.
    size_t common = 0;
    size_t lim = std::min(toks.size() - 1, g_cache.size());   // хотя бы последний токен надо пересчитать
    while (common < lim && toks[common] == g_cache[common]) common++;
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    g_cache.resize(common);

    // Обработка промпта пачками.
    for (size_t i = common; i < toks.size(); ) {
        if (g_cancel.load()) { g_cache.clear(); llama_memory_clear(mem, true); return 0; }
        int32_t n = (int32_t) std::min((size_t) g_n_batch, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, n);
        if (llama_decode(g_ctx, b) != 0) {
            LOGE("decode failed (prompt)");
            g_cache.clear(); llama_memory_clear(mem, true);
            return -3;
        }
        g_cache.insert(g_cache.end(), toks.begin() + i, toks.begin() + i + n);
        i += n;
    }

    // Семплер.
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (repeatPenalty > 1.0f)
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 128, repeatPenalty, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (topK > 0)     llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
        if (topP < 1.0f)  llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        if (minP > 0.0f)  llama_sampler_chain_add(smpl, llama_sampler_init_min_p(minP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    for (size_t i = (toks.size() > 128 ? toks.size() - 128 : 0); i < toks.size(); i++)
        llama_sampler_accept(smpl, toks[i]);

    int produced = 0;
    int result = 0;
    std::string pending;
    char buf[256];

    while (produced < maxNew) {
        if (g_cancel.load()) break;
        if ((int) g_cache.size() >= g_n_ctx - 1) break;

        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, id);
        if (llama_vocab_is_eog(vocab, id)) break;

        int32_t len = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
        if (len > 0) pending.append(buf, (size_t) len);
        size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            jstring js = env->NewStringUTF(pending.substr(0, ok).c_str());
            jboolean cont = env->CallBooleanMethod(callback, onToken, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
            pending.erase(0, ok);
            if (!cont) {
                produced++;
                llama_token tl = id;
                llama_batch bb = llama_batch_get_one(&tl, 1);
                if (llama_decode(g_ctx, bb) == 0) { g_cache.push_back(id); }
                break;
            }
        }

        llama_token t = id;
        llama_batch b1 = llama_batch_get_one(&t, 1);
        if (llama_decode(g_ctx, b1) != 0) { LOGE("decode failed (gen)"); result = -3; break; }
        g_cache.push_back(id);
        produced++;
    }

    llama_sampler_free(smpl);
    return result < 0 ? result : produced;
}

JNIEXPORT void JNICALL
Java_com_iaandroid17_engine_LlamaNative_nativeUnload(JNIEnv *, jobject) {
    g_cancel.store(true);
    std::lock_guard<std::mutex> lk(g_mutex);
    free_all();
}

} // extern "C"
