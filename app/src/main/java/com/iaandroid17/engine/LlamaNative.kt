package com.iaandroid17.engine

/** Колбэк потоковой выдачи: вернуть false, чтобы остановить генерацию. */
fun interface TokenCallback {
    fun onToken(piece: String): Boolean
}

/** Тонкая обёртка над JNI (см. app/src/main/cpp/ia_jni.cpp). Вызывать только с потока движка. */
object LlamaNative {
    @Volatile
    private var loaded = false

    fun ensureLoaded(nativeLibDir: String) {
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("iaandroid_jni")
                nativeInit(nativeLibDir)
                loaded = true
            }
        }
    }

    external fun nativeInit(libDir: String)
    external fun nativeSystemInfo(): String

    /** Пустая строка — успех, иначе текст ошибки. */
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nBatch: Int): String
    external fun nativeContextSize(): Int
    external fun nativeCountTokens(text: String): Int
    external fun nativeCancel()
    external fun nativeResetCache()

    /** >=0 — число токенов; -1 нет модели; -2 запрос не влез в контекст; -3 ошибка вычислений. */
    external fun nativeGenerate(
        prompt: String,
        maxNew: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        callback: TokenCallback
    ): Int

    external fun nativeUnload()
}
