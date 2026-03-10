package com.metrolist.music.desktop.recognition

import com.metrolist.shazamkit.Shazam
import com.metrolist.shazamkit.models.RecognitionResult
import com.metrolist.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.sound.sampled.*
import kotlin.math.*

/**
 * Desktop music recognition using microphone capture + Shazam fingerprinting.
 *
 * Records 12 seconds of audio from the default microphone at 44.1kHz,
 * resamples to 16kHz (Shazam's required format), generates a Vibra/Shazam
 * signature, and sends it to the Shazam API for recognition.
 */
object DesktopMusicRecognizer {

    private const val RECORD_SAMPLE_RATE = 44100
    private const val TARGET_SAMPLE_RATE = 16000
    private const val RECORD_DURATION_SECONDS = 12
    private const val SAMPLE_SIZE_BITS = 16
    private const val CHANNELS = 1

    /**
     * Record audio from the microphone and recognize the song.
     *
     * @param onStatusChange callback for status updates (listening, processing, etc.)
     * @return Recognition result or error
     */
    suspend fun recognize(
        onStatusChange: (RecognitionStatus) -> Unit = {}
    ): Result<RecognitionResult> = withContext(Dispatchers.IO) {
        try {
            // 1. Record audio
            onStatusChange(RecognitionStatus.Listening)
            val rawPcm = recordAudio()

            if (!isActive) return@withContext Result.failure(Exception("Cancelled"))

            // 2. Resample from 44.1kHz to 16kHz
            onStatusChange(RecognitionStatus.Processing)
            val resampled = resample(rawPcm, RECORD_SAMPLE_RATE, TARGET_SAMPLE_RATE)

            // 3. Generate Shazam signature
            val signature = ShazamSignatureGeneratorDesktop.fromI16(resampled)
            val sampleDurationMs = (RECORD_DURATION_SECONDS * 1000).toLong()

            // 4. Send to Shazam API
            val result = Shazam.recognize(signature, sampleDurationMs)

            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e("Recognition failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Check if a microphone is available on this system.
     */
    fun isMicrophoneAvailable(): Boolean {
        return try {
            val format = AudioFormat(
                RECORD_SAMPLE_RATE.toFloat(),
                SAMPLE_SIZE_BITS,
                CHANNELS,
                true,
                false
            )
            val info = DataLine.Info(TargetDataLine::class.java, format)
            AudioSystem.isLineSupported(info)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Record audio from the default microphone.
     *
     * @return ByteArray of raw PCM data (16-bit signed LE, mono, 44.1kHz)
     */
    private fun recordAudio(): ByteArray {
        val format = AudioFormat(
            RECORD_SAMPLE_RATE.toFloat(),
            SAMPLE_SIZE_BITS,
            CHANNELS,
            true, // signed
            false // little-endian
        )

        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            throw Exception("No microphone available. Please connect a microphone and try again.")
        }

        val line = AudioSystem.getLine(info) as TargetDataLine
        line.open(format)
        line.start()

        val totalBytes = RECORD_SAMPLE_RATE * RECORD_DURATION_SECONDS * (SAMPLE_SIZE_BITS / 8) * CHANNELS
        val buffer = ByteArray(4096)
        val output = ByteArrayOutputStream(totalBytes)

        try {
            var bytesRead = 0
            while (bytesRead < totalBytes) {
                val toRead = minOf(buffer.size, totalBytes - bytesRead)
                val read = line.read(buffer, 0, toRead)
                if (read > 0) {
                    output.write(buffer, 0, read)
                    bytesRead += read
                }
            }
        } finally {
            line.stop()
            line.close()
        }

        return output.toByteArray()
    }

    /**
     * Linear interpolation resampler from [srcRate] to [dstRate].
     *
     * Input and output are both 16-bit signed little-endian mono PCM.
     */
    private fun resample(input: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        if (srcRate == dstRate) return input

        val srcSamples = input.size / 2
        val srcBuf = java.nio.ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        val srcShorts = ShortArray(srcSamples) { srcBuf.short }

        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstSamples = (srcSamples / ratio).toInt()
        val dstShorts = ShortArray(dstSamples)

        for (i in 0 until dstSamples) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx

            val s0 = srcShorts[minOf(srcIdx, srcSamples - 1)].toInt()
            val s1 = srcShorts[minOf(srcIdx + 1, srcSamples - 1)].toInt()
            dstShorts[i] = (s0 + (s1 - s0) * frac).toInt().toShort()
        }

        val output = ByteArray(dstSamples * 2)
        val dstBuf = java.nio.ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        for (s in dstShorts) {
            dstBuf.putShort(s)
        }

        return output
    }
}

/**
 * Desktop-specific Shazam signature generator.
 *
 * Identical to the Android version but uses java.util.Base64 instead of android.util.Base64.
 * Delegates the actual FFT and peak detection to the same algorithm.
 */
internal object ShazamSignatureGeneratorDesktop {

    private const val SAMPLE_RATE = 16_000
    private const val FFT_SIZE = 2048
    private const val FFT_OUTPUT_SIZE = FFT_SIZE / 2 + 1
    private const val MAX_PEAKS = 255
    private const val MAX_TIME_SECONDS = 12.0
    private const val RING_BUF_SIZE = 256

    private const val BAND_250_520 = 0
    private const val BAND_520_1450 = 1
    private const val BAND_1450_3500 = 2
    private const val BAND_3500_5500 = 3

    private val HANNING = DoubleArray(FFT_SIZE) { i ->
        0.5 * (1.0 - cos(2.0 * PI * (i + 1).toDouble() / 2049.0))
    }

    fun fromI16(samples: ByteArray): String {
        require(samples.size >= 2 && samples.size % 2 == 0) {
            "samples must be a non-empty byte array with even length (16-bit PCM)"
        }
        val pcm = ShortArray(samples.size / 2)
        java.nio.ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        return SignatureGeneratorState().process(pcm)
    }

    private class SignatureGeneratorState {
        private val samplesRing = IntArray(FFT_SIZE)
        private var samplesPos = 0
        private val fftOutputs = Array(RING_BUF_SIZE) { DoubleArray(FFT_OUTPUT_SIZE) }
        private var fftPos = 0
        private var fftNumWritten = 0
        private val spreadFfts = Array(RING_BUF_SIZE) { DoubleArray(FFT_OUTPUT_SIZE) }
        private var spreadPos = 0
        private var spreadNumWritten = 0
        private var numSamples = 0
        private val bandPeaks = Array(4) { mutableListOf<FrequencyPeak>() }
        private var totalPeaks = 0

        fun process(pcm: ShortArray): String {
            var offset = 0
            while (offset + 128 <= pcm.size) {
                val elapsedSec = numSamples.toDouble() / SAMPLE_RATE
                if (elapsedSec >= MAX_TIME_SECONDS && totalPeaks >= MAX_PEAKS) break
                numSamples += 128
                feedSamples(pcm, offset, 128)
                doFFT()
                doPeakSpreadingAndRecognition()
                offset += 128
            }
            return encodeSignature()
        }

        private fun feedSamples(pcm: ShortArray, start: Int, count: Int) {
            for (k in start until start + count) {
                samplesRing[samplesPos] = pcm[k].toInt()
                samplesPos = (samplesPos + 1) % FFT_SIZE
            }
        }

        private fun doFFT() {
            val windowed = DoubleArray(FFT_SIZE) { i ->
                samplesRing[(samplesPos + i) % FFT_SIZE].toDouble() * HANNING[i]
            }
            val result = computeRfft(windowed)
            result.copyInto(fftOutputs[fftPos])
            fftPos = (fftPos + 1) % RING_BUF_SIZE
            fftNumWritten++
        }

        private fun doPeakSpreadingAndRecognition() {
            doPeakSpreading()
            if (spreadNumWritten >= 47) {
                doPeakRecognition()
            }
        }

        private fun doPeakSpreading() {
            val lastFftIdx = (fftPos - 1 + RING_BUF_SIZE) % RING_BUF_SIZE
            val spread = fftOutputs[lastFftIdx].copyOf()
            for (pos in 0 until FFT_OUTPUT_SIZE - 2) {
                spread[pos] = maxOf(spread[pos], spread[pos + 1], spread[pos + 2])
            }
            for (pos in 0 until FFT_OUTPUT_SIZE) {
                var maxVal = spread[pos]
                for (offset in intArrayOf(-1, -3, -6)) {
                    val idx = ((spreadPos + offset) % RING_BUF_SIZE + RING_BUF_SIZE) % RING_BUF_SIZE
                    val oldVal = spreadFfts[idx][pos]
                    if (oldVal > maxVal) maxVal = oldVal
                    spreadFfts[idx][pos] = maxVal
                }
            }
            spread.copyInto(spreadFfts[spreadPos])
            spreadPos = (spreadPos + 1) % RING_BUF_SIZE
            spreadNumWritten++
        }

        private fun doPeakRecognition() {
            val fftMinus46 = fftOutputs[(fftPos - 46 + RING_BUF_SIZE * 2) % RING_BUF_SIZE]
            val spreadMinus49 = spreadFfts[(spreadPos - 49 + RING_BUF_SIZE * 2) % RING_BUF_SIZE]
            val otherOffsets = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

            for (binPos in 10 until FFT_OUTPUT_SIZE - 8) {
                val fftVal = fftMinus46[binPos]
                if (fftVal < 1.0 / 64.0 || fftVal < spreadMinus49[binPos]) continue

                var maxNeighborSpread49 = 0.0
                for (neighborOffset in intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)) {
                    val v = spreadMinus49[binPos + neighborOffset]
                    if (v > maxNeighborSpread49) maxNeighborSpread49 = v
                }
                if (fftVal <= maxNeighborSpread49) continue

                var maxNeighborOther = maxNeighborSpread49
                for (otherOffset in otherOffsets) {
                    val spreadIdx = ((spreadPos + otherOffset) % RING_BUF_SIZE + RING_BUF_SIZE) % RING_BUF_SIZE
                    val v = spreadFfts[spreadIdx][binPos - 1]
                    if (v > maxNeighborOther) maxNeighborOther = v
                }
                if (fftVal <= maxNeighborOther) continue

                val fftNumber = spreadNumWritten - 46
                val peakMag = ln(max(1.0 / 64.0, fftVal)) * 1477.3 + 6144
                val peakMagBefore = ln(max(1.0 / 64.0, fftMinus46[binPos - 1])) * 1477.3 + 6144
                val peakMagAfter = ln(max(1.0 / 64.0, fftMinus46[binPos + 1])) * 1477.3 + 6144
                val peakVariation1 = peakMag * 2 - peakMagBefore - peakMagAfter
                val peakVariation2 = (peakMagAfter - peakMagBefore) * 32 / peakVariation1
                val correctedBin = binPos * 64.0 + peakVariation2
                val frequencyHz = correctedBin * (16000.0 / 2.0 / 1024.0 / 64.0)

                val band = when {
                    frequencyHz < 250.0 -> continue
                    frequencyHz < 520.0 -> BAND_250_520
                    frequencyHz < 1450.0 -> BAND_520_1450
                    frequencyHz < 3500.0 -> BAND_1450_3500
                    frequencyHz <= 5500.0 -> BAND_3500_5500
                    else -> continue
                }

                bandPeaks[band].add(
                    FrequencyPeak(
                        fftPassNumber = fftNumber,
                        peakMagnitude = peakMag.toInt(),
                        correctedPeakFrequencyBin = correctedBin.toInt()
                    )
                )
                totalPeaks++
            }
        }

        private fun encodeSignature(): String {
            val contentsStream = ByteArrayOutputStream()
            for (bandId in 0..3) {
                val peaks = bandPeaks[bandId]
                if (peaks.isEmpty()) continue
                val peakBuf = ByteArrayOutputStream()
                var prevFftPassNumber = 0
                for (peak in peaks) {
                    val diff = peak.fftPassNumber - prevFftPassNumber
                    if (diff >= 255) {
                        peakBuf.write(0xFF)
                        writeLittleEndian32(peakBuf, peak.fftPassNumber)
                        prevFftPassNumber = peak.fftPassNumber
                    }
                    peakBuf.write(peak.fftPassNumber - prevFftPassNumber)
                    writeLittleEndian16(peakBuf, peak.peakMagnitude)
                    writeLittleEndian16(peakBuf, peak.correctedPeakFrequencyBin)
                    prevFftPassNumber = peak.fftPassNumber
                }
                val peakBytes = peakBuf.toByteArray()
                writeLittleEndian32(contentsStream, 0x60030040 + bandId)
                writeLittleEndian32(contentsStream, peakBytes.size)
                contentsStream.write(peakBytes)
                val padBytes = (4 - peakBytes.size % 4) % 4
                repeat(padBytes) { contentsStream.write(0) }
            }

            val contents = contentsStream.toByteArray()
            val sizeMinusHeader = contents.size + 8
            val samplesAndOffset = (numSamples + SAMPLE_RATE * 0.24).toInt()

            val headerBytes = java.nio.ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0xcafe2580.toInt())
                putInt(0) // crc32 placeholder
                putInt(sizeMinusHeader)
                putInt(0x94119c00.toInt())
                putInt(0); putInt(0); putInt(0)
                putInt(3 shl 27)
                putInt(0); putInt(0)
                putInt(samplesAndOffset)
                putInt((15 shl 19) + 0x40000)
            }.array()

            val fullBuf = ByteArrayOutputStream(56 + contents.size)
            fullBuf.write(headerBytes)
            writeLittleEndian32(fullBuf, 0x40000000)
            writeLittleEndian32(fullBuf, contents.size + 8)
            fullBuf.write(contents)
            val fullBytes = fullBuf.toByteArray()

            val crc = CRC32()
            crc.update(fullBytes, 8, fullBytes.size - 8)
            val crc32Value = crc.value.toInt()
            fullBytes[4] = (crc32Value and 0xFF).toByte()
            fullBytes[5] = ((crc32Value shr 8) and 0xFF).toByte()
            fullBytes[6] = ((crc32Value shr 16) and 0xFF).toByte()
            fullBytes[7] = ((crc32Value shr 24) and 0xFF).toByte()

            // Use java.util.Base64 instead of android.util.Base64
            val base64 = java.util.Base64.getEncoder().encodeToString(fullBytes)
            return "data:audio/vnd.shazam.sig;base64,$base64"
        }
    }

