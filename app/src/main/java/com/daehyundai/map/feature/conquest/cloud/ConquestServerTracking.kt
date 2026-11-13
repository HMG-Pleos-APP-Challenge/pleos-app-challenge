package com.daehyundai.map.feature.conquest.cloud

import android.content.Context
import com.daehyundai.map.core.model.LocationSample
import com.daehyundai.map.feature.conquest.domain.ConquestCatalog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean

internal data class ConquestTransferState(
    val collecting: Boolean = false,
    val uploadsEnabled: Boolean = false,
    val pending: Int = 0,
    val blocked: Int = 0,
    val notStored: Int = 0,
    val issue: ConquestAccountIssue? = null,
) {
    val message: String get() = when {
        issue == ConquestAccountIssue.ACCOUNT_CHANGED || issue == ConquestAccountIssue.NO_SESSION ->
            "점령 계정 확인 필요 · 새 근거 수집을 중지했습니다. 기존 미전송 기록은 보존됩니다."
        !collecting -> "점령은 이 기기의 기록을 사용합니다 · 서버 근거 수집·업로드 비활성"
        else -> buildString {
            append(if (uploadsEnabled) "서버 확인 대기" else "서버 업로드 비활성 · 기기 보관")
            append(" ${pending}건")
            if (blocked > 0) append(" · 충돌 확인 필요 ${blocked}건")
            if (notStored > 0) append(" · 기기에 저장하지 못한 근거 ${notStored}건 (이번 실행)")
            if (issue == ConquestAccountIssue.OUTBOX_FULL) append(" · 보관 한도 도달, 새 제출 보류")
            else if (issue != null) append(" · 저장·전송 상태 확인 필요")
        }
    }
}

/** One confirmed account/generation/team/catalog lifetime. No auth, disk or HTTP on the sample caller.
 * The bounded handoff is not durable storage; a full handoff reports an unstored proof explicitly.
 */
