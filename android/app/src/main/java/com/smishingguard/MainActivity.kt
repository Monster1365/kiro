package com.smishingguard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.smishingguard.notify.EXTRA_RECORD_ID
import com.smishingguard.ui.AnalyzeScreen
import com.smishingguard.ui.GuardianScreen
import com.smishingguard.ui.HistoryScreen
import com.smishingguard.ui.HomeScreen
import com.smishingguard.ui.ResultScreen
import com.smishingguard.ui.SettingsScreen
import com.smishingguard.ui.SetupScreen
import com.smishingguard.ui.SmishingGuardTheme
import com.smishingguard.ui.WelcomeScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** 알림을 눌러 들어온 경우 해당 결과 화면으로 이동하기 위한 값 */
    private var pendingRecordId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRecordId = intent?.getStringExtra(EXTRA_RECORD_ID)
        setContent {
            SmishingGuardTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(pendingRecordId, onConsumed = { pendingRecordId = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRecordId = intent.getStringExtra(EXTRA_RECORD_ID)
    }
}

private object Routes {
    const val WELCOME = "welcome"
    const val SETUP = "setup"
    const val GUARDIAN_ONBOARDING = "guardian_onboarding"
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ANALYZE = "analyze"
    const val GUARDIAN = "guardian"
    const val RESULT = "result/{id}"
    fun result(id: String) = "result/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "홈", Icons.Filled.Home),
    Tab(Routes.HISTORY, "기록", Icons.AutoMirrored.Filled.List),
    Tab(Routes.SETTINGS, "설정", Icons.Filled.Settings),
)

/** 저장된 온보딩 여부를 한 번 읽은 뒤, 그 값으로 시작 화면을 고정한다. */
@Composable
private fun AppRoot(pendingRecordId: String?, onConsumed: () -> Unit) {
    val container = (LocalContext.current.applicationContext as App).container
    var startOnboarded by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { startOnboarded = container.settings.current().onboarded }
    val onboarded = startOnboarded ?: return
    AppNav(container, onboarded, pendingRecordId, onConsumed)
}

@Composable
private fun AppNav(container: AppContainer, startOnboarded: Boolean, pendingRecordId: String?, onConsumed: () -> Unit) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val start = if (startOnboarded) Routes.HOME else Routes.WELCOME

    // 알림 → 결과 화면. 온보딩 전에는 이동하지 않는다.
    LaunchedEffect(pendingRecordId) {
        if (pendingRecordId != null) {
            if (startOnboarded) nav.navigate(Routes.result(pendingRecordId))
            onConsumed()
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = start, modifier = Modifier.padding(padding)) {
            composable(Routes.WELCOME) { WelcomeScreen(onNext = { nav.navigate(Routes.SETUP) }) }
            composable(Routes.SETUP) { SetupScreen(onNext = { nav.navigate(Routes.GUARDIAN_ONBOARDING) }) }
            composable(Routes.GUARDIAN_ONBOARDING) {
                // 보호자 등록은 건너뛰어도 온보딩이 끝난다. 건너뛰면 본인에게만 결과를 안내한다.
                GuardianScreen(onboarding = true, onDone = {
                    scope.launch {
                        container.settings.setOnboarded(true)
                        nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) { inclusive = true } }
                    }
                })
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onAnalyze = { nav.navigate(Routes.ANALYZE) },
                    onOpenRecord = { nav.navigate(Routes.result(it)) },
                    onOpenHistory = { nav.navigateTab(Routes.HISTORY) },
                )
            }
            composable(Routes.HISTORY) { HistoryScreen(onOpen = { nav.navigate(Routes.result(it)) }) }
            composable(Routes.SETTINGS) { SettingsScreen(onOpenGuardian = { nav.navigate(Routes.GUARDIAN) }) }
            composable(Routes.ANALYZE) {
                AnalyzeScreen(onResult = { id ->
                    nav.navigate(Routes.result(id)) { popUpTo(Routes.ANALYZE) { inclusive = true } }
                })
            }
            composable(Routes.GUARDIAN) { GuardianScreen(onboarding = false, onDone = { nav.popBackStack() }) }
            composable(Routes.RESULT) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                ResultScreen(recordId = id, onClose = { nav.popBackStack() })
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
