package com.mystt.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mystt.app.llm.Summarizer
import com.mystt.app.media.AudioExtractor
import com.mystt.app.media.MediaInfo
import com.mystt.app.media.MediaProbe
import com.mystt.app.model.JobMode
import com.mystt.app.model.JobProgress
import com.mystt.app.model.Phase
import com.mystt.app.settings.AppSettings
import com.mystt.app.settings.SecureKeyStore
import com.mystt.app.stt.TranscriptionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val secure = SecureKeyStore(app)
    val settings = AppSettings(app)

    private val _selected = MutableStateFlow<Uri?>(null)
    val selected: StateFlow<Uri?> = _selected
    private val _info = MutableStateFlow<MediaInfo?>(null)
    val info: StateFlow<MediaInfo?> = _info

    private val _mode = MutableStateFlow(JobMode.STT)
    val mode: StateFlow<JobMode> = _mode

    private val _progress = MutableStateFlow(JobProgress())
    val progress: StateFlow<JobProgress> = _progress

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _result = MutableStateFlow("")
    val result: StateFlow<String> = _result
    private val _resultPath = MutableStateFlow<String?>(null)
    val resultPath: StateFlow<String?> = _resultPath

    private val _summary = MutableStateFlow("")
    val summary: StateFlow<String> = _summary

    private var pipeline: TranscriptionPipeline? = null
    private var extractCancel: AtomicBoolean? = null
    private var job: Job? = null

    fun setMode(m: JobMode) { _mode.value = m }

    fun pick(uri: Uri) {
        _selected.value = uri
        _result.value = ""; _summary.value = ""; _resultPath.value = null
        _progress.value = JobProgress()
        viewModelScope.launch {
            val i = withContext(Dispatchers.IO) { MediaProbe.probe(getApplication(), uri) }
            _info.value = i
        }
    }

    fun start() {
        val uri = _selected.value ?: return
        if (_busy.value) return
        if (_mode.value == JobMode.STT) startStt(uri) else startExtract(uri)
    }

    private fun startStt(uri: Uri) {
        val token = secure.getSttToken()
        if (token.isBlank()) {
            _progress.value = JobProgress(Phase.ERROR, "설정에서 Groq STT API 키를 먼저 등록하세요.")
            return
        }
        _busy.value = true
        _result.value = ""; _summary.value = ""
        job = viewModelScope.launch {
            val chunk = settings.chunkSeconds.first()
            val conc = settings.concurrency.first()
            val lang = settings.language.first()
            val model = settings.sttModel.first()
            val base = MediaProbe.baseName(_info.value?.displayName ?: "input")
            val p = TranscriptionPipeline(getApplication(), token, model, lang, chunk, conc)
            pipeline = p
            val res = p.run(uri, base) { pr -> _progress.value = pr }
            _result.value = res.text
            _resultPath.value = res.outputPath
            _busy.value = false
        }
    }

    private fun startExtract(uri: Uri) {
        _busy.value = true
        val cancel = AtomicBoolean(false); extractCancel = cancel
        job = viewModelScope.launch {
            try {
                _progress.value = JobProgress(Phase.EXTRACTING, "음원 추출 중...", 0f)
                val base = MediaProbe.baseName(_info.value?.displayName ?: "audio")
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val dir = File(getApplication<Application>().getExternalFilesDir(null), "audio").apply { mkdirs() }
                val out = File(dir, "${base}_${stamp}.m4a")
                val f = withContext(Dispatchers.IO) {
                    AudioExtractor.extractToM4a(getApplication(), uri, out, cancel) { fr ->
                        _progress.value = _progress.value.copy(
                            overall = fr, message = "음원 추출 중... ${(fr * 100).toInt()}%")
                    }
                }
                _resultPath.value = f.absolutePath
                _progress.value = JobProgress(Phase.DONE, "음원 추출 완료 → ${f.name}", 1f, outputPath = f.absolutePath)
            } catch (e: Exception) {
                _progress.value = JobProgress(Phase.ERROR, "추출 실패: ${e.message}")
            } finally { _busy.value = false }
        }
    }

    fun cancel() {
        pipeline?.requestCancel()
        extractCancel?.set(true)
    }

    fun summarize(minutes: Boolean) {
        val text = _result.value
        if (text.isBlank()) return
        val token = secure.effectiveLlmToken()
        if (token.isBlank()) { _summary.value = "요약하려면 설정에서 Groq 키가 필요합니다."; return }
        viewModelScope.launch {
            _summary.value = "요약 생성 중..."
            _summary.value = try {
                Summarizer(token).summarize(text, minutes)
            } catch (e: Exception) { "요약 실패: ${e.message}" }
        }
    }

    fun savedTranscripts(): List<File> {
        val dir = File(getApplication<Application>().getExternalFilesDir(null), "transcripts")
        return dir.listFiles()?.filter { it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
