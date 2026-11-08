package com.smishingguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smishingguard.AppContainer
import com.smishingguard.appViewModel
import com.smishingguard.data.remote.BackendException
import com.smishingguard.domain.AppSettings
import com.smishingguard.domain.GuardianStatus
import com.smishingguard.domain.PhoneNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GuardianUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val devCode: String? = null,
    val message: String? = null,
)

class GuardianViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    private val _ui = MutableStateFlow(GuardianUiState())
    val ui: StateFlow<GuardianUiState> = _ui

    /** 인증번호 요청. 번호를 바꾸면 새 번호로 인증을 다시 진행한다. */
    fun requestCode(name: String, phone: String, relationship: String, autoAlert: Boolean) {
        if (name.isBlank()) { _ui.value = GuardianUiState(error = "보호자 이름이나 별칭을 입력해 주세요."); return }
        if (!PhoneNumber.isValidMobile(phone)) { _ui.value = GuardianUiState(error = "휴대전화 번호를 다시 확인해 주세요. 예: 010-1234-5678"); return }
        if (!c.connectivity.isOnlineNow()) { _ui.value = GuardianUiState(error = "인터넷 연결을 확인해 주세요."); return }
        viewModelScope.launch {
            _ui.value = GuardianUiState(loading = true)
            try {
                val g = c.backend.requestGuardianVerification(name.trim(), PhoneNumber.normalize(phone), relationship.trim().ifBlank { null }, autoAlert)
                c.settings.saveGuardian(g.guardianId, g.name, g.phoneMasked, GuardianStatus.PENDING)
                // 자동 알림 선택은 저장하되, 인증 전에는 발송되지 않는다. (저장소·서버 모두 확인)
                c.settings.setGuardianAutoAlert(autoAlert)
                _ui.value = GuardianUiState(devCode = g.devCode, message = "보호자에게 인증번호 문자를 보냈어요.")
            } catch (e: BackendException) {
                _ui.value = GuardianUiState(error = e.message)
            }
        }
    }

    fun verify(code: String) {
        val id = settings.value.guardianId ?: return
        if (code.isBlank()) { _ui.value = _ui.value.copy(error = "인증번호를 입력해 주세요."); return }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val g = c.backend.verifyGuardian(id, code.trim())
                c.settings.saveGuardian(g.guardianId, g.name, g.phoneMasked, g.status)
                _ui.value = GuardianUiState(message = "보호자 인증이 끝났어요.")
            } catch (e: BackendException) {
                _ui.value = _ui.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun delete() {
        val id = settings.value.guardianId ?: return
        viewModelScope.launch {
            _ui.value = GuardianUiState(loading = true)
            try {
                c.backend.deleteGuardian(id) // 서버에 남은 번호도 함께 삭제
                c.settings.clearGuardian()
                _ui.value = GuardianUiState(message = "보호자 정보를 삭제했어요.")
            } catch (e: BackendException) {
                _ui.value = GuardianUiState(error = "서버의 보호자 정보를 지우지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.")
            }
        }
    }

    fun clearMessage() { _ui.value = _ui.value.copy(message = null, error = null) }
}

/**
 * 보호자 등록·인증·삭제. 온보딩과 설정에서 같이 쓴다.
 * 보호자 등록은 건너뛸 수 있고, 건너뛰어도 본인용 분석은 그대로 쓸 수 있다.
 */
@Composable
fun GuardianScreen(onboarding: Boolean, onDone: () -> Unit) {
    val vm = appViewModel { GuardianViewModel(it) }
    val s by vm.settings.collectAsState()
    val ui by vm.ui.collectAsState()

    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var relationship by rememberSaveable { mutableStateOf("") }
    var autoAlert by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("보호자 등록", style = MaterialTheme.typography.headlineMedium)
        Text(
            "믿을 수 있는 가족이나 지인 1명을 등록하면, 스미싱 위험이 높은 문자가 확인될 때 그분께 알림 문자를 보낼 수 있어요. 건너뛰어도 괜찮아요.",
            style = MaterialTheme.typography.bodyLarge,
        )

        val registered = s.guardianId != null && s.guardianStatus != GuardianStatus.NONE && !editing
        if (registered) {
            SectionCard("등록된 보호자") {
                Text("${s.guardianName} (${s.guardianPhoneMasked})", style = MaterialTheme.typography.titleMedium)
                val statusText = when (s.guardianStatus) {
                    GuardianStatus.VERIFIED -> "✔ 인증 완료"
                    GuardianStatus.PENDING -> "인증 대기 중 - 인증 전에는 알림이 가지 않아요"
                    GuardianStatus.FAILED -> "인증 실패 - 다시 인증해 주세요"
                    GuardianStatus.NONE -> ""
                }
                Text(statusText, style = MaterialTheme.typography.bodyLarge)
            }
            if (s.guardianStatus != GuardianStatus.VERIFIED) {
                SectionCard("인증번호 입력") {
                    Text("보호자가 받은 문자의 인증번호 6자리를 입력해 주세요.", style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text("인증번호") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ui.devCode?.let { Text("개발용 인증번호: $it", style = MaterialTheme.typography.bodyMedium) }
                    BigButton("인증하기", enabled = !ui.loading, onClick = { vm.verify(code) })
                }
            }
            BigButton("번호 바꾸기 (다시 인증해요)", secondary = true, onClick = { editing = true; vm.clearMessage() })
            BigButton("보호자 삭제", secondary = true, enabled = !ui.loading, onClick = { vm.delete() })
        } else {
            SectionCard("보호자 정보") {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("이름 또는 별칭") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it }, label = { Text("휴대전화 번호") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = relationship, onValueChange = { relationship = it }, label = { Text("관계 (선택)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "보호자 번호는 인증 문자를 보내기 위해 서버로 전송돼요. 인증을 마치기 전에는 알림이 가지 않아요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ConsentSwitch(
                    "위험할 때 보호자에게 자동으로 알리기",
                    "위험 수준이 '높음'일 때만 보내요. 언제든 끌 수 있어요.",
                    autoAlert,
                ) { autoAlert = it }
            }
            BigButton("인증번호 보내기", enabled = !ui.loading, onClick = {
                vm.requestCode(name, phone, relationship, autoAlert)
                editing = false
            })
        }

        if (ui.loading) Text("처리 중이에요…", style = MaterialTheme.typography.bodyLarge)
        ErrorText(ui.error)
        ui.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }

        if (onboarding) {
            BigButton(
                if (s.guardianStatus == GuardianStatus.VERIFIED) "완료" else "건너뛰기",
                secondary = s.guardianStatus != GuardianStatus.VERIFIED,
                onClick = onDone,
            )
        } else {
            BigButton("돌아가기", secondary = true, onClick = onDone)
        }
    }
}
