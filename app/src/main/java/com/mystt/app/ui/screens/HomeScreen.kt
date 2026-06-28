package com.mystt.app.ui.screens

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mystt.app.model.JobMode
import com.mystt.app.model.Phase
import com.mystt.app.ui.MainViewModel
import com.mystt.app.ui.components.InfoRow
import com.mystt.app.ui.components.SectionCard
import com.mystt.app.util.Sharing
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit
) {
    val ctx = LocalContext.current
    val info by vm.info.collectAsState()
    val mode by vm.mode.collectAsState()
    val progress by vm.progress.collectAsState()
    val busy by vm.busy.collectAsState()
    val result by vm.result.collectAsState()
    val resultPath by vm.resultPath.collectAsState()
    val summary by vm.summary.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            vm.pick(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("myStt", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenLog) {
                        Icon(Icons.Default.List, contentDescription = "로그")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 모드 선택
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("작업 모드", fontWeight = FontWeight.Bold)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = mode == JobMode.STT,
                            onClick = { if (!busy) vm.setMode(JobMode.STT) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text("음성 → 텍스트") }
                        SegmentedButton(
                            selected = mode == JobMode.EXTRACT_AUDIO,
                            onClick = { if (!busy) vm.setMode(JobMode.EXTRACT_AUDIO) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text("동영상 → 음원") }
                    }
                    Text(
                        if (mode == JobMode.STT)
                            "대용량 오디오/동영상을 조각으로 나눠 STT 후 합칩니다."
                        else
                            "동영상에서 음원만 추출해 .m4a 파일로 저장합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // 파일 선택
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { picker.launch(arrayOf("audio/*", "video/*")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("오디오/동영상 파일 선택") }

                    info?.let { i ->
                        Spacer(Modifier.height(4.dp))
                        InfoRow("파일", i.displayName)
                        InfoRow("크기", Formatter.formatShortFileSize(ctx, i.sizeBytes))
                        if (i.durationMs > 0) InfoRow("길이", formatDuration(i.durationMs))
                    }
                }
            }

            // 실행 / 취소
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { vm.start() },
                    enabled = info != null && !busy,
                    modifier = Modifier.weight(1f)
                ) { Text(if (mode == JobMode.STT) "STT 시작" else "음원 추출") }
                if (busy) {
                    OutlinedButton(
                        onClick = { vm.cancel() },
                        modifier = Modifier.weight(1f)
                    ) { Text("취소") }
                }
            }

            // 진행 상태
            if (progress.phase != Phase.IDLE) {
                SectionCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(progress.message.ifBlank { progress.phase.name }, fontWeight = FontWeight.Medium)
                        if (busy || progress.overall > 0f) {
                            LinearProgressIndicator(
                                progress = { progress.overall.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (progress.totalChunks > 0) {
                            Text(
                                "조각 ${progress.doneChunks}/${progress.totalChunks}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (progress.partialText.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "실시간 미리보기",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.background,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    progress.partialText.takeLast(600),
                                    modifier = Modifier.padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // 결과 (STT)
            if (result.isNotBlank()) {
                SectionCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("인식 결과", fontWeight = FontWeight.Bold)
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(result, modifier = Modifier.padding(12.dp))
                        }
                        FlowButtons(
                            onCopy = { Sharing.copyText(ctx, result) },
                            onShare = { Sharing.shareText(ctx, result) },
                            onOpenTxt = {
                                resultPath?.let { Sharing.openFile(ctx, File(it), "text/plain") }
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.summarize(true) }) { Text("회의록") }
                            OutlinedButton(onClick = { vm.summarize(false) }) { Text("요약") }
                        }
                    }
                }
            }

            // 요약
            if (summary.isNotBlank()) {
                SectionCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Description, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text("AI 정리", fontWeight = FontWeight.Bold)
                        }
                        Text(summary)
                        OutlinedButton(onClick = { Sharing.copyText(ctx, summary) }) { Text("복사") }
                    }
                }
            }

            // 음원 추출 결과
            if (mode == JobMode.EXTRACT_AUDIO && progress.phase == Phase.DONE && resultPath != null) {
                SectionCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("추출된 음원", fontWeight = FontWeight.Bold)
                        Text(File(resultPath!!).name, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                Sharing.shareFile(ctx, File(resultPath!!), "audio/mp4")
                            }) { Text("공유") }
                            OutlinedButton(onClick = {
                                Sharing.openFile(ctx, File(resultPath!!), "audio/mp4")
                            }) { Text("열기") }
                        }
                    }
                }
            }

            // 저장된 결과 목록
            val saved = remember(result, progress.phase) { vm.savedTranscripts() }
            if (saved.isNotEmpty()) {
                SectionCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("저장된 텍스트", fontWeight = FontWeight.Bold)
                        saved.take(8).forEach { f ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    f.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    Sharing.openFile(ctx, f, "text/plain")
                                }) { Text("열기") }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FlowButtons(
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onOpenTxt: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCopy) { Text("복사") }
        OutlinedButton(onClick = onShare) { Text("공유") }
        OutlinedButton(onClick = onOpenTxt) { Text(".txt 열기") }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
