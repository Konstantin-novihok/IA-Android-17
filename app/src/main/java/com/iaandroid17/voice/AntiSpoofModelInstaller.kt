package com.iaandroid17.voice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Pinned MIT-licensed AASIST ONNX anti-spoof model. Download is user-initiated; inference is offline. */
object AntiSpoofModelInstaller {
    const val MODEL_URL = "https://huggingface.co/SpeechAntiSpoofingBenchmarks/AASIST/resolve/main/aasist.onnx"
    const val MODEL_SHA256 = "130e536266b7c537f9a13029e1612a9f392fd1cc827783683b6d1c062a3db5e1"
    private const val MODEL_NAME = "aasist_antispoof.onnx"
    private const val MIN_BYTES = 1_500_000L
    private const val MAX_BYTES = 1_800_000L

    fun modelFile(context: Context) = File(context.noBackupFilesDir, MODEL_NAME)
    fun hasModelFile(context: Context): Boolean = modelFile(context).let { it.isFile && it.length() in MIN_BYTES..MAX_BYTES }
    fun isInstalled(context: Context): Boolean = hasModelFile(context) &&
        runCatching { sha256(modelFile(context)) == MODEL_SHA256 }.getOrDefault(false)

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
            check(connection.responseCode in 200..299) { "Anti-spoof model download HTTP ${connection.responseCode}" }
            val length = connection.contentLengthLong
            if (length > 0) check(length in MIN_BYTES..MAX_BYTES) { "Unexpected AASIST model size: $length" }
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output, 128 * 1024); output.fd.sync() }
            }
            check(temp.length() in MIN_BYTES..MAX_BYTES) { "Downloaded AASIST model size is invalid" }
            check(sha256(temp) == MODEL_SHA256) { "AASIST model SHA-256 mismatch; file rejected" }
            if (target.exists()) check(target.delete()) { "Cannot replace existing AASIST model" }
            check(temp.renameTo(target)) { "Cannot finalize AASIST model" }
            VoiceSecurityAudit.record(context, "anti_spoof_model_installed", "AASIST ONNX SHA-256 verified")
        } catch (e: Exception) {
            temp.delete()
            VoiceSecurityAudit.record(context, "anti_spoof_model_install_failed", e.javaClass.simpleName)
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
