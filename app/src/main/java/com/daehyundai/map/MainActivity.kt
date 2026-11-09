package com.daehyundai.map

import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.daehyundai.map.feature.exploration.location.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.daehyundai.map.core.data.*
import com.daehyundai.map.core.location.*
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.home.HomeScreen
import com.daehyundai.map.feature.conquest.ConquestMapScreen
import com.daehyundai.map.feature.exploration.ExplorationMapScreen
import com.daehyundai.map.feature.exploration.rememberExplorationSession
import com.daehyundai.map.feature.profile.ProfileScreen
import com.daehyundai.map.navigation.Routes
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BootstrapApp() }
    }
}

@Composable private fun BootstrapApp() {
    val userId = "u-demo-01" // Synthetic account; not a production user identity.
    val conquest = remember { PreviewConquestFeature() }
    val (mockSession, explorationError) = rememberExplorationSession(userId)
    val context = LocalContext.current
    var sdkEnabled by rememberSaveable { mutableStateOf(false) }
    var sdkSelected by rememberSaveable { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf("") }
    val sdkState = rememberPleosExploration(userId, sdkEnabled)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        sdkEnabled = granted
        permissionMessage = if (granted) "" else "위치 권한 거부 · 설정에서 허용한 후 다시 시작하세요"
    }
    val explorationSession = if (sdkSelected) sdkState.session else mockSession
    val source = remember { MockLocationSource(userId) }
    val detector = remember { MockVisitDetector() }
    val scope = rememberCoroutineScope()
    var route by rememberSaveable { mutableStateOf(Routes.HOME) }
    var revision by remember { mutableIntStateOf(0) }
    var conquestSummary by remember { mutableStateOf(ConquestSummary(userId, null, 0, 0, null)) }
    val explorationSummary = explorationSession?.summary ?: ExplorationSummary(userId, 0, 0, 0, 0, null)
    LaunchedEffect(revision) {
        conquestSummary = conquest.getSummary(userId)
    }
    BackHandler(route != Routes.HOME) { route = Routes.HOME }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9DE4CF))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("대현대여지도", style = MaterialTheme.typography.headlineLarge)
                Text("공통 계약 v0.1 · 공동 승인 대기 · 개발용 통합 화면")
                Text("Pleos 위치 SDK 연결 · 주차 판정/실차 검증 미완료 · 실제 멀티플레이 아님")
                Text(if (sdkSelected) "개인 탐험·프로필: SDK 기록 (모의 기록과 별도)" else "개인 탐험·프로필: 모의 기록")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        sdkSelected = true
                        if (context.checkSelfPermission(PLEOS_LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                            permissionMessage = ""; sdkEnabled = true
                        } else permissionLauncher.launch(PLEOS_LOCATION_PERMISSION)
                    }, enabled = !sdkEnabled) { Text("SDK 위치 시작") }
                    OutlinedButton(onClick = { sdkEnabled = false }) { Text("수신 중지") }
                    OutlinedButton(onClick = { sdkEnabled = false; sdkSelected = false }) { Text("모의 기록 보기") }
                }
                if (sdkSelected) {
                    Text(sdkState.message)
                    if (permissionMessage.isNotEmpty()) Text(permissionMessage)
                    Text(sdkState.policy.description)
                    Text("앱을 보고 있을 때만 수신 · 에뮬레이터 SDK 기록은 실제 방문 기록과 분리합니다")
                }
                explorationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!sdkSelected && mockSession == null && explorationError == null) Text("모의 기록 불러오는 중…")
                HorizontalDivider()
                when (route) {
                    Routes.HOME -> HomeScreen(conquestSummary, mockSession?.summary ?: ExplorationSummary(userId, 0, 0, 0, 0, null),
                        onNavigate = { route = it }, onReplay = {
                            scope.launch {
                                source.samples().collect { sample ->
                                    val batch = detector.onSample(sample)
                                    batch.roads.forEach { conquest.onRoadVisited(it) }
                                    // Legacy integration fixtures are not real Namyang region IDs.
                                    // Region discovery uses the exploration screen's GPS replay.
                                }
                                revision++
                            }
                        })
                    Routes.CONQUEST_MAP -> ConquestMapScreen(conquestSummary)
                    Routes.EXPLORATION_MAP -> ExplorationMapScreen(explorationSummary, explorationSession,
                        onReplay = { scenario -> scope.launch { explorationSession?.replay(scenario) } })
                    Routes.PROFILE -> ProfileScreen(conquestSummary, explorationSummary, UserProgress(userId, 0, 1),
                        landmarks = explorationSession?.landmarkSummary)
                }
                if (route != Routes.HOME) OutlinedButton(onClick = { route = Routes.HOME }) { Text("홈으로") }
            }
        }
    }
}
