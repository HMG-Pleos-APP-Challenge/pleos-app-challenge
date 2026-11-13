package com.daehyundai.map.feature.conquest.cloud

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Deployment gate, independent of Supabase key presence. Enabling needs isolated Auth/API verification. */
internal object ConquestServerRollout { const val membershipEnabled = false }

internal data class ConquestMembershipState(
    val loading: Boolean = false, val needsTeam: Boolean = false,
    val teamId: String? = null, val issue: ConquestAccountIssue? = null,
) {
    val message: String get() = when {
        loading -> "점령 서버 소속 확인 중"
        issue == ConquestAccountIssue.NO_SESSION -> "저장된 계정이 없습니다 · 점령 서버 가입은 실행하지 않았습니다."
        issue == ConquestAccountIssue.ACCOUNT_CHANGED -> "계정이 변경됐습니다 · 점령 서버 소속을 다시 확인해 주세요."
        issue != null -> "점령 서버 소속을 확인하지 못했습니다 · 기존 기기 기록은 유지됩니다."
        needsTeam -> "서버에서 사용할 팀을 선택하세요 · 처음 선택한 팀은 변경할 수 없습니다."
        teamId != null -> "서버 팀: $teamId · 기기 기록의 팀과 별도로 확인한 소속입니다."
        else -> "점령 서버 소속 확인 전"
    }
}

/** Signup-free recovery first; team creation requires a separate explicit selection action.
 * Call outside sample/record locks. Installer runs AFTER RPC/auth locks release, and guards storage again.
 */
internal class ConquestMembershipConnection(
    private val access: ConquestServerAccess,
    private val partition: ConquestServerPartition,
    private val catalog: String,
    private val teams: List<String>,
    private val install: suspend (ConquestAuthSession, ConquestTeamResponse) -> ConquestAccountResult<Unit>,
) {
    private val mutex = Mutex()
    @Volatile private var expected: ConquestAuthSession? = null
    private val active = AtomicBoolean(true)
    private val mutable = MutableStateFlow(ConquestMembershipState())
    val state = mutable.asStateFlow()
    val isActive: Boolean get() = active.get()

    fun deactivate() { active.set(false) }

    fun inspectAccount(result: ConquestAccountResult<Unit>) {
        val owner = expected ?: return
        if (result is ConquestAccountResult.Success && result.session == owner) return
        deactivate()
        failed(if (result is ConquestAccountResult.Unavailable) result.issue else ConquestAccountIssue.ACCOUNT_CHANGED)
    }

    suspend fun connect() = mutex.withLock {
        if (!active.get()) return@withLock
        expected = null
        mutable.value = ConquestMembershipState(loading=true)
        val account = io(ConquestAccountIssue.READ_SESSION) { access.existingAccount() }
        if (account is ConquestAccountResult.Unavailable) { failed(account.issue); return@withLock }
        account as ConquestAccountResult.Success
        val owner = account.session
        if (!active.get()) return@withLock
        expected = owner
        val reply = io(ConquestAccountIssue.REQUEST) { access.membership(owner,partition,catalog) }
        if (!active.get()) return@withLock
        if (reply is ConquestAccountResult.Unavailable) { failed(reply.issue); return@withLock }
        reply as ConquestAccountResult.Success
        if (reply.session != owner) { failed(ConquestAccountIssue.ACCOUNT_CHANGED); return@withLock }
        val membership = try { ConquestMembershipResponse.parse(reply.value,owner,partition,catalog)
            .also { require(it.team == null || it.team.teamId in teams) } }
            catch (_: Exception) { failed(ConquestAccountIssue.INVALID_RESPONSE); return@withLock }
        if (membership.team != null) accept(owner,membership.team)
        else {
            val checked = io(ConquestAccountIssue.APPLY) { access.withCurrentAccount(owner) { Unit } }
            if (checked is ConquestAccountResult.Unavailable) failed(checked.issue)
            else if ((checked as ConquestAccountResult.Success).session != owner) failed(ConquestAccountIssue.ACCOUNT_CHANGED)
            else {
                expected = owner
                mutable.value = ConquestMembershipState(needsTeam=true)
            }
        }
    }

    suspend fun selectTeam(team: String) = mutex.withLock {
        val owner = expected
        if (!active.get() || owner == null || !state.value.needsTeam || team !in teams) return@withLock
        mutable.value = ConquestMembershipState(loading=true)
        val reply = io(ConquestAccountIssue.REQUEST) { access.selectTeam(owner,partition,catalog,team) }
        if (!active.get()) return@withLock
        if (reply is ConquestAccountResult.Unavailable) { failed(reply.issue); return@withLock }
        reply as ConquestAccountResult.Success
        if (reply.session != owner) { failed(ConquestAccountIssue.ACCOUNT_CHANGED); return@withLock }
        val confirmed = try { ConquestTeamResponse.parse(reply.value,owner,partition,catalog,team)
            .also { require(it.teamId in teams) } }
            catch (_: Exception) { failed(ConquestAccountIssue.INVALID_RESPONSE); return@withLock }
        accept(owner,confirmed)
    }

    private suspend fun accept(owner: ConquestAuthSession, team: ConquestTeamResponse) {
        if (!active.get()) return
        // Installer opens the account-bound outbox under withCurrentAccount. Never install under HTTP/auth locks.
        val result = try { install(owner,team) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ConquestAccountResult.Unavailable(ConquestAccountIssue.APPLY) }
        if (!active.get()) return
        if (result is ConquestAccountResult.Unavailable) failed(result.issue)
        else if ((result as ConquestAccountResult.Success).session != owner) failed(ConquestAccountIssue.ACCOUNT_CHANGED)
        else {
            expected = owner
            mutable.value = ConquestMembershipState(teamId=team.teamId)
        }
    }

    private fun failed(issue: ConquestAccountIssue) {
        expected = null
        mutable.value = ConquestMembershipState(issue=issue)
    }

    private suspend fun <T> io(issue: ConquestAccountIssue, action: () -> ConquestAccountResult<T>): ConquestAccountResult<T> =
        withContext(Dispatchers.IO) {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ConquestAccountResult.Unavailable(issue) }
        }
}
