package com.iaandroid17.engine

import android.content.Context
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

sealed interface EngineState {
    data object Unloaded : EngineState
    data object Loading : EngineState
    data class Ready(val modelName: String, val nCtx: Int, val threads: Int) : EngineState
    data class Error(val message: String) : EngineState
}

data class GenParams(
    val maxNew: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float,
    val repeatPenalty: Float
)

/** Все вызовы llama.cpp идут через один выделенный поток. */
class LlmEngine(private val context: Context) {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "llm-engine", 16L * 1024 * 1024)
    }.asCoroutineDispatcher()

    suspend fun <T> onEngine(block: () -> T): T = withContext(dispatcher) { block() }

    /** @return пустая строка при успехе, иначе сообщение об ошибке. */
    suspend fun load(path: String, nCtx: Int, threads: Int): String = onEngine {
        try {
            LlamaNative.ensureLoaded(context.applicationInfo.nativeLibraryDir)
            LlamaNative.nativeLoadModel(path, nCtx, threads, 512)
        } catch (t: Throwable) {
            "Ошибка движка: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    suspend fun unload() = onEngine { LlamaNative.nativeUnload() }

    suspend fun resetCache() = onEngine { LlamaNative.nativeResetCache() }

    suspend fun systemInfo(): String = onEngine {
        try {
            LlamaNative.ensureLoaded(context.applicationInfo.nativeLibraryDir)
            LlamaNative.nativeSystemInfo().trim()
        } catch (t: Throwable) {
            "недоступно: ${t.message}"
        }
    }

    suspend fun generate(prompt: String, p: GenParams, cb: TokenCallback): Int = onEngine {
        LlamaNative.nativeGenerate(prompt, p.maxNew, p.temperature, p.topP, p.topK, p.minP, p.repeatPenalty, cb)
    }

    /** Можно вызывать из любого потока. */
    fun cancel() = LlamaNative.nativeCancel()
}
