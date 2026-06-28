package com.mystt.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.mystt.app.log.AppLogger
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 대용량 오디오/동영상을 "조각조각" 16kHz mono WAV 로 디코딩한다.
 *
 * 핵심: 파일 전체를 메모리에 올리지 않는다(기존 구현의 OOM 원인 제거).
 *  - MediaExtractor 로 오디오 트랙만 선택(동영상이면 비디오는 무시).
 *  - 디코딩 PCM 을 청크 버퍼에 누적하다가 목표 길이 부근의 "조용한 지점(무음)"에서 절단.
 *  - 각 청크를 16kHz 로 리샘플 후 WAV 파일로 즉시 내보내고 버퍼는 비운다.
 *  - 따라서 동시 점유 메모리는 청크 1개 분량(수 MB)으로 한정된다.
 *
 * onChunk 콜백으로 잘린 청크를 즉시 흘려보내 STT 파이프라인이 곧바로 인식을 시작할 수 있다.
 */
class ChunkedDecoder(
    private val context: Context,
    private val targetSec: Int = 120,        // 목표 청크 길이(초)
    private val windowSec: Int = 15,         // 무음 탐색 ± 창(초)
    private val minSec: Int = 8              // 최소 청크 길이(초)
) {
    private val TARGET_RATE = 16000

    fun decode(
        uri: Uri,
        workDir: File,
        cancel: AtomicBoolean,
        onDuration: (Long) -> Unit = {},
        onProgress: (decodedMs: Long) -> Unit = {},
        onChunk: (ChunkFile) -> Unit
    ): Int {
        workDir.mkdirs()
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i; format = f; break
            }
        }
        require(trackIndex >= 0 && format != null) { "오디오 트랙을 찾을 수 없습니다." }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) else 0L
        onDuration(durationUs / 1000)
        AppLogger.i("Decoder", "트랙 $mime ${srcRate}Hz ${channels}ch dur=${durationUs/1000}ms")

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val targetSamples = targetSec * srcRate
        val windowSamples = windowSec * srcRate
        val minSamples = minSec * srcRate
        val flushAt = targetSamples + windowSamples
        // 버퍼는 최대 (목표+창) + 디코드 한 프레임 여유. 넉넉히 +2초.
        val cap = flushAt + srcRate * 2
        var buf = ShortArray(cap)
        var len = 0
        var globalSampleCursor = 0L  // 지금까지 "내보낸" 누적 mono 샘플 수(srcRate 기준)
        var chunkIndex = 0

        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        fun emit(cutSamples: Int) {
            if (cutSamples <= 0) return
            val mono = ShortArray(cutSamples)
            System.arraycopy(buf, 0, mono, 0, cutSamples)
            val startMs = globalSampleCursor * 1000 / srcRate
            globalSampleCursor += cutSamples
            val endMs = globalSampleCursor * 1000 / srcRate
            val pcm16 = resampleToTarget(mono, srcRate)
            val f = File(workDir, "chunk_%04d.wav".format(chunkIndex))
            WavWriter.writeWav(f, pcm16, TARGET_RATE, 1)
            onChunk(ChunkFile(chunkIndex, f, startMs, endMs))
            chunkIndex++
            // 남은 부분 앞으로 당기기
            val remain = len - cutSamples
            if (remain > 0) System.arraycopy(buf, cutSamples, buf, 0, remain)
            len = remain
        }

        try {
            while (!sawOutputEOS) {
                if (cancel.get()) { AppLogger.w("Decoder", "사용자 취소"); break }

                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf: ByteBuffer = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(inBuf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        appendMono(outBuf, info.size, channels) { s ->
                            if (len >= buf.size) {
                                // 안전장치: 무음 못 찾고 가득 차면 강제 절단
                                emit(len)
                            }
                            buf[len++] = s
                        }
                        onProgress(globalSampleCursor.let { (it + len) * 1000 / srcRate })
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                }

                // 충분히 쌓이면 무음 지점에서 절단
                while (len >= flushAt) {
                    val cut = findSilenceCut(buf, len, targetSamples, windowSamples, minSamples, srcRate)
                    emit(cut)
                }
            }
            // 잔여분 마지막 청크로
            if (!cancel.get() && len > 0) emit(len)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release(); extractor.release()
        }
        return chunkIndex
    }

    /** 디코더 PCM16(interleaved) → mono 샘플 콜백 */
    private inline fun appendMono(bb: ByteBuffer, size: Int, channels: Int, push: (Short) -> Unit) {
        val frameBytes = 2 * channels
        var i = 0
        while (i + frameBytes <= size) {
            var sum = 0
            for (c in 0 until channels) {
                val lo = bb.get().toInt() and 0xff
                val hi = bb.get().toInt()
                sum += (hi shl 8) or lo
            }
            push((sum / channels).toShort())
            i += frameBytes
        }
        // 잔여 바이트(채널 정렬 안 맞는 꼬리) 버림
        while (i < size) { bb.get(); i++ }
    }

    /**
     * [target-window, target+window] 구간에서 30ms 프레임 RMS 가 가장 낮은 지점을 절단점으로.
     * 자연스러운 말 사이 공백에서 끊어 단어가 잘리지 않게 한다.
     */
    private fun findSilenceCut(
        b: ShortArray, len: Int, target: Int, window: Int, minSamples: Int, srcRate: Int
    ): Int {
        val lo = (target - window).coerceAtLeast(minSamples)
        val hi = (target + window).coerceAtMost(len)
        if (hi <= lo) return target.coerceIn(minSamples, len)
        val frame = (srcRate * 0.03).toInt().coerceAtLeast(160)
        var bestPos = target.coerceIn(lo, hi)
        var bestRms = Double.MAX_VALUE
        var p = lo
        while (p + frame <= hi) {
            var acc = 0.0
            var k = 0
            while (k < frame) { val v = b[p + k].toDouble(); acc += v * v; k++ }
            val rms = Math.sqrt(acc / frame)
            if (rms < bestRms) { bestRms = rms; bestPos = p + frame }
            p += frame
        }
        return bestPos.coerceIn(minSamples, len)
    }

    /** 선형 보간 리샘플 (mono shorts, srcRate → 16k) → PCM16 LE 바이트 */
    private fun resampleToTarget(src: ShortArray, srcRate: Int): ByteArray {
        if (srcRate == TARGET_RATE) {
            val out = ByteArray(src.size * 2)
            for (n in src.indices) {
                out[n*2] = (src[n].toInt() and 0xff).toByte()
                out[n*2+1] = ((src[n].toInt() shr 8) and 0xff).toByte()
            }
            return out
        }
        val ratio = TARGET_RATE.toDouble() / srcRate
        val dst = (src.size * ratio).toInt()
        val out = ByteArray(dst * 2)
        for (n in 0 until dst) {
            val sp = n / ratio
            val i0 = sp.toInt().coerceIn(0, src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = sp - i0
            val v = (src[i0] + (src[i1] - src[i0]) * frac).toInt()
            out[n*2] = (v and 0xff).toByte()
            out[n*2+1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }
}
