package com.mystt.app.media

import java.io.File
import java.io.RandomAccessFile

/** PCM16 데이터를 표준 RIFF/WAVE(44바이트 헤더) 파일로 기록. */
object WavWriter {

    /** 메모리에 있는 PCM16(mono) 바이트 배열을 통째로 WAV 파일로 저장. */
    fun writeWav(file: File, pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1) {
        file.outputStream().use { os ->
            os.write(header(pcm.size, sampleRate, channels))
            os.write(pcm)
        }
    }

    private fun header(dataLen: Int, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalLen = dataLen + 36
        val h = ByteArray(44)
        // RIFF
        h[0]='R'.code.toByte(); h[1]='I'.code.toByte(); h[2]='F'.code.toByte(); h[3]='F'.code.toByte()
        writeIntLE(h, 4, totalLen)
        h[8]='W'.code.toByte(); h[9]='A'.code.toByte(); h[10]='V'.code.toByte(); h[11]='E'.code.toByte()
        // fmt
        h[12]='f'.code.toByte(); h[13]='m'.code.toByte(); h[14]='t'.code.toByte(); h[15]=' '.code.toByte()
        writeIntLE(h, 16, 16)                 // PCM fmt chunk size
        writeShortLE(h, 20, 1)                // audioFormat = PCM
        writeShortLE(h, 22, channels)
        writeIntLE(h, 24, sampleRate)
        writeIntLE(h, 28, byteRate)
        writeShortLE(h, 32, blockAlign)
        writeShortLE(h, 34, bitsPerSample)
        // data
        h[36]='d'.code.toByte(); h[37]='a'.code.toByte(); h[38]='t'.code.toByte(); h[39]='a'.code.toByte()
        writeIntLE(h, 40, dataLen)
        return h
    }

    private fun writeIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off+1] = ((v shr 8) and 0xff).toByte()
        b[off+2] = ((v shr 16) and 0xff).toByte()
        b[off+3] = ((v shr 24) and 0xff).toByte()
    }
    private fun writeShortLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off+1] = ((v shr 8) and 0xff).toByte()
    }
}
