package com.mystt.app.stt

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class SttHttpException(val code: Int, val bodyText: String) :
    RuntimeException("STT HTTP $code: ${bodyText.take(300)}")

/**
 * Groq 의 Whisper 호환 transcriptions 엔드포인트로 단일 WAV 청크를 텍스트화.
 * 청크가 작으므로(기본 ~2분) 업로드/추론이 빠르고 용량 제한에 걸리지 않는다.
 */
class GroqSttClient(private val token: String) {

    private val endpoint = "https://api.groq.com/openai/v1/audio/transcriptions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 1회 호출. 실패 시 SttHttpException(재시도 판단용 code 포함) throw. */
    fun transcribeOnce(wav: File, model: String, language: String): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("language", language)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw SttHttpException(resp.code, raw)
            return JSONObject(raw).optString("text").trim()
        }
    }
}
