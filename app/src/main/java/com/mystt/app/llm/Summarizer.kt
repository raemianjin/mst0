package com.mystt.app.llm

import com.mystt.app.log.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * (선택) Groq chat 으로 STT 결과를 요약/회의록화. STT 토큰과 같은 Groq 키 재사용 가능.
 * 인터넷·토큰 없으면 비활성.
 */
class Summarizer(private val token: String) {
    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"
    private val model = "llama-3.3-70b-versatile"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun summarize(transcript: String, minutes: Boolean): String = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "요약용 토큰이 없습니다." }
        val sys = if (minutes)
            "너는 회의록 작성 비서다. 주어진 음성 인식 원문을 바탕으로 한국어 회의록을 작성하라. " +
                "형식: [회의 주제] / [핵심 논의] (불릿) / [결정 사항] / [후속 조치(담당/기한)]. 원문에 없는 내용은 지어내지 말 것."
        else
            "너는 요약 비서다. 주어진 음성 인식 원문을 한국어로 간결히 요약하라. 핵심만 불릿으로."
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sys))
                put(JSONObject().put("role", "user").put("content", transcript.take(24000)))
            })
        }
        val req = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                AppLogger.e("LLM", "요약 실패 ${resp.code}: ${raw.take(200)}")
                throw RuntimeException("요약 실패 ${resp.code}")
            }
            JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }
    }
}
