package com.mystt.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mystt.app.model.GroqModel
import com.mystt.app.ui.MainViewModel
import com.mystt.app.ui.components.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var sttKey by remember { mutableStateOf(vm.secure.getSttToken()) }
    var llmKey by remember { mutableStateOf(vm.secure.getLlmToken()) }

    val chunk by vm.settings.chunkSeconds.collectAsState(initial = 120)
    val conc by vm.settings.concurrency.collectAsState(initial = 1)
    val lang by vm.settings.language.collectAsState(initial = "ko")
    val model by vm.settings.sttModel.collectAsState(initial = GroqModel.TURBO.id)

    var saved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API 키
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Groq API 키", fontWeight = FontWeight.Bold)
                    Text(
                        "console.groq.com 에서 무료 키를 발급받아 입력하세요. 키는 기기에 암호화 저장됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    OutlinedTextField(
                        value = sttKey,
                        onValueChange = { sttKey = it; saved = false },
                        label = { Text("STT 키 (필수)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = llmKey,
                        onValueChange = { llmKey = it; saved = false },
                        label = { Text("요약용 LLM 키 (선택, 비우면 STT 키 사용)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            vm.secure.saveSttToken(sttKey)
                            vm.secure.saveLlmToken(llmKey)
                            saved = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (saved) "저장됨 ✓" else "키 저장") }
                }
            }

            // 청크 길이
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("조각 길이", fontWeight = FontWeight.Bold)
                    Text(
                        "긴 조각일수록 호출 횟수가 줄지만 1회 실패 시 손실이 큽니다. 권장 120초.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val opts = listOf(60, 120, 300)
                        opts.forEachIndexed { idx, sec ->
                            SegmentedButton(
                                selected = chunk == sec,
                                onClick = { scope.launch { vm.settings.setChunkSeconds(sec) } },
                                shape = SegmentedButtonDefaults.itemShape(idx, opts.size)
                            ) { Text("${sec}초") }
                        }
                    }
                }
            }

            // 동시 처리
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("동시 처리 수", fontWeight = FontWeight.Bold)
                    Text(
                        "높이면 빨라지지만 Groq 무료 한도(429)에 걸리기 쉽습니다. 권장 1.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val opts = listOf(1, 2, 3)
                        opts.forEachIndexed { idx, n ->
                            SegmentedButton(
                                selected = conc == n,
                                onClick = { scope.launch { vm.settings.setConcurrency(n) } },
                                shape = SegmentedButtonDefaults.itemShape(idx, opts.size)
                            ) { Text("$n") }
                        }
                    }
                }
            }

            // 모델
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("STT 모델", fontWeight = FontWeight.Bold)
                    GroqModel.entries.forEach { gm ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(
                                selected = model == gm.id,
                                onClick = { scope.launch { vm.settings.setSttModel(gm.id) } }
                            )
                            Text(gm.label)
                        }
                    }
                }
            }

            // 언어
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("인식 언어", fontWeight = FontWeight.Bold)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val opts = listOf("ko" to "한국어", "en" to "English", "ja" to "日本語")
                        opts.forEachIndexed { idx, (code, label) ->
                            SegmentedButton(
                                selected = lang == code,
                                onClick = { scope.launch { vm.settings.setLanguage(code) } },
                                shape = SegmentedButtonDefaults.itemShape(idx, opts.size)
                            ) { Text(label) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
