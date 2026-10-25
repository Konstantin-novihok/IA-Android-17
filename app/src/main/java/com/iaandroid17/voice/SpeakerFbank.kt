package com.iaandroid17.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Kaldi-style 80-bin log-mel filterbank for the CAM++ checkpoint used by SpeakerEmbeddingEngine.
 * Audio is 16 kHz mono float in [-1,1]. Config follows the checkpoint's sherpa-onnx feature
 * defaults: 25 ms frames, 10 ms shift, Povey window, no dither, pre-emphasis 0.97, DC removal,
 * 20 Hz .. Nyquist-400 Hz. Keep this pipeline coupled to the pinned model revision.
 */
internal class SpeakerFbank {
    private val sampleRate = 16_000
    private val bins = 80
    private val frameLength = 400
    private val frameShift = 160
    private val fftSize = 512
    private val window = DoubleArray(frameLength) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (frameLength - 1))).let { kotlin.math.pow(it, 0.85) }
    }
    private val filters = buildFilters()

    fun compute(samples: FloatArray): Array<FloatArray> {
        require(samples.isNotEmpty()) { "No audio samples" }
        val frameCount = (samples.size + frameShift / 2) / frameShift
        require(frameCount >= 20) { "Audio is too short for speaker embedding" }
        return Array(frameCount) { frameIndex ->
            val midpoint = frameIndex * frameShift + frameShift / 2
            val start = midpoint - frameLength / 2
            val frame = DoubleArray(frameLength) { i ->
                var idx = start + i
                while (idx < 0 || idx >= samples.size) {
                    idx = if (idx < 0) -idx - 1 else 2 * samples.size - 1 - idx
                }
                samples[idx].toDouble()
            }
            val mean = frame.average()
            for (i in frame.indices) frame[i] -= mean
            for (i in frame.size - 1 downTo 1) frame[i] -= 0.97 * frame[i - 1]
            frame[0] -= 0.97 * frame[0]
            for (i in frame.indices) frame[i] *= window[i]
            val padded = DoubleArray(fftSize)
            System.arraycopy(frame, 0, padded, 0, frame.size)
            val power = fftPower(padded)
            FloatArray(bins) { b ->
                var energy = 0.0
                for (i in power.indices) energy += filters[b][i] * power[i]
                ln(max(energy, 1.1920928955078125e-7)).toFloat()
            }
        }
    }

    private fun buildFilters(): Array<DoubleArray> {
        val low = mel(20.0)
        val high = mel(sampleRate / 2.0 - 400.0)
        val delta = (high - low) / (bins + 1)
        return Array(bins) { b ->
            val left = low + b * delta
            val center = low + (b + 1) * delta
            val right = low + (b + 2) * delta
            DoubleArray(fftSize / 2 + 1) { i ->
                val m = mel(i.toDouble() * sampleRate / fftSize)
                when {
                    m <= left || m >= right -> 0.0
                    m <= center -> (m - left) / (center - left)
                    else -> (right - m) / (right - center)
                }
            }
        }
    }

    private fun mel(hz: Double) = 1127.0 * ln(1.0 + hz / 700.0)

    private fun fftPower(input: DoubleArray): DoubleArray {
        val n = input.size
        val re = input.copyOf()
        val im = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = re[i]; re[i] = re[j]; re[j] = r
                val v = im[i]; im[i] = im[j]; im[j] = v
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wr = cos(angle); val wi = sin(angle)
            var offset = 0
            while (offset < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val p = offset + k; val q = p + len / 2
                    val vr = re[q] * cr - im[q] * ci
                    val vi = re[q] * ci + im[q] * cr
                    re[q] = re[p] - vr; im[q] = im[p] - vi
                    re[p] += vr; im[p] += vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                offset += len
            }
            len = len shl 1
        }
        return DoubleArray(n / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
    }
}
