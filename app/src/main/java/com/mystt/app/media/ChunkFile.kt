package com.mystt.app.media

import java.io.File

/** 디코더가 잘라낸 한 청크(16kHz mono WAV). */
data class ChunkFile(
    val index: Int,
    val file: File,
    val startMs: Long,
    val endMs: Long
)
