package com.daehyundai.map.feature.conquest

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.data.*
import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ConquestViewState(val catalog: ConquestCatalog, val summary: ConquestSummary,
    val owners: Map<String, String?>, val teamIds: List<String>)

/** One session at the app composition root. Screens receive snapshots, not direct access to storage. */
class ConquestSession private constructor(private val userId: String, private val catalog: ConquestCatalog,
    private val feature: LocalConquestFeature, private val pipeline: ConquestLocationPipeline,
    private val teams: List<String>, val demoEnabled: Boolean, val sourceType:LocationSourceType) {
    private var savedState by mutableStateOf<ConquestViewState?>(null)
    private var demoState by mutableStateOf<ConquestViewState?>(null)
    val state get() = demoState ?: savedState
    private var demo: ConquestDemo? = null
    var demoLabel by mutableStateOf<String?>(null)
        private set
    var demoSettled by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val mutex = Mutex()

    private suspend fun change(action: suspend () -> Unit) = mutex.withLock {
        try {
            action()
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "점령 기록을 처리하지 못했습니다. 기존 기록은 유지되며 다시 확인이 필요합니다."
        }
    }

    private suspend fun update(action: () -> Unit) = change {
        savedState = withContext(Dispatchers.IO) {
            action()
            ConquestViewState(catalog, feature.getSummary(userId), feature.owners(), teams)
        }
    }

    suspend fun replayDemo(route: DemoRoute) = change {
        if (!demoEnabled) return@change
        val (nextDemo, snapshot) = withContext(Dispatchers.IO) {
            val next = ConquestDemo(userId, catalog, teams, feature.teamOf(userId) ?: teams.first())
            next to next.replay(route)
        }
        demo = nextDemo
        demoState = snapshot
        demoLabel = route.label
        demoSettled = false
    }

    suspend fun settleDemo() = change {
        val current = demo ?: return@change
        demoState = withContext(Dispatchers.IO) { current.settle() }
        demoSettled = current.settled
    }

    suspend fun exitDemo() = change {
        demo = null
        demoState = null
        demoLabel = null
        demoSettled = false
    }

    fun resetInput() { pipeline.resetObservations(userId) }
    suspend fun refresh() = update { }
    suspend fun selectTeam(teamId: String) = update {
        check(pipeline.selectTeam(userId, teamId)) { "Team cannot change" }
    }
    suspend fun onSample(sample: LocationSample) = update { pipeline.onSample(sample) }

    companion object {
        private val sessions=mutableMapOf<String,ConquestSession>()
        private val opening=Mutex()
        suspend fun open(context: Context, userId: String,
            teams: List<String> = listOf("team-red", "team-blue"),
            source:LocationSourceType=LocationSourceType.MOCK, recordPrefix:String="mock"): ConquestSession = opening.withLock { withContext(Dispatchers.IO) {
            require(userId.isNotBlank() && teams.isNotEmpty() && teams.distinct().size == teams.size)
            val catalog = context.assets.open("conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
            val prefix=if(source==LocationSourceType.MOCK) "mock" else recordPrefix.also { require(it in setOf("pleos","pleos-emulator")) }
            val file=File(context.filesDir,"conquest/$prefix-${catalog.version}.bin")
            sessions[file.canonicalPath]?.let {
                require(it.userId==userId && it.teams==teams && it.sourceType==source) { "점령 파일 세션의 사용자·팀·출처 충돌" }
                return@withContext it
            }
            val feature = LocalConquestFeature(teams.toSet(), catalog.roads.map { it.id }.toSet(), catalog.version,
                source, FileConquestStore(file))
            ConquestSession(userId, catalog, feature,
                ConquestLocationPipeline(feature, catalog, source), teams,
                source==LocationSourceType.MOCK && (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,source)
                .also { sessions[file.canonicalPath]=it }
        } }
    }
}

/** App-owned MOCK session with an isolated, debug-only demonstration. */
@Composable fun rememberConquestSession(userId: String,source:LocationSourceType=LocationSourceType.MOCK,recordPrefix:String="mock"): Pair<ConquestSession?, String?> {
    val context = LocalContext.current.applicationContext
    var session by remember(userId,source,recordPrefix) { mutableStateOf<ConquestSession?>(null) }
    var error by remember(userId,source,recordPrefix) { mutableStateOf<String?>(null) }
    LaunchedEffect(userId,source,recordPrefix) {
        try {
            session = ConquestSession.open(context, userId,source=source,recordPrefix=recordPrefix).also { it.refresh() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "점령 데이터를 열 수 없습니다. 기존 파일은 보존했습니다."
        }
    }
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        while (isActive) {
            current.refresh()
            delay(30_000) // Foreground refresh only; this is not an Android background scheduler.
        }
    }
    return session to error
}
