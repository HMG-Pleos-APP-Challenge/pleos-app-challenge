package com.hmg.pleos.mapgame

import android.os.Bundle
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
import com.hmg.pleos.mapgame.core.data.*
import com.hmg.pleos.mapgame.core.location.*
import com.hmg.pleos.mapgame.core.model.*
import com.hmg.pleos.mapgame.feature.home.HomeScreen
import com.hmg.pleos.mapgame.feature.conquest.ConquestMapScreen
import com.hmg.pleos.mapgame.feature.exploration.ExplorationMapScreen
import com.hmg.pleos.mapgame.feature.profile.ProfileScreen
import com.hmg.pleos.mapgame.navigation.Routes
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
    val exploration = remember { PreviewExplorationFeature() }
    val source = remember { MockLocationSource(userId) }
    val detector = remember { MockVisitDetector() }
    val scope = rememberCoroutineScope()
    var route by rememberSaveable { mutableStateOf(Routes.HOME) }
    var revision by remember { mutableIntStateOf(0) }
    var conquestSummary by remember { mutableStateOf(ConquestSummary(userId, null, 0, 0, null)) }
    var explorationSummary by remember { mutableStateOf(ExplorationSummary(userId, 0, 0, 0, 0, null)) }
    LaunchedEffect(revision) {
        conquestSummary = conquest.getSummary(userId)
        explorationSummary = exploration.getSummary(userId)
    }
    BackHandler(route != Routes.HOME) { route = Routes.HOME }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9DE4CF))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("PLEOS MAP GAME", style = MaterialTheme.typography.headlineLarge)
                Text("공통 계약 v0.1 · 공동 승인 대기 · 개발용 Mock 통합 화면")
                Text("실주행 사용 불가 · 위치 SDK/주차 판정 미연동 · 실제 멀티플레이 아님")
                HorizontalDivider()
                when (route) {
                    Routes.HOME -> HomeScreen(conquestSummary, explorationSummary,
                        onNavigate = { route = it }, onReplay = {
                            scope.launch {
                                source.samples().collect { sample ->
                                    val batch = detector.onSample(sample)
                                    batch.roads.forEach { conquest.onRoadVisited(it) }
                                    batch.regions.forEach { exploration.onRegionDiscovered(it) }
                                }
                                revision++
                            }
                        })
                    Routes.CONQUEST_MAP -> ConquestMapScreen(conquestSummary)
                    Routes.EXPLORATION_MAP -> ExplorationMapScreen(explorationSummary)
                    Routes.PROFILE -> ProfileScreen(conquestSummary, explorationSummary, UserProgress(userId, 0, 1))
                }
                if (route != Routes.HOME) OutlinedButton(onClick = { route = Routes.HOME }) { Text("홈으로") }
            }
        }
    }
}
