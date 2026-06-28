package com.mystt.app.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "mystt_settings")

/**
 * 사용자 설정(평문 가능 값). 토큰은 SecureKeyStore 가 따로 암호화 보관.
 */
class AppSettings(private val context: Context) {
    private val KEY_CHUNK = intPreferencesKey("chunk_seconds")
    private val KEY_CONC = intPreferencesKey("concurrency")
    private val KEY_LANG = stringPreferencesKey("language")
    private val KEY_MODEL = stringPreferencesKey("stt_model")

    val chunkSeconds: Flow<Int> = context.dataStore.data.map { it[KEY_CHUNK] ?: 120 }
    val concurrency: Flow<Int> = context.dataStore.data.map { it[KEY_CONC] ?: 1 }
    val language: Flow<String> = context.dataStore.data.map { it[KEY_LANG] ?: "ko" }
    val sttModel: Flow<String> = context.dataStore.data.map { it[KEY_MODEL] ?: "whisper-large-v3-turbo" }

    suspend fun setChunkSeconds(v: Int) { context.dataStore.edit { it[KEY_CHUNK] = v } }
    suspend fun setConcurrency(v: Int) { context.dataStore.edit { it[KEY_CONC] = v } }
    suspend fun setLanguage(v: String) { context.dataStore.edit { it[KEY_LANG] = v } }
    suspend fun setSttModel(v: String) { context.dataStore.edit { it[KEY_MODEL] = v } }
}
