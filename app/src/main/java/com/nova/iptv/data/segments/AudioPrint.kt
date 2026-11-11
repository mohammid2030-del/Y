package com.nova.iptv.data.segments

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Audio fingerprints in the spirit of Plex / Jellyfin intro detection (Haitsma–Kalker style):
 * the sound is decoded, folded to mono ~5.5 kHz, cut into 0.37 s frames every ~93 ms, and each frame becomes 32 bits
 * that say whether the energy difference between neighbouring frequency bands rose or fell since the previous frame.
 * The same intro / credits music in two episodes gives (nearly) the same bits even with different encodes.
 */
object AudioPrint {
    const val RATE = 5512
    private const val FRAME = 2048
    private const val HOP = 512
    const val HOP_SEC = HOP.toDouble() / RATE
    private const val BANDS = 33

    class Window(val startSec: Double, val prints: IntArray)

    /**
     * Fingerprint of [fromSec, fromSec + lengthSec) of a stream (negative fromSec = that far before the end).
     * Returns null when the stream has no decodable audio. [keepGoing] is asked regularly; false stops early.
     */
    fun of(url: String, headers: Map<String, String>, fromSec: Double, lengthSec: Double, keepGoing: () -> Boolean): Window? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(url, headers)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val durSec = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
            val start = if (fromSec < 0) (if (durSec > 0) max(0.0, durSec + fromSec) else return null) else fromSec
            val end = start + lengthSec
            if (start > 0) ex.seekTo((start * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime).apply { configure(fmt, null, null, 0); start() }
            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val mono = FloatBuilder()
            var firstSec = -1.0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var acc = 0.0; var accN = 0; var step = max(1, Math.round(inRate.toDouble() / RATE).toInt())
            var checks = 0
            while (true) {
                if (++checks % 64 == 0 && !keepGoing()) return null
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (n < 0 || t / 1e6 > end) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, t, 0); ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        step = max(1, Math.round(inRate.toDouble() / RATE).toInt())
                    }
                    o >= 0 -> {
                        val t = info.presentationTimeUs / 1e6
                        if (info.size > 0 && t >= start - 0.05) {
                            if (firstSec < 0) firstSec = t
                            val b = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            b.position(info.offset); b.limit(info.offset + info.size)
                            // fold to mono and average down to ~5.5 kHz
                            if (pcmFloat) {
                                val fb = b.asFloatBuffer()
                                while (fb.remaining() >= channels) {
                                    var s = 0f; repeat(channels) { s += fb.get() }
                                    acc += s / channels; if (++accN == step) { mono.add((acc / accN).toFloat()); acc = 0.0; accN = 0 }
                                }
                            } else {
                                val sb = b.asShortBuffer()
                                while (sb.remaining() >= channels) {
                                    var s = 0; repeat(channels) { s += sb.get() }
                                    acc += s / (channels * 32768.0); if (++accN == step) { mono.add((acc / accN).toFloat()); acc = 0.0; accN = 0 }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || t > end) break
                    }
                }
            }
            if (firstSec < 0 || mono.size < FRAME * 4) return null
            return Window(firstSec, prints(mono.toArray(), inRate.toDouble() / step))
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { ex.release() }
        }
    }

    /** 32-bit sub-fingerprints of mono samples at [rate] Hz. */
    private fun prints(x: FloatArray, rate: Double): IntArray {
        val n = (x.size - FRAME) / HOP
        if (n <= 1) return IntArray(0)
        // 33 log-spaced bands between 300 and 2000 Hz
        val edges = IntArray(BANDS + 1) { b ->
            val f = 300.0 * Math.pow(2000.0 / 300.0, b.toDouble() / BANDS)
            min(FRAME / 2 - 1, (f / rate * FRAME).toInt())
        }
        val window = FloatArray(FRAME) { (0.5 - 0.5 * Math.cos(2 * Math.PI * it / (FRAME - 1))).toFloat() }
        val re = DoubleArray(FRAME); val im = DoubleArray(FRAME)
        val prev = DoubleArray(BANDS); val cur = DoubleArray(BANDS)
        val out = IntArray(n - 1)
        for (f in 0 until n) {
            val off = f * HOP
            for (i in 0 until FRAME) { re[i] = (x[off + i] * window[i]).toDouble(); im[i] = 0.0 }
            fft(re, im)
            for (b in 0 until BANDS) {
                var e = 0.0
                for (k in edges[b] until max(edges[b] + 1, edges[b + 1])) e += re[k] * re[k] + im[k] * im[k]
                cur[b] = ln(1e-9 + e)
            }
            if (f > 0) {
                var bits = 0
                for (b in 0 until 32) {
                    val d = (cur[b] - cur[b + 1]) - (prev[b] - prev[b + 1])
                    if (d > 0) bits = bits or (1 shl b)
                }
                out[f - 1] = bits
            }
            System.arraycopy(cur, 0, prev, 0, BANDS)
        }
        return out
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = Math.cos(ang); val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** The longest stretch of the same sound in both windows, as (start, end) seconds in each, or null. */
    data class Match(val aStart: Double, val aEnd: Double, val bStart: Double, val bEnd: Double)

    fun common(a: Window, b: Window, minSec: Double, maxSec: Double): Match? {
        val pa = a.prints; val pb = b.prints
        if (pa.isEmpty() || pb.isEmpty()) return null
        val minLen = (minSec / HOP_SEC).toInt(); val gap = 6
        var best = 0; var bestA = 0; var bestB = 0
        // every alignment of the two windows; a frame "matches" when ≤ 10 of its 32 bits differ
        for (shift in -(pb.size - minLen) until pa.size - minLen) {
            var i = max(0, shift); var run = 0; var runStart = -1; var miss = 0; var lastHit = -1
            while (i < pa.size && i - shift < pb.size) {
                val hit = Integer.bitCount(pa[i] xor pb[i - shift]) <= 10
                if (hit) {
                    if (runStart < 0) runStart = i
                    lastHit = i; miss = 0
                } else if (runStart >= 0 && ++miss > gap) {
                    run = lastHit - runStart + 1
                    if (run > best) { best = run; bestA = runStart; bestB = runStart - shift }
                    runStart = -1; miss = 0
                }
                i++
            }
            if (runStart >= 0) {
                run = lastHit - runStart + 1
                if (run > best) { best = run; bestA = runStart; bestB = runStart - shift }
            }
        }
        if (best < minLen) return null
        val len = min(best, (maxSec / HOP_SEC).toInt())
        // frame f covers [f*HOP, f*HOP + FRAME) of the window: report the middle of the hop
        val dur = len * HOP_SEC
        return Match(a.startSec + bestA * HOP_SEC, a.startSec + bestA * HOP_SEC + dur, b.startSec + bestB * HOP_SEC, b.startSec + bestB * HOP_SEC + dur)
    }

    /** Growable float list without boxing. */
    private class FloatBuilder {
        private var a = FloatArray(1 shl 16)
        var size = 0; private set
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray(): FloatArray = a.copyOf(size)
    }

    @Suppress("unused") private fun rms(x: FloatArray) = sqrt(x.fold(0.0) { s, v -> s + v * v } / max(1, x.size))
}