    private data class FrequencyPeak(
        val fftPassNumber: Int,
        val peakMagnitude: Int,
        val correctedPeakFrequencyBin: Int
    )

    private fun writeLittleEndian32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeLittleEndian16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun computeRfft(windowed: DoubleArray): DoubleArray {
        val n = windowed.size
        val re = windowed.copyOf()
        val im = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n ushr 1
            while (j and bit != 0) { j = j xor bit; bit = bit ushr 1 }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val halfLen = len ushr 1
            val ang = -PI / halfLen
            val wBaseRe = cos(ang)
            val wBaseIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0; var wIm = 0.0
                for (k in 0 until halfLen) {
                    val u = i + k; val v = u + halfLen
                    val evenRe = re[u]; val evenIm = im[u]
                    val oddRe = re[v] * wRe - im[v] * wIm
                    val oddIm = re[v] * wIm + im[v] * wRe
                    re[u] = evenRe + oddRe; im[u] = evenIm + oddIm
                    re[v] = evenRe - oddRe; im[v] = evenIm - oddIm
                    val newWRe = wRe * wBaseRe - wIm * wBaseIm
                    wIm = wRe * wBaseIm + wIm * wBaseRe; wRe = newWRe
                }
                i += len
            }
            len = len shl 1
        }
        val scaleFactor = 1.0 / (1 shl 17)
        val minVal = 1e-10
        return DoubleArray(FFT_OUTPUT_SIZE) { idx ->
            val r = re[idx]; val img = im[idx]
            val mag = (r * r + img * img) * scaleFactor
            if (mag < minVal) minVal else mag
        }
    }
}
