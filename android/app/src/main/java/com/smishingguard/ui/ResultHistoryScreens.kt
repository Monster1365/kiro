package com.smishingguard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smishingguard.AppContainer
import com.smishingguard.appViewModel
import com.smishingguard.domain.AlertStatus
import com.smishingguard.domain.DetectionRecord
import com.smishingguard.domain.RiskLevel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------------- 결과 / 이력 상세 ----------------

class ResultViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    /** null: 아직 로딩 중이거나 삭제됨 */
    val record: StateFlow<DetectionRecord?> =
        c.repository.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun delete(done: () -> Unit) {
        viewModelScope.launch { c.repository.delete(id); done() }
    }

    fun feedback(helpful: Boolean) {
        viewModelScope.launch { c.repository.setFeedback(id, helpful) }
    }
}

@Composable
fun ResultScreen(recordId: String, onClose: () -> Unit) {
    val vm = appViewModel(key = "result_$recordId") { ResultViewModel(it, recordId) }
    val r by vm.record.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    val rec = r
    if (rec == null) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("기록을 찾을 수 없어요. 삭제되었거나 보관 기간이 지났을 수 있어요.", style = MaterialTheme.typography.bodyLarge)
            BigButton("돌아가기", secondary = true, onClick = onClose)
        }
        return
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("이 기록을 지울까요?") },
            text = { Text("이 기기에 저장된 이 기록이 삭제돼요.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onClose) }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RiskCard(rec.riskLevel, rec.summary)
        Text(formatTime(rec.analyzedAt) + " 분석", style = MaterialTheme.typography.bodyMedium)

        if (rec.riskLevel == RiskLevel.HIGH || rec.riskLevel == RiskLevel.CAUTION) {
            SectionCard(null) {
                Text("문자 속 링크를 누르지 마세요.", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (rec.riskLevel == RiskLevel.LOW) {
            SectionCard(null) {
                Text("위험이 낮게 나왔어도 안전을 보장하지는 않아요. 모르는 링크는 누르지 마세요.", style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (rec.reasons.isNotEmpty()) {
            SectionCard("이렇게 판단했어요") { BulletList(rec.reasons) }
        }
        // 경고 화면에는 행동을 2~3개로 간결하게
        SectionCard("이렇게 하세요") { BulletList(rec.recommendedActions.take(3)) }

        SectionCard("보호자 알림") {
            Text(rec.guardianAlertStatus.label, style = MaterialTheme.typography.bodyLarge)
        }

        if (rec.messageBody != null || rec.sender != null) {
            SectionCard("분석한 문자 (이 기기에만 저장돼요)") {
                rec.sender?.let { Text("보낸 번호: $it", style = MaterialTheme.typography.bodyMedium) }
                // Text 는 링크를 자동으로 열지 않는다.
                rec.messageBody?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
        }

        Text(
            "이 결과는 분석 결과이며 100% 정확하지 않아요. 확실하지 않으면 공식 앱이나 공식 전화번호로 직접 확인해 주세요.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionCard("이 결과가 어땠나요? (선택)") {
            Text("의견은 이 기기에만 기록되고 서버로 보내지 않아요.", style = MaterialTheme.typography.bodyMedium)
            when (rec.feedback) {
                "HELPFUL" -> Text("✔ '도움이 됐어요'로 기록했어요.", style = MaterialTheme.typography.bodyLarge)
                "WRONG" -> Text("✔ '판단이 잘못된 것 같아요'로 기록했어요.", style = MaterialTheme.typography.bodyLarge)
                else -> {
                    BigButton("이 결과가 도움이 됐어요", secondary = true, onClick = { vm.feedback(true) })
                    BigButton("판단이 잘못된 것 같아요", secondary = true, onClick = { vm.feedback(false) })
                }
            }
        }

        BigButton("이 기록 삭제", secondary = true, onClick = { confirmDelete = true })
        BigButton("닫기", onClick = onClose)
    }
}

// ---------------- 이력 목록 ----------------

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val records: StateFlow<List<DetectionRecord>> =
        c.repository.records.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteAll() {
        viewModelScope.launch { c.repository.deleteAll() }
    }
}

@Composable
fun HistoryScreen(onOpen: (String) -> Unit) {
    val vm = appViewModel { HistoryViewModel(it) }
    val list by vm.records.collectAsState()
    var confirmAll by remember { mutableStateOf(false) }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("모든 기록을 지울까요?") },
            text = { Text("이 기기에 저장된 모든 분석 기록이 삭제돼요. 이미 보호자에게 보낸 문자는 되돌릴 수 없어요.") },
            confirmButton = { TextButton(onClick = { confirmAll = false; vm.deleteAll() }) { Text("모두 삭제") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("취소") } },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("확인한 기록", style = MaterialTheme.typography.headlineMedium)
        if (list.isEmpty()) {
            Text("저장된 기록이 없어요.", style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.id }) { r ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpen(r.id) }.semantics(mergeDescendants = true) {
                            contentDescription = "${formatTime(r.analyzedAt)}, 위험 수준 ${r.riskLevel.label}, ${r.summary}. 눌러서 자세히 보기"
                        },
                    ) {
                        RiskCard(r.riskLevel, r.summary)
                        Text(
                            formatTime(r.analyzedAt) + if (r.guardianAlertStatus != AlertStatus.NONE) " · ${r.guardianAlertStatus.label}" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            BigButton("모든 기록 삭제", secondary = true, onClick = { confirmAll = true })
        }
    }
}
