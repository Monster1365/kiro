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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smishingguard.AppContainer
import com.smishingguard.appViewModel
import kotlinx.coroutines.launch

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    val autoAvailability = c.smsSource.availability

    fun saveWelcome(analysisConsent: Boolean, saveOriginal: Boolean, done: () -> Unit) {
        viewModelScope.launch {
            c.settings.setAnalysisConsent(analysisConsent)
            c.settings.setOriginalSaveConsent(saveOriginal)
            done()
        }
    }

    fun setNotificationEnabled(v: Boolean) {
        viewModelScope.launch { c.settings.setNotificationEnabled(v) }
    }

    fun finish(done: () -> Unit) {
        viewModelScope.launch {
            c.settings.setOnboarded(true)
            done()
        }
    }
}

@Composable
fun ConsentSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 시작·안내: 서비스 설명, 개인정보 처리 안내, 선택 동의 */
@Composable
fun WelcomeScreen(onNext: () -> Unit) {
    val vm = appViewModel { OnboardingViewModel(it) }
    var analysisConsent by rememberSaveable { mutableStateOf(false) }
    var saveOriginal by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("스미싱 예방", style = MaterialTheme.typography.headlineMedium)
        Text(
            "의심되는 문자를 붙여 넣으면, 스미싱일 가능성과 그 이유, 대처 방법을 쉬운 말로 알려드려요.",
            style = MaterialTheme.typography.bodyLarge,
        )
        SectionCard("개인정보는 이렇게 다뤄요") {
            BulletList(
                listOf(
                    "분석을 위해 붙여 넣은 문자 내용이 분석 서버로 전송돼요. 동의하지 않으면 전송하지 않아요.",
                    "문자 속 링크는 열지 않아요.",
                    "보호자를 등록하면, 위험한 문자가 확인될 때 보호자에게 알림 문자를 보낼 수 있어요. 기본으로는 '위험이 있었다는 사실, 시각, 대처 안내'만 보내고 문자 내용과 링크는 보내지 않아요.",
                    "보호자 알림은 보호자가 인증을 마치고, 직접 켠 경우에만 보내요.",
                    "분석 결과는 100% 정확하지 않아요. 최종 판단은 공식 기관·업체에 직접 확인해 주세요.",
                ),
            )
        }
        SectionCard("선택 항목 (나중에 설정에서 바꿀 수 있어요)") {
            ConsentSwitch(
                "문자 내용을 서버로 보내 분석하기",
                "꺼져 있으면 분석을 시작할 때 다시 물어봐요.",
                analysisConsent,
            ) { analysisConsent = it }
            ConsentSwitch(
                "분석한 문자 내용을 이 기기에 저장하기",
                "꺼져 있으면 위험 수준과 요약만 저장해요. 저장하면 암호화되어 기본 30일간 보관돼요.",
                saveOriginal,
            ) { saveOriginal = it }
        }
        BigButton("시작하기", onClick = { vm.saveWelcome(analysisConsent, saveOriginal, onNext) })
    }
}

/** 초기 설정: 알림 권한, 분석 방식 안내 */
@Composable
fun SetupScreen(onNext: () -> Unit) {
    val vm = appViewModel { OnboardingViewModel(it) }
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    var granted by rememberSaveable { mutableStateOf(Build.VERSION.SDK_INT < 33) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
        vm.setNotificationEnabled(ok)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("알림 설정", style = MaterialTheme.typography.headlineMedium)
        SectionCard("위험 알림") {
            Text("스미싱이 의심되면 알림으로 먼저 알려드려요. 알림에는 문자 내용이나 링크를 보여주지 않아요.", style = MaterialTheme.typography.bodyLarge)
            if (asked && !granted) {
                Text(
                    "알림을 허용하지 않아도 앱 안에서 분석 결과를 볼 수 있어요. 나중에 알림을 받고 싶으면 휴대전화 설정에서 이 앱의 알림을 켜 주세요.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                BigButton("휴대전화 알림 설정 열기", secondary = true, onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                })
            } else if (granted && Build.VERSION.SDK_INT >= 33) {
                Text("✔ 알림이 켜져 있어요.", style = MaterialTheme.typography.bodyLarge)
            } else {
                BigButton("알림 받기", onClick = {
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else { granted = true; vm.setNotificationEnabled(true) }
                })
            }
        }
        SectionCard("문자 확인 방법") {
            Text(vm.autoAvailability.reason, style = MaterialTheme.typography.bodyLarge)
        }
        BigButton("다음", onClick = onNext)
    }
}
