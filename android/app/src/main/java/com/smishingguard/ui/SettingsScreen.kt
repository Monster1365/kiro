package com.smishingguard.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smishingguard.AppContainer
import com.smishingguard.appViewModel
import com.smishingguard.data.remote.BackendException
import com.smishingguard.domain.AppSettings
import com.smishingguard.domain.GuardianStatus
import com.smishingguard.domain.ShareLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    val online: StateFlow<Boolean> =
        c.connectivity.isOnline.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), c.connectivity.isOnlineNow())
    val autoAvailability = c.smsSource.availability
    val notifier = c.notifier

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun setAnalysisConsent(v: Boolean) { viewModelScope.launch { c.settings.setAnalysisConsent(v) } }
    fun setOriginalSave(v: Boolean) { viewModelScope.launch { c.settings.setOriginalSaveConsent(v) } }
    fun setNotificationEnabled(v: Boolean) { viewModelScope.launch { c.settings.setNotificationEnabled(v) } }
    fun setShareLevel(v: ShareLevel) { viewModelScope.launch { c.settings.setShareLevel(v) } }

    fun setRetention(days: Int) {
        viewModelScope.launch {
            c.settings.setRetentionDays(days)
            c.repository.purgeExpired()
        }
    }

    /** 서버(보호자 수신 동의)와 앱 설정을 함께 바꾼다. 인증된 보호자가 있어야 켤 수 있다. */
    fun setAutoAlert(enabled: Boolean) {
        val s = settings.value
        val id = s.guardianId
        if (enabled && (id == null || s.guardianStatus != GuardianStatus.VERIFIED)) {
            _error.value = "먼저 보호자를 등록하고 인증해 주세요."
            return
        }
        viewModelScope.launch {
            try {
                if (id != null) c.backend.setGuardianAlertConsent(id, enabled)
                c.settings.setGuardianAutoAlert(enabled)
                _error.value = null
            } catch (e: BackendException) {
                // 끄는 요청이 실패해도 앱에서는 먼저 끈다. (앱이 보내지 않으면 서버도 보내지 않는다)
                if (!enabled) c.settings.setGuardianAutoAlert(false)
                _error.value = "서버에 반영하지 못했어요. 인터넷 연결을 확인해 주세요."
            }
        }
    }

    fun deleteAllHistory() { viewModelScope.launch { c.repository.deleteAll() } }
    fun clearError() { _error.value = null }
}

