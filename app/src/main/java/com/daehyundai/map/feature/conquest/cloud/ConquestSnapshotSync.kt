package com.daehyundai.map.feature.conquest.cloud

import com.daehyundai.map.feature.conquest.domain.ConquestCatalog
import java.util.concurrent.CancellationException

internal data class ConquestSnapshotState(val snapshot: ConquestSnapshot? = null, val cached: Boolean = false,
    val issue: ConquestAccountIssue? = null)

/** Blocking IO only. No sample/record lock may be held on entry. All disk application is auth-fenced. */
internal class ConquestSnapshotSync(private val cache: ConquestSnapshotCache, private val access: ConquestServerAccess,
    private val expected: ConquestAuthSession, private val partition: ConquestServerPartition,
    private val catalog: ConquestCatalog, private val teams: List<String>, private val confirmedTeam: String) {
    fun cached(): ConquestAccountResult<ConquestSnapshot?> = access.withCurrentAccount(expected) {
        cache.read()?.also { require(it.team == confirmedTeam) }
    }

    fun refresh(checkCancellation: () -> Unit = {}): ConquestAccountResult<ConquestSnapshot> {
        checkCancellation()
        val reply = try { access.snapshot(expected,partition,catalog.version) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return ConquestAccountResult.Unavailable(ConquestAccountIssue.REQUEST) }
        if (reply is ConquestAccountResult.Unavailable) return reply
        reply as ConquestAccountResult.Success
        if (reply.session != expected) return ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED)
        val parsed = try { ConquestSnapshot.parse(reply.value,expected,partition,catalog,teams).also { require(it.team == confirmedTeam) } }
            catch (_: Exception) { return ConquestAccountResult.Unavailable(ConquestAccountIssue.INVALID_RESPONSE) }
        checkCancellation()
        val result = access.withCurrentAccount(expected) {
            checkCancellation()
            try { cache.save(parsed); parsed }
            catch (_: ConquestSnapshotOrderException) { null }
        }
        return when (result) {
            is ConquestAccountResult.Unavailable -> result
            is ConquestAccountResult.Success -> result.value?.let { ConquestAccountResult.Success(result.session,it) }
                ?: ConquestAccountResult.Unavailable(ConquestAccountIssue.STALE_SNAPSHOT)
        }
    }
}
