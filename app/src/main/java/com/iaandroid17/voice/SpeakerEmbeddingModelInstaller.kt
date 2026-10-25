package com.iaandroid17.voice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs the pinned CAM++ speaker encoder from the sherpa-onnx model release.
 * Download is optional and only called by the user; the model then runs offline.
 */
object SpeakerEmbeddingModelInstaller {
    const val MODEL_SHA256 = "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b"
    const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
    private const val MODEL_NAME = "speaker_embedding_campp.onnx"
    private const val MIN_BYTES = 20L * 1024 * 1024
    private const val MAX_BYTES = 60L * 1024 * 1024

    fun modelFile(context: Context) = File(context.noBackupFilesDir, MODEL_NAME)

    fun hasModelFile(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() in MIN_BYTES..MAX_BYTES
    }

    fun isInstalled(context: Context): Boolean =
        hasModelFile(context) && runCatching { sha256(modelFile(context)) == MODEL_SHA256 }.getOrDefault(false)

    /** Blocking I/O; invoke from Dispatchers.IO. A partial or unverified file is never installed. */
    fun download(context: Context) {
        val target = modelFile(context)
        if (isInstalled(context)) return
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$MODEL_NAME.part")
        temp.delete()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "IA-Android-17/0.3")
            }
            val code = connection.responseCode
            check(code in 200..299) { "Model download HTTP $code" }
            val length = connection.contentLengthLong
            if (length > 0) check(length in MIN_BYTES..MAX_BYTES) { "Unexpected model size: $length bytes" }
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output, 256 * 1024); output.fd.sync() }
            }
            check(temp.length() in MIN_BYTES..MAX_BYTES) { "Downloaded model size is invalid: ${temp.length()} bytes" }
            val actual = sha256(temp)
            check(actual == MODEL_SHA256) { "Model SHA-256 mismatch; file rejected" }
            if (target.exists()) check(target.delete()) { "Cannot replace old model file" }
            check(temp.renameTo(target)) { "Cannot finalize verified model file" }
            VoiceSecurityAudit.record(context, "speaker_model_installed", "CAM++ SHA-256 verified")
        } catch (e: Exception) {
            temp.delete()
            VoiceSecurityAudit.record(context, "speaker_model_install_failed", e.javaClass.simpleName)
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
