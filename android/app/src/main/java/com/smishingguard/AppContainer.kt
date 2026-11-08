package com.smishingguard

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smishingguard.data.AnalysisRepository
import com.smishingguard.data.ConnectivityObserver
import com.smishingguard.data.SettingsStore
import com.smishingguard.data.local.AppDatabase
import com.smishingguard.data.remote.Backend
import com.smishingguard.data.remote.HttpBackend
import com.smishingguard.data.remote.MockBackend
import com.smishingguard.notify.Notifier
import com.smishingguard.sms.IncomingSmsSource
import com.smishingguard.sms.UnavailableIncomingSmsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 수동 DI 컨테이너 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    private val db = AppDatabase.create(context)
    val notifier = Notifier(context)
    val connectivity = ConnectivityObserver(context)

    /** debug 기본값은 모의 백엔드. release 는 HttpBackend. (-PuseMock 으로 변경 가능) */
    val backend: Backend =
        if (BuildConfig.USE_MOCK_BACKEND) MockBackend() else HttpBackend(BuildConfig.API_BASE_URL, settings)

    val repository = AnalysisRepository(db.detectionDao(), settings, backend, notifier, appScope)

    /** 자동 수신 분석은 정책/기술 검증 전까지 사용 불가 구현을 연결한다. */
    val smsSource: IncomingSmsSource = UnavailableIncomingSmsSource()

    init {
        appScope.launch { repository.purgeExpired() }
    }
}

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as App).container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
