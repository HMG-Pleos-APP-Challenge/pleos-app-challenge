package com.daehyundai.map

import android.os.Bundle
import android.Manifest
import android.os.Build
import com.daehyundai.map.feature.exploration.tracking.ExplorationTracking
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
import com.daehyundai.map.feature.profile.IdentityGate
import com.daehyundai.map.navigation.Routes
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { IdentityGate { userId -> BootstrapApp(userId) } }
    }
}

@Composable private fun BootstrapApp(userId:String) {
    val conquest = remember { PreviewConquestFeature() }
    val (mockSession, explorationError) = rememberExplorationSession(userId)
    val context = LocalContext.current
    var sdkSelected by rememberSaveable { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf("") }
    val sdkState = rememberPleosExploration()
    val sdkEnabled = sdkState.running
    fun startTracking() {
        runCatching { ExplorationTracking.start(context,userId) }
            .onFailure { permissionMessage="기록을 시작하지 못했습니다. 화면에서 다시 시작해 주세요." }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val sdk=context.checkSelfPermission(PLEOS_LOCATION_PERMISSION)==PackageManager.PERMISSION_GRANTED
        val location=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(sdk && location) { permissionMessage=""; startTracking() }
        else permissionMessage="탐험 기록을 위해 위치 권한을 허용해 주세요."
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
                        val permissions=buildList {
                            add(PLEOS_LOCATION_PERMISSION); add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if(Build.VERSION.SDK_INT>=33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissionLauncher.launch(permissions.toTypedArray())
                    }, enabled = !sdkEnabled) { Text("탐험 기록 시작") }
                    OutlinedButton(onClick = { ExplorationTracking.stop(context) }) { Text("기록 중지") }
                    OutlinedButton(onClick = { sdkSelected = false }) { Text("모의 기록 보기") }
                }
                if (sdkSelected) {
                    Text(sdkState.message)
                    if (permissionMessage.isNotEmpty()) Text(permissionMessage)
                    Text(sdkState.policy.description)
                    Text("화면 밖에서도 기록 · 새 해금은 음성 알림 · 기록 중지로 종료")
                    Text(sdkState.voice)
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
                    Routes.PROFILE -> ProfileScreen(conquestSummary, explorationSummary, explorationSession?.progress ?: UserProgress(userId,0,1),
                        landmarks = explorationSession?.landmarkSummary)
                }
                if (route != Routes.HOME) OutlinedButton(onClick = { route = Routes.HOME }) { Text("홈으로") }
            }
        }
    }
}
