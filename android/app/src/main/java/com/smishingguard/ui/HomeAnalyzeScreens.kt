package com.smishingguard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smishingguard.AppContainer
import com.smishingguard.appViewModel
import com.smishingguard.data.AnalyzeOutcome
import com.smishingguard.domain.AppSettings
import com.smishingguard.domain.DetectionRecord
import com.smishingguard.domain.GuardianStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatTime(ms: Long): String = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(Date(ms))

// ---------------- 홈 ----------------

class HomeViewModel(c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    val online: StateFlow<Boolean> =
        c.connectivity.isOnline.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), c.connectivity.isOnlineNow())
    val recent: StateFlow<List<DetectionRecord>> =
        c.repository.records.map { it.take(3) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val autoAvailability = c.smsSource.availability
}

@Composable
fun HomeScreen(onAnalyze: () -> Unit, onOpenRecord: (String) -> Unit, onOpenHistory: () -> Unit) {
    val vm = appViewModel { HomeViewModel(it) }
    val s by vm.settings.collectAsState()
    val online by vm.online.collectAsState()
    val recent by vm.recent.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("스미싱 예방", style = MaterialTheme.typography.headlineMedium)

        SectionCard("지금 상태") {
            if (!online) {
                Text("⚠ 인터넷 연결을 확인해 주세요", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            }
            Text(
                if (vm.autoAvailability.available) "새 문자 자동 분석이 켜져 있어요" else "문자를 복사해서 직접 확인할 수 있어요",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                when (s.guardianStatus) {
                    GuardianStatus.NONE -> "보호자를 등록하면 위험 상황을 알려드릴 수 있어요"
                    GuardianStatus.VERIFIED ->
                        if (s.guardianAutoAlertEnabled) "보호자 ${s.guardianName}님께 위험을 알릴 수 있어요" else "보호자 자동 알림이 꺼져 있어요"
                    else -> "보호자 인증을 마치면 위험 상황을 알려드릴 수 있어요"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        BigButton("의심되는 문자 확인하기", onClick = onAnalyze)

        Text("최근 결과", style = MaterialTheme.typography.titleLarge)
        if (recent.isEmpty()) {
            Text("아직 확인한 문자가 없어요.", style = MaterialTheme.typography.bodyLarge)
        } else {
            recent.forEach { r ->
                Column(Modifier.fillMaxWidth().clickable { onOpenRecord(r.id) }) {
                    RiskCard(r.riskLevel, r.summary)
                    Text(formatTime(r.analyzedAt), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
            BigButton("전체 기록 보기", secondary = true, onClick = onOpenHistory)
        }
    }
}

// ---------------- 문자 분석 ----------------

sealed interface AnalyzeUi {
    data object Idle : AnalyzeUi
    data object Loading : AnalyzeUi
    data class Failed(val message: String) : AnalyzeUi
}

class AnalyzeViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow<AnalyzeUi>(AnalyzeUi.Idle)
    val ui: StateFlow<AnalyzeUi> = _ui
    private val _needConsent = MutableStateFlow(false)
    val needConsent: StateFlow<Boolean> = _needConsent
    private val _resultId = MutableStateFlow<String?>(null)
    val resultId: StateFlow<String?> = _resultId

    fun submit(body: String, sender: String) {
        if (_ui.value is AnalyzeUi.Loading) return
        if (body.isBlank()) { _ui.value = AnalyzeUi.Failed("분석할 문자 내용을 붙여 넣어 주세요."); return }
        if (!c.connectivity.isOnlineNow()) { _ui.value = AnalyzeUi.Failed("인터넷 연결을 확인해 주세요."); return }
        viewModelScope.launch {
            _ui.value = AnalyzeUi.Loading
            when (val o = c.repository.analyzeManual(body, sender)) {
                is AnalyzeOutcome.Success -> { _ui.value = AnalyzeUi.Idle; _resultId.value = o.recordId }
                AnalyzeOutcome.NeedConsent -> { _ui.value = AnalyzeUi.Idle; _needConsent.value = true }
                is AnalyzeOutcome.Failed -> _ui.value = AnalyzeUi.Failed(o.message)
                AnalyzeOutcome.Duplicate -> _ui.value = AnalyzeUi.Idle
            }
        }
    }

    fun consentAndSubmit(body: String, sender: String) {
        viewModelScope.launch {
            c.settings.setAnalysisConsent(true)
            _needConsent.value = false
            submit(body, sender)
        }
    }

    fun dismissConsent() { _needConsent.value = false }
    fun consumeResult() { _resultId.value = null }
}

@Composable
fun AnalyzeScreen(onResult: (String) -> Unit) {
    val vm = appViewModel { AnalyzeViewModel(it) }
    val ui by vm.ui.collectAsState()
    val needConsent by vm.needConsent.collectAsState()
    val resultId by vm.resultId.collectAsState()
    val clipboard = LocalClipboardManager.current

    var body by rememberSaveable { mutableStateOf("") }
    var sender by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(resultId) {
        resultId?.let {
            vm.consumeResult()
            body = "" // 화면에 원문을 남기지 않는다
            onResult(it)
        }
    }

    if (needConsent) {
        AlertDialog(
            onDismissRequest = vm::dismissConsent,
            title = { Text("문자 내용을 서버로 보내도 될까요?") },
            text = {
                Text("분석을 위해 붙여 넣은 문자 내용이 분석 서버로 전송돼요. 문자 속 링크는 열지 않아요. 동의하지 않으면 전송하지 않고, 분석도 할 수 없어요.")
            },
            confirmButton = { TextButton(onClick = { vm.consentAndSubmit(body, sender) }) { Text("동의하고 분석") } },
            dismissButton = { TextButton(onClick = vm::dismissConsent) { Text("취소") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("문자 확인하기", style = MaterialTheme.typography.headlineMedium)
        Text(
            "메시지 앱에서 의심되는 문자를 길게 눌러 복사한 뒤, 아래에 붙여 넣어 주세요. 링크는 누르지 마세요.",
            style = MaterialTheme.typography.bodyLarge,
        )
        BigButton("복사한 문자 붙여넣기", secondary = true, onClick = {
            clipboard.getText()?.text?.let { body = it.take(2000) }
        })
        OutlinedTextField(
            value = body, onValueChange = { body = it.take(2000) },
            label = { Text("문자 내용") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        )
        OutlinedTextField(
            value = sender, onValueChange = { sender = it },
            label = { Text("보낸 번호 (모르면 비워 두세요)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )

        when (val u = ui) {
            AnalyzeUi.Loading -> Text("분석하고 있어요. 잠시만 기다려 주세요…", style = MaterialTheme.typography.bodyLarge)
            is AnalyzeUi.Failed -> {
                ErrorText(u.message)
                BigButton("다시 시도", secondary = true, onClick = { vm.submit(body, sender) })
            }
            AnalyzeUi.Idle -> Unit
        }
        BigButton("분석하기", enabled = ui !is AnalyzeUi.Loading, onClick = { vm.submit(body, sender) })
    }
}
