package com.mystt.app.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Groq API 토큰을 기기 내에서 암호화 저장. 외부로 노출되지 않으며 호출 시에만 메모리로 로드.
 * STT 토큰과 LLM 토큰을 분리 저장(같은 Groq 키를 양쪽에 쓸 수도 있음).
 */
class SecureKeyStore(context: Context) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "mystt_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    fun saveSttToken(token: String) { prefs.edit().putString(KEY_STT, token.trim()).apply() }
    fun getSttToken(): String = prefs.getString(KEY_STT, null)?.takeIf { it.isNotBlank() } ?: ""
    fun hasSttToken(): Boolean = getSttToken().isNotBlank()

    fun saveLlmToken(token: String) { prefs.edit().putString(KEY_LLM, token.trim()).apply() }
    fun getLlmToken(): String = prefs.getString(KEY_LLM, null)?.takeIf { it.isNotBlank() } ?: ""
    fun hasLlmToken(): Boolean = getLlmToken().isNotBlank()

    /** LLM 토큰이 비어있으면 STT 토큰을 재사용(같은 Groq 키일 때 편의) */
    fun effectiveLlmToken(): String = getLlmToken().ifBlank { getSttToken() }

    companion object {
        private const val KEY_STT = "stt_token"
        private const val KEY_LLM = "llm_token"
    }
}
