package com.daehyundai.map.feature.exploration.location

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.exploration.ExplorationSession
import com.daehyundai.map.feature.exploration.openExplorationSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PleosExplorationState {
    var policy by mutableStateOf(PleosAccuracyPolicy.UNVERIFIED)
        internal set
    var session by mutableStateOf<ExplorationSession?>(null)
        internal set
    var message by mutableStateOf("SDK 위치 수신 꺼짐")
        internal set
    var receivedCount by mutableIntStateOf(0)
        internal set
}

@Composable fun rememberPleosExploration(userId: String, enabled: Boolean): PleosExplorationState {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(userId) { PleosExplorationState() }
    LaunchedEffect(userId, enabled, lifecycle) {
        if (!enabled) { state.message = "SDK 위치 수신 꺼짐"; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                // Reopen earned records, discard pending dwell across foreground sessions.
                val policy = pleosAccuracyPolicy(context)
                state.policy = policy
                val session = withContext(Dispatchers.IO) {
                    openExplorationSession(context, userId, LocationSourceType.PLEOS, policy.recordPrefix)
                }
                session.refresh()
                state.session = session
                state.message = "SDK 연결 중 · 위치 업데이트 대기"
                val source = PleosLocationSource(userId, { PleosSdkConnection(context) }, accuracyPolicy = policy)
                source.samples().collect { sample ->
                    session.acceptSdk(sample)
                    state.receivedCount++
                    state.message = "SDK 위치 ${state.receivedCount}회 수신 · ${session.status}"
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                state.message = "SDK 위치 수신 실패 · 기기 지원·권한·위치 공유 설정 확인 후 다시 시작하세요"
            }
        }
    }
    return state
}
