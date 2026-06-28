package com.mystt.app.stt

import android.content.Context
import android.net.Uri
import com.mystt.app.log.AppLogger
import com.mystt.app.media.ChunkFile
import com.mystt.app.media.ChunkedDecoder
import com.mystt.app.media.MediaProbe
import com.mystt.app.model.JobProgress
import com.mystt.app.model.JobResult
import com.mystt.app.model.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 대용량 STT 파이프라인 (조각 디코딩 → 병렬 STT → 병합).
 *
 * - 생산자: ChunkedDecoder 가 무음 경계로 잘라 16k WAV 청크를 스트리밍.
 * - 채널(용량 제한)로 백프레셔 → 디스크에 청크가 무한정 쌓이지 않음.
 * - 소비자(N개): 각 청크를 Groq STT(재시도/백오프) 처리 후 즉시 WAV 삭제.
 * - 한 청크가 끝내 실패해도 전체를 중단하지 않고 표식만 남겨 부분 결과를 보존.
 * - 취소 시에도 지금까지 인식분을 .txt 로 부분 저장.
 */
class TranscriptionPipeline(
    private val context: Context,
    private val token: String,
    private val model: String,
    private val language: String,
    private val chunkSeconds: Int,
    private val concurrency: Int
) {
    private val cancel = AtomicBoolean(false)
    fun requestCancel() { cancel.set(true) }

    suspend fun run(
        uri: Uri,
        displayBase: String,
        onProgress: (JobProgress) -> Unit
    ): JobResult = coroutineScope {
        val started = System.currentTimeMillis()
        val info = MediaProbe.probe(context, uri)
        val totalDurMs = info.durationMs
        val estTotal = if (totalDurMs > 0)
            Math.ceil(totalDurMs.toDouble() / (chunkSeconds * 1000.0)).toInt().coerceAtLeast(1) else 0

        onProgress(JobProgress(Phase.DECODING, "디코딩 준비 중...", 0f, estTotal, 0))

        val workDir = File(context.cacheDir, "jobs/${System.currentTimeMillis()}").apply { mkdirs() }
        val texts = ConcurrentHashMap<Int, String>()
        val failed = ConcurrentHashMap<Int, String>()
        val doneCount = AtomicInteger(0)
        val totalChunks = AtomicInteger(estTotal)
        val decodedMs = AtomicInteger(0)
        val cap = (concurrency + 2).coerceAtLeast(2)
        val channel = Channel<ChunkFile>(capacity = cap)
        val client = GroqSttClient(token)

        fun assembledPrefix(): String {
            // index 0부터 연속으로 존재하는 부분만 이어붙여 안정적 미리보기 제공
            val sb = StringBuilder()
            var i = 0
            while (true) {
                val t = texts[i] ?: failed[i]?.let { "[조각 ${i+1} 인식 실패]" } ?: break
                if (t.isNotBlank()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(t) }
                i++
            }
            return sb.toString()
        }

        fun pushProgress(phase: Phase, msg: String) {
            val tot = totalChunks.get().coerceAtLeast(1)
            val done = doneCount.get()
            val decFrac = if (totalDurMs > 0) (decodedMs.get().toFloat() / totalDurMs).coerceIn(0f, 1f) else 0f
            // 전체 진행률: 디코딩 25% 가중 + STT 75% 가중
            val overall = (decFrac * 0.25f + (done.toFloat() / tot) * 0.75f).coerceIn(0f, 1f)
            onProgress(JobProgress(phase, msg, overall, tot, done, assembledPrefix(), durationMs = totalDurMs))
        }

        // ---- 생산자: 디코딩(별도 IO 스레드) ----
        val producer = launch(Dispatchers.IO) {
            try {
                val decoder = ChunkedDecoder(context, targetSec = chunkSeconds)
                val n = decoder.decode(
                    uri = uri,
                    workDir = workDir,
                    cancel = cancel,
                    onDuration = { },
                    onProgress = { ms -> decodedMs.set(ms.toInt()); pushProgress(Phase.DECODING, "디코딩/조각화 중...") }
                ) { cf ->
                    runBlocking { channel.send(cf) }   // 채널이 차면 대기 → 디스크/메모리 보호
                }
                totalChunks.set(n.coerceAtLeast(doneCount.get()))
            } catch (e: Exception) {
                AppLogger.e("Pipeline", "디코딩 오류", e)
            } finally {
                channel.close()
            }
        }

        // ---- 소비자: STT N개 ----
        val workers = (0 until concurrency).map {
            launch(Dispatchers.IO) {
                for (cf in channel) {
                    if (cancel.get()) break
                    try {
                        val text = sttWithRetry(client, cf.file, model, language)
                        texts[cf.index] = text
                    } catch (e: Exception) {
                        failed[cf.index] = e.message ?: "실패"
                        AppLogger.w("Pipeline", "조각 ${cf.index} 실패: ${e.message}")
                    } finally {
                        cf.file.delete()
                        doneCount.incrementAndGet()
                        pushProgress(Phase.TRANSCRIBING, "음성 인식 중...")
                    }
                }
            }
        }

        producer.join()
        workers.joinAll()

        pushProgress(Phase.MERGING, "병합 중...")
        // 최종 병합: index 순서대로
        val maxIdx = (texts.keys + failed.keys).maxOrNull() ?: -1
        val sb = StringBuilder()
        for (i in 0..maxIdx) {
            val t = texts[i] ?: failed[i]?.let { "[조각 ${i+1} 인식 실패: $it]" } ?: continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(t)
        }
        val merged = sb.toString().trim()

        val canceled = cancel.get()
        val outFile = saveTranscript(displayBase, merged, canceled)
        workDir.deleteRecursively()

        val elapsed = (System.currentTimeMillis() - started) / 1000
        val failCount = failed.size
        val okCount = texts.size
        val msg = when {
            canceled -> "중단됨 — 인식 ${okCount}조각 부분 저장 (${elapsed}초)"
            failCount > 0 -> "완료(일부 실패) — 성공 ${okCount} / 실패 ${failCount} (${elapsed}초)"
            else -> "완료 — ${okCount}조각, ${elapsed}초"
        }
        AppLogger.i("Pipeline", msg)
        onProgress(JobProgress(if (canceled) Phase.CANCELED else Phase.DONE, msg, 1f,
            totalChunks.get(), doneCount.get(), merged, outFile.absolutePath, totalDurMs))
        JobResult(!canceled || okCount > 0, msg, merged, outFile.absolutePath)
    }

    /** 429/5xx 는 지수 백오프 재시도, 그 외 4xx 는 즉시 실패. */
    private suspend fun sttWithRetry(
        client: GroqSttClient, wav: File, model: String, language: String
    ): String {
        var attempt = 0
        val maxAttempts = 6
        while (true) {
            if (cancel.get()) throw RuntimeException("취소됨")
            try {
                return client.transcribeOnce(wav, model, language)
            } catch (e: SttHttpException) {
                val retryable = e.code == 429 || e.code in 500..599
                attempt++
                if (!retryable || attempt >= maxAttempts) throw e
                val backoff = (1000L * (1 shl (attempt - 1))).coerceAtMost(30_000L)
                AppLogger.w("STT", "HTTP ${e.code} 재시도 ${attempt}/${maxAttempts} (${backoff}ms 대기)")
                delay(backoff)
            } catch (e: Exception) {
                attempt++
                if (attempt >= maxAttempts) throw e
                delay((1000L * attempt).coerceAtMost(10_000L))
            }
        }
    }

    private fun saveTranscript(base: String, text: String, partial: Boolean): File {
        val dir = File(context.getExternalFilesDir(null), "transcripts").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val suffix = if (partial) "_부분" else ""
        val f = File(dir, "${base}_${stamp}${suffix}.txt")
        f.writeText(text)
        return f
    }
}
