package com.mystt.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.mystt.app.log.AppLogger
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 동영상/오디오에서 "음원만" .m4a(AAC) 로 추출.
 *  1순위: 오디오 트랙 그대로 복사(재인코딩 없음 → 초대용량도 수초, 무손실).
 *  실패 시(코덱이 mp4 컨테이너에 못 들어갈 때): 디코드→AAC 재인코딩으로 폴백.
 *
 * 참고: 안드로이드 표준 인코더에는 MP3 가 없어 .mp3 직접 생성은 불가.
 *       .m4a(AAC)는 모든 플레이어에서 재생되고 동일 음질에 용량이 더 작습니다.
 */
object AudioExtractor {

    fun extractToM4a(
        context: Context,
        uri: Uri,
        outFile: File,
        cancel: AtomicBoolean,
        onProgress: (Float) -> Unit = {}
    ): File {
        return try {
            trackCopy(context, uri, outFile, cancel, onProgress)
        } catch (e: Exception) {
            AppLogger.w("Extract", "트랙복사 실패(${e.message}) → 재인코딩 폴백")
            reEncode(context, uri, outFile, cancel, onProgress)
        }
    }

    // ---- 1) 무손실 트랙 복사 --------------------------------------------------
    private fun trackCopy(
        context: Context, uri: Uri, outFile: File,
        cancel: AtomicBoolean, onProgress: (Float) -> Unit
    ): File {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var audioTrack = -1
        var fmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrack = i; fmt = f; break
            }
        }
        require(audioTrack >= 0 && fmt != null) { "오디오 트랙 없음" }
        val mimeOk = fmt.getString(MediaFormat.KEY_MIME)?.contains("mp4a") == true ||
                fmt.getString(MediaFormat.KEY_MIME)?.contains("aac") == true
        require(mimeOk) { "AAC 가 아니어서 트랙복사 불가" }

        extractor.selectTrack(audioTrack)
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val dstTrack = muxer.addTrack(fmt)
        muxer.start()

        val maxSize = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024) else 1 shl 20
        val buffer = ByteBuffer.allocate(maxSize)
        val info = MediaCodec.BufferInfo()
        try {
            while (!cancel.get()) {
                val sz = extractor.readSampleData(buffer, 0)
                if (sz < 0) break
                info.offset = 0
                info.size = sz
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(dstTrack, buffer, info)
                if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                extractor.advance()
            }
        } finally {
            try { muxer.stop() } catch (_: Exception) {}
            muxer.release(); extractor.release()
        }
        AppLogger.i("Extract", "트랙복사 완료 → ${outFile.name} (${outFile.length()/1024}KB)")
        onProgress(1f)
        return outFile
    }

    // ---- 2) 디코드 → AAC 재인코딩 폴백 ---------------------------------------
    private fun reEncode(
        context: Context, uri: Uri, outFile: File,
        cancel: AtomicBoolean, onProgress: (Float) -> Unit
    ): File {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var audioTrack = -1
        var inFmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrack = i; inFmt = f; break
            }
        }
        require(audioTrack >= 0 && inFmt != null) { "오디오 트랙 없음" }
        extractor.selectTrack(audioTrack)

        val mime = inFmt.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (inFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val durationUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION)) inFmt.getLong(MediaFormat.KEY_DURATION) else 0L

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inFmt, null, null, 0); decoder.start()

        val outFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxStarted = false

        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var sawInEOS = false; var sawDecEOS = false; var sawEncEOS = false

        try {
            while (!sawEncEOS && !cancel.get()) {
                if (!sawInEOS) {
                    val ii = decoder.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = decoder.getInputBuffer(ii)!!
                        val sz = extractor.readSampleData(ib, 0)
                        if (sz < 0) { decoder.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); sawInEOS = true }
                        else { decoder.queueInputBuffer(ii, 0, sz, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                // decoder → encoder
                if (!sawDecEOS) {
                    val oi = decoder.dequeueOutputBuffer(decInfo, 10_000)
                    if (oi >= 0) {
                        val ob = decoder.getOutputBuffer(oi)!!
                        val ei = encoder.dequeueInputBuffer(10_000)
                        if (ei >= 0) {
                            val eb = encoder.getInputBuffer(ei)!!
                            eb.clear()
                            ob.position(decInfo.offset); ob.limit(decInfo.offset + decInfo.size)
                            eb.put(ob)
                            val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.queueInputBuffer(ei, 0, decInfo.size, decInfo.presentationTimeUs,
                                if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (eos) sawDecEOS = true
                            if (durationUs > 0) onProgress((decInfo.presentationTimeUs.toFloat()/durationUs).coerceIn(0f,1f))
                        }
                        decoder.releaseOutputBuffer(oi, false)
                    }
                }
                // encoder → muxer
                var eo = encoder.dequeueOutputBuffer(encInfo, 0)
                while (eo >= 0) {
                    if (eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = muxer.addTrack(encoder.outputFormat); muxer.start(); muxStarted = true
                    } else {
                        val outB = encoder.getOutputBuffer(eo)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                        if (encInfo.size > 0 && muxStarted) {
                            outB.position(encInfo.offset); outB.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(muxTrack, outB, encInfo)
                        }
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEncEOS = true
                        encoder.releaseOutputBuffer(eo, false)
                    }
                    eo = encoder.dequeueOutputBuffer(encInfo, 0)
                }
            }
        } finally {
            try { decoder.stop() } catch (_: Exception) {}; decoder.release()
            try { encoder.stop() } catch (_: Exception) {}; encoder.release()
            try { if (muxStarted) muxer.stop() } catch (_: Exception) {}; muxer.release()
            extractor.release()
        }
        AppLogger.i("Extract", "재인코딩 완료 → ${outFile.name} (${outFile.length()/1024}KB)")
        onProgress(1f)
        return outFile
    }
}
