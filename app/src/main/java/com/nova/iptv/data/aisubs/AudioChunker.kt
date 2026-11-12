package com.nova.iptv.data.aisubs

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Takes the sound of an episode and writes it as small speech files (mono, 16 kHz, AAC ~32 kbit/s, 10 minutes each:
 * about 2.4 MB — well under the transcription service's 25 MB per request). Only the sound is kept on the device.
 */
object AudioChunker {
    const val CHUNK_SEC = 600
    private const val RATE = 16_000
    private const val BITRATE = 32_000

    class Chunk(val file: File, val startSec: Double)

    /** What the last run saw (sample rate, channels, loudness) or why it failed — shown on the series page on errors. */
    @Volatile var lastInfo: String = ""

    /**
     * Writes the chunks into [dir] and returns them in order. [onChunk] is told about every finished chunk (so work
     * can start on it at once); [keepGoing] is asked often — false stops (returns null).
     */
    fun split(url: String, headers: Map<String, String>, dir: File, keepGoing: () -> Boolean, onProgress: (Double) -> Unit): List<Chunk>? {
        dir.mkdirs()
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        val out = ArrayList<Chunk>()
        var writer: ChunkWriter? = null
        try {
            ex.setDataSource(url, headers)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val total = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).apply { configure(fmt, null, null, 0); start() }
            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            var formatRead = false
            var sumSq = 0.0; var count = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var pos = 0.0          // resampler position in input samples
            var written = 0L       // 16 kHz samples written in the current chunk
            var chunkStart = 0.0
            var checks = 0
            val buf = ShortArray(RATE)   // one second at a time to the encoder
            var bufN = 0

            fun newChunk(atSec: Double) {
                writer?.finish()?.let { out += Chunk(it, chunkStart) }
                chunkStart = atSec
                writer = ChunkWriter(File(dir, "part_${out.size}.m4a"))
                written = 0
            }
            fun flush() {
                if (bufN > 0) { writer?.write(buf, bufN); written += bufN; bufN = 0 }
            }

            while (true) {
                if (++checks % 64 == 0 && !keepGoing()) return null
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    formatRead = true
                    val f = dec.outputFormat
                    inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    if (!formatRead) {
                        formatRead = true
                        runCatching { dec.getOutputFormat(o) }.getOrNull()?.let { f ->
                            if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                    val t = info.presentationTimeUs / 1e6
                    if (writer == null) newChunk(max(0.0, t))
                    if (info.size > 0) {
                        val b = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val frames = if (pcmFloat) info.size / (4 * channels) else info.size / (2 * channels)
                        val fb = if (pcmFloat) b.asFloatBuffer() else null
                        val sb = if (!pcmFloat) b.asShortBuffer() else null
                        val step = inRate.toDouble() / RATE
                        // fold to mono, pick every step-th sample (simple resampling is fine for speech recognition)
                        var frame = 0
                        while (frame < frames) {
                            if (pos <= frame) {
                                var s = 0f
                                for (c in 0 until channels) s += if (pcmFloat) fb!!.get(frame * channels + c) else sb!!.get(frame * channels + c) / 32768f
                                val v = s / channels
                                sumSq += v * v; count++
                                buf[bufN++] = (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                                if (bufN == buf.size) flush()
                                pos += step
                            } else frame++
                        }
                        pos -= frames
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (written + bufN >= CHUNK_SEC.toLong() * RATE) { flush(); newChunk(chunkStart + CHUNK_SEC) }
                    if (total > 0) onProgress((t / total).coerceIn(0.0, 1.0))
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            flush()
            writer?.finish()?.let { out += Chunk(it, chunkStart) }
            writer = null
            lastInfo = "${inRate}Hz ${channels}ch ${if (pcmFloat) "f32" else "s16"} rms=${"%.4f".format(if (count > 0) Math.sqrt(sumSq / count) else 0.0)}"
            if (count > 0 && Math.sqrt(sumSq / count) < 0.0005) { lastInfo = "silent audio ($lastInfo)"; return null }
            return out
        } catch (e: Exception) {
            lastInfo = "audio: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}"
            return null
        } finally {
            runCatching { writer?.finish() }
            runCatching { dec?.stop() }; runCatching { dec?.release() }; runCatching { ex.release() }
        }
    }

    /** 16 kHz mono PCM → AAC in an .m4a file. */
    private class ChunkWriter(private val file: File) {
        private val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start()
        }
        private val mux = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var track = -1
        private var samples = 0L
        private val info = MediaCodec.BufferInfo()

        fun write(pcm: ShortArray, n: Int) {
            var off = 0
            while (off < n) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val ib = enc.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                    ib.clear()
                    val take = minOf(n - off, ib.capacity() / 2)
                    ib.asShortBuffer().put(pcm, off, take)
                    enc.queueInputBuffer(i, 0, take * 2, samples * 1_000_000L / RATE, 0)
                    samples += take; off += take
                }
                drain(false)
            }
        }

        private fun drain(end: Boolean) {
            while (true) {
                val o = enc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(enc.outputFormat); mux.start() }
                    o >= 0 -> {
                        val ob = enc.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) mux.writeSampleData(track, ob, info)
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> if (!end) return
                }
            }
        }

        private var done = false
        fun finish(): File? {
            if (done) return null
            done = true
            return runCatching {
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) enc.queueInputBuffer(i, 0, 0, samples * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
                enc.stop(); enc.release()
                if (track >= 0) mux.stop()
                mux.release()
                if (samples > RATE) file else null
            }.getOrNull()
        }
    }
}
