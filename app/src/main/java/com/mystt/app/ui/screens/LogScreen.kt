package com.mystt.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mystt.app.log.AppLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val lines by AppLogger.lines.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("로그") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(onClick = { AppLogger.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "지우기")
                    }
                }
            )
        }
    ) { pad ->
        if (lines.isEmpty()) {
            Box(
                Modifier
                    .padding(pad)
                    .fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    "로그가 아직 없습니다.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                reverseLayout = true
            ) {
                items(lines.reversed()) { l ->
                    val color = when (l.level) {
                        "ERROR" -> Color(0xFFD32F2F)
                        "WARN" -> Color(0xFFE08600)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        "${AppLogger.fmtTime(l.time)}  ${l.tag}: ${l.msg}",
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
