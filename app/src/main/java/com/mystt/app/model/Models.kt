package com.mystt.app.model

/** 처리 모드 */
enum class JobMode { STT, EXTRACT_AUDIO }

/** 파이프라인 진행 단계 */
enum class Phase { IDLE, PROBING, DECODING, TRANSCRIBING, MERGING, EXTRACTING, DONE, ERROR, CANCELED }

/** Groq STT 모델 (Whisper 호환) */
enum class GroqModel(val id: String, val label: String) {
    TURBO("whisper-large-v3-turbo", "Turbo (가장 빠름·권장)"),
    LARGE("whisper-large-v3", "Large v3 (최고 정확도·느림)")
}

/** 한 청크의 상태 */
enum class ChunkState { PENDING, RUNNING, DONE, FAILED }

data class ChunkInfo(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    var state: ChunkState = ChunkState.PENDING,
    var text: String = "",
    var attempts: Int = 0,
    var error: String? = null
)

/** 화면에 노출되는 전체 진행 상태 스냅샷 */
data class JobProgress(
    val phase: Phase = Phase.IDLE,
    val message: String = "",
    val overall: Float = 0f,          // 0..1
    val totalChunks: Int = 0,
    val doneChunks: Int = 0,
    val partialText: String = "",
    val outputPath: String? = null,
    val durationMs: Long = 0L
)

/** 결과 요약 */
data class JobResult(
    val success: Boolean,
    val message: String,
    val text: String = "",
    val outputPath: String? = null
)
