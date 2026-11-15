package com.daehyundai.map.feature.conquest.cloud

import java.util.concurrent.CancellationException

internal data class ConquestOutboxSyncCounts(val returned: Int, val pending: Int, val blocked: Int)
private enum class Admission { ADDED, EXISTING, FULL }

/** Blocking IO. The caller must not hold sample/record/queue locks while entering this coordinator. */
internal class ConquestOutboxSync(private val outbox: ConquestOutbox, private val server: ConquestServerAccess) {
    private fun matches(expected:ConquestAuthSession)=expected.projectRef==outbox.binding.projectRef &&
        expected.authUserId==outbox.binding.authUserId

    fun enqueue(expected:ConquestAuthSession,request:ConquestSubmission):ConquestAccountResult<Boolean> {
        if(!matches(expected)) return ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED)
        return when(val guarded=server.withCurrentAccount(expected) {
            try {if(outbox.enqueue(request)) Admission.ADDED else Admission.EXISTING}
            catch(_:ConquestOutboxFullException) {Admission.FULL}
        }) {
            is ConquestAccountResult.Unavailable -> guarded
            is ConquestAccountResult.Success -> when(guarded.value) {
                Admission.FULL -> ConquestAccountResult.Unavailable(ConquestAccountIssue.OUTBOX_FULL)
                Admission.ADDED -> ConquestAccountResult.Success(guarded.session,true)
                Admission.EXISTING -> ConquestAccountResult.Success(guarded.session,false)
            }
        }
    }

    @Synchronized fun sync(expected:ConquestAuthSession,checkCancellation:()->Unit={}):ConquestAccountResult<ConquestOutboxSyncCounts> {
        checkCancellation()
        if(!matches(expected)) return ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED)
        val sent=try {outbox.batch()} catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {return ConquestAccountResult.Unavailable(ConquestAccountIssue.READ_OUTBOX)}
        if(sent.isEmpty()) return server.withCurrentAccount(expected) {
            ConquestOutboxSyncCounts(0,outbox.pendingCount(),outbox.blockedCount())
        }
        // batch() has already released the record/SQLite lock. No queue lock during HTTP.
        val remote=try {
            server.submit(expected,outbox.binding.partition,outbox.binding.catalogVersion,sent.map {it.request})
        } catch(cancelled:CancellationException) {throw cancelled}
          catch(_:Exception) {return ConquestAccountResult.Unavailable(ConquestAccountIssue.REQUEST)}
        if(remote is ConquestAccountResult.Unavailable) return remote
        remote as ConquestAccountResult.Success
        if(remote.session!=expected) return ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED)
        checkCancellation()
        val response=try {ConquestSubmitResponse.parse(remote.value,expected,outbox.binding.partition,
            outbox.binding.catalogVersion,sent.map {it.request})}
            catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {return ConquestAccountResult.Unavailable(ConquestAccountIssue.INVALID_RESPONSE)}
        return server.withCurrentAccount(expected) {
            // Auth -> short record/SQLite transaction. No HTTP, suspension or UI here.
            checkCancellation()
            outbox.apply(sent,response)
            ConquestOutboxSyncCounts(response.outcomes.size,outbox.pendingCount(),outbox.blockedCount())
        }
    }
}
