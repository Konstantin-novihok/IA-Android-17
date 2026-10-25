package com.iaandroid17.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controlled 16 kHz mono signed PCM16 capture. Audio data is written as raw PCM;
 * callers must delete temporary files when done. This class performs no identity/liveness claims.
 */
class PcmAudioCapture {
    private var target: File? = null
    private var audio: AudioRecord? = null
    private var worker: Thread? = null
    private val active = AtomicBoolean(false)
    @Volatile private var failure: String? = null

    // Compatibility surface for the enrollment UI's setup sequence.
    fun setAudioSource(source: Int) { require(source == MediaRecorder.AudioSource.MIC) }
    fun setOutputFormat(format: Int) { require(format == MediaRecorder.OutputFormat.MPEG_4 || format == 0) }
    fun setAudioEncoder(encoder: Int) { /* PCM has no lossy encoder. */ }
    fun setAudioSamplingRate(rate: Int) { require(rate == SAMPLE_RATE) }
    fun setAudioEncodingBitRate(rate: Int) { /* Not applicable to raw PCM. */ }
    fun setOutputFile(path: String) { target = File(path) }
    fun prepare() {
        checkNotNull(target) { "Target PCM file not configured" }
        target?.parentFile?.mkdirs()
    }

    fun start() {
        check(active.compareAndSet(false, true)) { "Capture already running" }
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        check(min > 0) { "AudioRecord unavailable: $min" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, maxOf(min * 2, 8192))
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            active.set(false)
            "AudioRecord initialization failed"
        }
        audio = recorder
        recorder.startRecording()
        worker = Thread({
            try {
                BufferedOutputStream(FileOutputStream(checkNotNull(target))).use { out ->
                    val buffer = ByteArray(4096)
                    var written = 0
                    while (active.get() && written < MAX_CAPTURE_BYTES) {
                        val n = recorder.read(buffer, 0, minOf(buffer.size, MAX_CAPTURE_BYTES - written), AudioRecord.READ_BLOCKING)
                        if (n > 0) {
                            out.write(buffer, 0, n)
                            written += n
                            // Hard stop at 12 s of PCM16 mono 16 kHz; prevents unbounded cache/RAM use.
                            if (written >= MAX_CAPTURE_BYTES) active.set(false)
                        } else if (n < 0) {
                            failure = "AudioRecord read error: $n"
                            active.set(false)
                        }
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                failure = e.message ?: "PCM write error"
                active.set(false)
            }
        }, "ia-pcm-capture").apply { priority = Thread.NORM_PRIORITY }.also { it.start() }
    }

    fun stop() {
        active.set(false)
        runCatching { audio?.stop() }
        worker?.join(2500)
        audio?.release()
        audio = null
        worker = null
        failure?.let { throw IllegalStateException(it) }
    }

    fun release() {
        active.set(false)
        runCatching { audio?.stop() }
        runCatching { worker?.join(1200) }
        runCatching { audio?.release() }
        audio = null
        worker = null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_CAPTURE_BYTES = 12 * SAMPLE_RATE * 2
    }
}