@Composable
fun SettingsScreen(onOpenGuardian: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsState()
    val online by vm.online.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current

    var confirmExcerpt by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val notificationsAllowed = vm.notifier.canNotify()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        vm.setNotificationEnabled(ok)
    }

    if (confirmExcerpt) {
        AlertDialog(
            onDismissRequest = { confirmExcerpt = false },
            title = { Text("문자 내용 일부를 보호자에게 보낼까요?") },
            text = {
                Text("문자 내용 일부(링크 제외)와 발신 번호가 보호자에게 전달돼요. 받는 분이 문자를 직접 받은 것으로 오해하지 않도록, 보호자에게 미리 알려 주세요.")
            },
            confirmButton = { TextButton(onClick = { confirmExcerpt = false; vm.setShareLevel(ShareLevel.WITH_EXCERPT) }) { Text("이해했어요") } },
            dismissButton = { TextButton(onClick = { confirmExcerpt = false }) { Text("취소") } },
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("모든 기록을 지울까요?") },
            text = { Text("이 기기에 저장된 모든 분석 기록이 삭제돼요.") },
            confirmButton = { TextButton(onClick = { confirmDeleteAll = false; vm.deleteAllHistory() }) { Text("모두 삭제") } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("취소") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineMedium)

        SectionCard("서비스 상태") {
            StatusRow("위험 알림", if (s.notificationEnabled && notificationsAllowed) "켜짐" else "꺼짐", s.notificationEnabled && notificationsAllowed)
            StatusRow("자동 수신 분석", if (vm.autoAvailability.available) "사용 가능" else "사용 불가", vm.autoAvailability.available)
            if (!vm.autoAvailability.available) Text(vm.autoAvailability.reason, style = MaterialTheme.typography.bodyMedium)
            StatusRow(
                "보호자 등록·인증",
                when (s.guardianStatus) {
                    GuardianStatus.VERIFIED -> "인증 완료"
                    GuardianStatus.PENDING -> "인증 대기"
                    GuardianStatus.FAILED -> "인증 실패"
                    GuardianStatus.NONE -> "등록 안 함"
                },
                s.guardianStatus == GuardianStatus.VERIFIED,
            )
            StatusRow("보호자 자동 알림", if (s.guardianAutoAlertEnabled) "켜짐" else "꺼짐", s.guardianAutoAlertEnabled)
            StatusRow("인터넷 연결", if (online) "연결됨" else "연결 안 됨", online)
        }

        SectionCard("알림") {
            ConsentSwitch("위험 알림 받기", "스미싱이 의심될 때 알림으로 알려드려요. 알림에는 문자 내용을 보여주지 않아요.", s.notificationEnabled && notificationsAllowed) { on ->
                if (!on) vm.setNotificationEnabled(false)
                else if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.setNotificationEnabled(true)
            }
            if (!notificationsAllowed) {
                Text("알림 권한이 없어도 앱 안에서 결과를 볼 수 있어요. 알림을 받으려면 휴대전화 설정에서 이 앱의 알림을 켜 주세요.", style = MaterialTheme.typography.bodyMedium)
                BigButton("휴대전화 알림 설정 열기", secondary = true, onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                })
            }
        }

        SectionCard("보호자") {
            Text(
                if (s.guardianId != null) "${s.guardianName} (${s.guardianPhoneMasked})" else "등록된 보호자가 없어요.",
                style = MaterialTheme.typography.bodyLarge,
            )
            BigButton(if (s.guardianId != null) "보호자 관리" else "보호자 등록", secondary = true, onClick = onOpenGuardian)
            ConsentSwitch(
                "위험할 때 보호자에게 자동으로 알리기",
                "위험 수준이 '높음'이고 보호자 인증이 끝났을 때만 보내요.",
                s.guardianAutoAlertEnabled,
            ) { vm.setAutoAlert(it) }
            Text("보호자에게 알릴 내용", style = MaterialTheme.typography.titleMedium)
            ShareLevel.entries.forEach { level ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
                        selected = s.guardianShareLevel == level, role = Role.RadioButton,
                        onClick = { if (level == ShareLevel.WITH_EXCERPT) confirmExcerpt = true else vm.setShareLevel(level) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.guardianShareLevel == level, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(level.label, style = MaterialTheme.typography.bodyLarge)
                        Text(level.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text("문자 원문과 링크는 기본으로 보내지 않아요.", style = MaterialTheme.typography.bodyMedium)
            ErrorText(error)
        }

        SectionCard("개인정보와 동의") {
            ConsentSwitch("문자 내용을 서버로 보내 분석하기", "끄면 분석을 할 수 없어요. 문자는 전송되지 않아요.", s.analysisConsent) { vm.setAnalysisConsent(it) }
            ConsentSwitch("분석한 문자 내용을 이 기기에 저장하기", "끄면 위험 수준과 요약만 저장해요.", s.localOriginalSaveConsent) { vm.setOriginalSave(it) }
            Text("동의를 끄면 이후부터 적용돼요. 이미 저장된 기록은 아래 '모든 기록 삭제'로 지울 수 있어요.", style = MaterialTheme.typography.bodyMedium)
            Text("삭제 범위", style = MaterialTheme.typography.titleMedium)
            BulletList(
                listOf(
                    "이 기기: 기록을 지우면 바로 사라져요.",
                    "서버: 문자 원문은 분석이 끝나면 저장하지 않아요. 분석 결과 요약 정보는 잠시 후 자동으로 지워져요.",
                    "보호자 정보: 보호자를 삭제하면 서버에서도 지워요.",
                ),
            )
        }

        SectionCard("기록 보관 기간") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90).forEach { d ->
                    FilterChip(
                        selected = s.retentionDays == d,
                        onClick = { vm.setRetention(d) },
                        label = { Text("${d}일", style = MaterialTheme.typography.bodyLarge) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            BigButton("모든 기록 삭제", secondary = true, onClick = { confirmDeleteAll = true })
        }
    }
}
