package com.daehyundai.map.feature.conquest

import android.content.Context
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
    private val teams: List<String>) {
    var state by mutableStateOf<ConquestViewState?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val mutex = Mutex()

    private suspend fun update(action: () -> Unit) = mutex.withLock {
        try {
            val snapshot = withContext(Dispatchers.IO) {
                action()
                val summary = feature.getSummary(userId)
                ConquestViewState(catalog, summary, feature.owners(), teams)
            }
            state = snapshot
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "점령 기록을 처리하지 못했습니다. 기존 기록은 유지되며 다시 확인이 필요합니다."
        }
    }

    suspend fun refresh() = update { }
    suspend fun selectTeam(teamId: String) = update {
        check(pipeline.selectTeam(userId, teamId)) { "Team cannot change" }
    }
    suspend fun onSample(sample: LocationSample) = update { pipeline.onSample(sample) }

    companion object {
        suspend fun open(context: Context, userId: String,
            teams: List<String> = listOf("team-red", "team-blue")): ConquestSession = withContext(Dispatchers.IO) {
            require(userId.isNotBlank() && teams.isNotEmpty() && teams.distinct().size == teams.size)
            val catalog = context.assets.open("conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
            val feature = LocalConquestFeature(teams.toSet(), catalog.roads.map { it.id }.toSet(), catalog.version,
                LocationSourceType.MOCK, FileConquestStore(File(context.filesDir, "conquest/mock-${catalog.version}.bin")))
            ConquestSession(userId, catalog, feature,
                ConquestLocationPipeline(feature, catalog, LocationSourceType.MOCK), teams)
        }
    }
}

/** App-owned MOCK session; no live location permissions, subscriptions, or demo replay tools are added. */
@Composable fun rememberConquestSession(userId: String): Pair<ConquestSession?, String?> {
    val context = LocalContext.current.applicationContext
    var session by remember(userId) { mutableStateOf<ConquestSession?>(null) }
    var error by remember(userId) { mutableStateOf<String?>(null) }
    LaunchedEffect(userId) {
        try {
            session = ConquestSession.open(context, userId).also { it.refresh() }
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