internal class ConquestServerTracking private constructor(
    private val expected: ConquestAuthSession,
    private val outbox: ConquestOutbox,
    private val access: ConquestServerAccess,
    private val capture: ConquestServerCapture,
    private val uploadsEnabled: Boolean,
    handoffCapacity: Int,
    private val retryDelayMs: Long,
) {
    private sealed interface Work {
        data class Store(val request: ConquestSubmission) : Work
        data object Retry : Work
    }
    private val work = Channel<Work>(handoffCapacity, onUndeliveredElement={ if (it is Work.Store) unstored() })
    private val retryQueued = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sync = ConquestOutboxSync(outbox, access)
    private var accepting = true
    private val mutable = MutableStateFlow(ConquestTransferState(collecting=true, uploadsEnabled=uploadsEnabled))
    val state = mutable.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = scope.launch {
        try {
            refreshCounts()
            while (isActive) {
                // Select the timer atomically instead of cancelling a receive that may have consumed a proof.
                val next = select {
                    work.onReceiveCatching { it }
                    onTimeout(retryDelayMs) { null }
                }
                if (next?.isClosed == true) break
                when (val item = next?.getOrNull()) {
                    is Work.Store -> store(item.request)
                    Work.Retry -> retryQueued.set(false)
                    null -> Unit
                }
                // Explicitly disabled for the app until isolated deployment/activation is completed.
                if (uploadsEnabled && state.value.collecting) upload()
                else if (next?.getOrNull() == Work.Retry) refreshCounts()
            }
        } finally {
            stop()
            try { outbox.close() } catch (_: Exception) { mutable.update { it.copy(issue=ConquestAccountIssue.APPLY) } }
            scope.cancel()
        }
    }

    @Synchronized fun onSample(sample: LocationSample) {
        if (!accepting) return
        capture.onSample(sample).forEach { completion ->
            val request = completion.request
            if (request == null || !work.trySend(Work.Store(request)).isSuccess) unstored()
        }
    }

    @Synchronized fun resetInput() = capture.reset()

    fun requestRetry(): Boolean {
        if (!retryQueued.compareAndSet(false,true)) return true
        if (work.trySend(Work.Retry).isSuccess) return true
        retryQueued.set(false)
        return false
    }

    fun inspectAccount(result: ConquestAccountResult<Unit>) {
        if (result is ConquestAccountResult.Success && result.session == expected) return
        accountUnavailable(if (result is ConquestAccountResult.Unavailable) result.issue else ConquestAccountIssue.ACCOUNT_CHANGED)
        stop()
    }

    /** Stop new observations and drain already handed-off writes under their ORIGINAL auth fence. */
    @Synchronized fun stop() {
        accepting = false
        capture.reset()
        mutable.update { it.copy(collecting=false) }
        work.close()
    }

    suspend fun stopAndJoin() { stop(); worker.join() }

    private fun unstored(issue: ConquestAccountIssue? = null) {
        mutable.update { it.copy(notStored=it.notStored+1, issue=issue ?: it.issue) }
    }

    private fun accountUnavailable(issue: ConquestAccountIssue) {
        if (issue == ConquestAccountIssue.ACCOUNT_CHANGED || issue == ConquestAccountIssue.NO_SESSION) stop()
        mutable.update { it.copy(issue=issue) }
    }

    private fun refreshCounts() {
        try { mutable.update { it.copy(pending=outbox.pendingCount(), blocked=outbox.blockedCount()) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(issue=ConquestAccountIssue.READ_OUTBOX) } }
    }

    private fun store(request: ConquestSubmission) {
        val result = try { sync.enqueue(expected, request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ConquestAccountResult.Unavailable(ConquestAccountIssue.APPLY) }
        when (result) {
            is ConquestAccountResult.Success -> {
                mutable.update { it.copy(issue=null) }
                refreshCounts()
            }
            is ConquestAccountResult.Unavailable -> {
                unstored(result.issue)
                accountUnavailable(result.issue)
            }
        }
    }

    private suspend fun upload() {
        val context = currentCoroutineContext()
        val result = try { sync.sync(expected) { context.ensureActive() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ConquestAccountResult.Unavailable(ConquestAccountIssue.REQUEST) }
        when (result) {
            is ConquestAccountResult.Success -> mutable.update {
                it.copy(pending=result.value.pending, blocked=result.value.blocked, issue=null)
            }
            is ConquestAccountResult.Unavailable -> accountUnavailable(result.issue)
        }
    }

    companion object {
        /** Only a validated select-team reply can prepare tracking. Does not select a team or sign up.
         * Call outside all sample/record locks. Production session always passes uploadsEnabled=false.
         */
        fun prepare(context: Context, installationId: String, catalog: ConquestCatalog,
            partition: ConquestServerPartition, teams: List<String>, expected: ConquestAuthSession,
            reply: ConquestAccountResult.Success<String>, requestedTeam: String, access: ConquestServerAccess,
            uploadsEnabled: Boolean = false, limits: ConquestOutboxLimits = ConquestOutboxLimits(),
            handoffCapacity: Int = 16, retryDelayMs: Long = 15_000, maxSamples: Int = 2048,
        ): ConquestAccountResult<ConquestServerTracking> {
            require(handoffCapacity in 1..16 && retryDelayMs > 0 && maxSamples in 3..2048)
            if (reply.session != expected) return ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED)
            val team = try {
                require(requestedTeam in teams)
                ConquestTeamResponse.parse(reply.value, expected, partition, catalog.version, requestedTeam)
                    .also { require(it.teamId in teams) }
            } catch (_: Exception) { return ConquestAccountResult.Unavailable(ConquestAccountIssue.INVALID_RESPONSE) }
            return when (val opened = ConquestOutbox.openCurrent(context, access, expected, installationId, partition, catalog.version, limits)) {
                is ConquestAccountResult.Unavailable -> opened
                is ConquestAccountResult.Success -> ConquestAccountResult.Success(expected,
                    ConquestServerTracking(expected, opened.value, access,
                        ConquestServerCapture(installationId, catalog, team.selectedUtcMs, maxSamples),
                        uploadsEnabled, handoffCapacity, retryDelayMs))
            }
        }
    }
}
