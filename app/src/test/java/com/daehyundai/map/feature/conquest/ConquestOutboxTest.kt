package com.daehyundai.map.feature.conquest

import android.database.sqlite.SQLiteDatabase
import android.content.ContextWrapper
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.cloud.*
import com.daehyundai.map.feature.conquest.data.ConquestAccountRestore
import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class ConquestOutboxTest {
    @get:Rule val folder=TemporaryFolder()
    private val binding=ConquestAccountBinding("abcdefghijklmnopqrst","11111111-1111-4111-8111-111111111111",
        "u",ConquestServerPartition.PLEOS,"roads-v1")
    private val owner=ConquestAuthSession(binding.projectRef,binding.authUserId,1)
    private val file get()=File(folder.root,"conquest/server-accounts/outbox.sqlite")
    private fun open(limits:ConquestOutboxLimits=ConquestOutboxLimits(),hook:()->Unit={})=
        ConquestOutbox(file,binding,limits,hook)
    private fun packet(suffix:String=""):ConquestSubmission {
        val points=(0..3).map {LocationSample("s$it$suffix","u",37.2,126.8+it*.0002,1000L+it*2000,3f,LocationSourceType.PLEOS)}
        return ConquestSubmission.create(RoadVisited("s3$suffix:conquest:ab","u","ab",7000,LocationSourceType.PLEOS),points)
    }
    private fun rejects(action:()->Unit) {
        try {action()} catch(_:Exception) {return};fail("Expected preserved/blocked store")
    }
    private fun response(expected:ConquestAuthSession,events:List<ConquestSubmission>,status:ConquestSubmitStatus):String {
        val rows=JSONArray()
        events.forEach {rows.put(JSONObject().put("eventId",it.eventId).put("roadId",it.roadId).put("occurredMs",it.occurredMs)
            .put("requestHash",it.requestHash).put("status",status.name).put("acknowledge",status.acknowledge).put("retryable",status.retryable))}
        return JSONObject().put("protocol",1).put("userId",expected.authUserId).put("partition","pleos")
            .put("catalogVersion","roads-v1").put("receivedUtcMs",9000).put("results",rows).toString()
    }
    private inner class Server:ConquestServerAccess {
        override fun snapshot(expected:ConquestAuthSession,partition:ConquestServerPartition,catalog:String)
            :ConquestAccountResult<String> = error("No snapshot request expected")
        override fun membership(expected:ConquestAuthSession,partition:ConquestServerPartition,catalog:String)
            :ConquestAccountResult<String> = error("No membership request expected")
        var current:ConquestAuthSession?=owner
        var calls=0
        var beforeReply:()->Unit={}
        var status=ConquestSubmitStatus.ACCEPTED
        var take=20
        var malformed=false
        var applyFailure:Exception?=null
        override fun existingAccount():ConquestAccountResult<Unit> = current?.let {ConquestAccountResult.Success(it,Unit)}
            ?: ConquestAccountResult.Unavailable(ConquestAccountIssue.NO_SESSION)
        override fun <T> withCurrentAccount(expected:ConquestAuthSession,apply:()->T):ConquestAccountResult<T> {
            if(current==null) return ConquestAccountResult.Unavailable(ConquestAccountIssue.NO_SESSION)
            if(current!=expected) return ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED)
            return try {ConquestAccountResult.Success(expected,apply())} catch(cancelled:CancellationException) {throw cancelled}
                catch(failure:Exception) {applyFailure=failure;ConquestAccountResult.Unavailable(ConquestAccountIssue.APPLY)}
        }
        override fun selectTeam(expected:ConquestAuthSession,partition:ConquestServerPartition,catalog:String,teamId:String)
            :ConquestAccountResult<String> = error("No team request expected")
        override fun submit(expected:ConquestAuthSession,partition:ConquestServerPartition,catalog:String,events:List<ConquestSubmission>)
            :ConquestAccountResult<String> {
            calls++;beforeReply()
            return ConquestAccountResult.Success(expected,if(malformed) "broken" else response(expected,events.take(take),status))
        }
    }

    @Test fun restartAndDuplicateEnqueuePreserveExactPayload() {
        val packet=packet()
        open().use {assertTrue(it.enqueue(packet));assertFalse(it.enqueue(packet))}
        open().use {assertEquals(1,it.pendingCount());assertEquals(packet.requestJson,it.batch().single().request.requestJson)}
        open().use {rejects {it.enqueue(ConquestSubmission.restore(" "+packet.requestJson))};assertEquals(1,it.pendingCount())}
    }

    @Test fun countAndByteCapacityPreserveOldRowsWithoutEviction() {
        open(ConquestOutboxLimits(1,10000)).use {
            it.enqueue(packet());rejects {it.enqueue(packet("-new"))};assertEquals(1,it.pendingCount())
        }
        open(ConquestOutboxLimits(10,packet().requestJson.toByteArray().size.toLong())).use {
            rejects {it.enqueue(packet("-new"))};assertEquals(1,it.pendingCount())
        }
    }

    @Test fun capacityIsReportedAsDeferralWithoutDroppingExistingProof() {
        open(ConquestOutboxLimits(1,10000)).use {box ->
            val sync=ConquestOutboxSync(box,Server())
            assertEquals(ConquestAccountResult.Success(owner,true),sync.enqueue(owner,packet()))
            assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.OUTBOX_FULL),sync.enqueue(owner,packet("-new")))
            assertEquals(packet().requestJson,box.batch().single().request.requestJson)
        }
    }

    @Test fun deviceBudgetIncludesOtherAccountPartitionFilesAndFreesAfterAck() {
        val limits=ConquestOutboxLimits(1,10000)
        open(limits).use {first ->
            first.enqueue(packet())
            ConquestOutbox(File(file.parentFile,"other.sqlite"),binding.copy(partition=ConquestServerPartition.PLEOS_EMULATOR),limits).use {second ->
                rejects {second.enqueue(packet("-second"))}
                assertEquals(1,first.pendingCount());assertEquals(0,second.pendingCount())
                first.apply(first.batch(),ConquestSubmitResponse(9000,listOf(ConquestSubmitOutcome(packet().eventId,ConquestSubmitStatus.ACCEPTED))))
                assertTrue(second.enqueue(packet("-second")))
            }
        }
    }

    @Test fun partialAckAndConcurrentEnqueueLeaveUnreturnedRowsAcrossRestart() {
        val server=Server().apply {take=1}
        open().use {box ->
            box.enqueue(packet());box.enqueue(packet("-second"))
            val executor=Executors.newSingleThreadExecutor()
            try {
                server.beforeReply={executor.submit<Boolean> {box.enqueue(packet("-in-flight"))}.get(2,TimeUnit.SECONDS)}
                assertTrue(ConquestOutboxSync(box,server).sync(owner) is ConquestAccountResult.Success)
            } finally {executor.shutdownNow()}
            assertEquals(2,box.pendingCount());assertEquals(ConquestSubmitStatus.ACCEPTED,box.outcome(packet().eventId))
        }
        open().use {assertEquals(2,it.pendingCount());assertFalse(it.enqueue(packet()));assertEquals(2,it.pendingCount())}
    }

    @Test fun lostResponseRetriesWithoutDeletingPendingProof() {
        val server=Server()
        open().use {box ->
            box.enqueue(packet());val sync=ConquestOutboxSync(box,server)
            server.beforeReply={throw IOException("Lost response")}
            assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.REQUEST),sync.sync(owner))
            assertEquals(1,box.pendingCount())
            server.beforeReply={};assertTrue(sync.sync(owner) is ConquestAccountResult.Success)
            assertEquals(0,box.pendingCount());assertEquals(2,server.calls)
        }
    }

    @Test fun accountGenerationChangeOrSessionLossBeforeApplyNeverAcks() {
        for(noSession in listOf(false,true)) {
            val server=Server()
            open().use {box ->
                box.enqueue(packet());server.beforeReply={server.current=if(noSession) null else owner.copy(generation=3)}
                val result=ConquestOutboxSync(box,server).sync(owner)
                assertEquals(ConquestAccountResult.Unavailable(if(noSession) ConquestAccountIssue.NO_SESSION else ConquestAccountIssue.ACCOUNT_CHANGED),result)
                assertEquals(1,box.pendingCount());assertNull(box.outcome(packet().eventId))
            }
        }
    }

    @Test fun foreignOwnerIsBlockedBeforeNetwork() {
        val server=Server()
        open().use {box ->
            box.enqueue(packet())
            assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED),
                ConquestOutboxSync(box,server).sync(owner.copy(projectRef="tsrqponmlkjihgfedcba")))
            assertEquals(0,server.calls);assertEquals(1,box.pendingCount())
        }
    }

    @Test fun invalidResponsePreservesAllProofs() {
        val server=Server().apply {malformed=true}
        open().use {box ->
            box.enqueue(packet())
            assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.INVALID_RESPONSE),ConquestOutboxSync(box,server).sync(owner))
            assertEquals(1,box.pendingCount());assertNull(box.outcome(packet().eventId))
        }
    }

    @Test fun failedAckCommitRollsBackProofDeletionAndCachedOutcome() {
        var fail=false;val server=Server()
        open(hook={if(fail) throw IOException("Injected commit failure")}).use {box ->
            box.enqueue(packet());fail=true
            val sync=ConquestOutboxSync(box,server)
            assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.APPLY),sync.sync(owner))
            assertEquals(1,box.pendingCount());assertNull(box.outcome(packet().eventId))
            fail=false;assertTrue(sync.sync(owner) is ConquestAccountResult.Success)
            assertEquals(0,box.pendingCount());assertEquals(ConquestSubmitStatus.ACCEPTED,box.outcome(packet().eventId))
        }
    }

    @Test fun conflictIsDurablyBlockedAndNotAutomaticallyRetried() {
        val server=Server().apply {status=ConquestSubmitStatus.CONFLICT}
        open().use {box ->
            box.enqueue(packet());val sync=ConquestOutboxSync(box,server)
            sync.sync(owner);sync.sync(owner)
            assertEquals(1,server.calls);assertEquals(1,box.pendingCount());assertEquals(1,box.blockedCount())
        }
        open().use {assertEquals(1,it.blockedCount());assertTrue(it.batch().isEmpty())}
    }

    @Test fun futureResultStaysPendingAndTerminalRejectionDropsRawProof() {
        val server=Server().apply {status=ConquestSubmitStatus.FUTURE}
        open().use {box ->
            box.enqueue(packet());val sync=ConquestOutboxSync(box,server)
            sync.sync(owner);assertEquals(1,box.pendingCount());assertNull(box.outcome(packet().eventId))
            server.status=ConquestSubmitStatus.LATE;sync.sync(owner)
            assertEquals(0,box.pendingCount());assertEquals(ConquestSubmitStatus.LATE,box.outcome(packet().eventId))
        }
    }

    @Test fun wrongBindingAndCorruptFileArePreservedAndBlockRestore() {
        open().use {it.enqueue(packet())}
        val before=file.readBytes()
        rejects {ConquestOutbox(file,binding.copy(partition=ConquestServerPartition.PLEOS_EMULATOR),ConquestOutboxLimits()).close()}
        assertArrayEquals(before,file.readBytes())
        assertFalse(ConquestAccountRestore.canRestore(File(folder.root,"conquest")))
        val bad=File(folder.root,"bad.sqlite").apply {writeText("broken")};val original=bad.readBytes()
        rejects {ConquestOutbox(bad,binding,ConquestOutboxLimits()).close()}
        assertArrayEquals(original,bad.readBytes())
    }

    @Test fun modifiedPayloadHashStopsBatchWithoutResettingData() {
        open().use {it.enqueue(packet())}
        SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE outbox SET hash=?",arrayOf("0".repeat(64)))
        }
        open().use {box ->rejects {box.batch()};assertEquals(1,box.pendingCount())}
    }

    @Test fun factoryChecksAccountBeforeCreatingDurableNamespace() {
        // A short, independent app-private fixture path also works with Windows sqlite4java.
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir()=File(folder.root,"app-files").apply {mkdirs()}
        }
        val server=Server().apply {current=null}
        val root=File(context.filesDir,"conquest/server-accounts")
        assertFalse(root.exists())
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.NO_SESSION),ConquestOutbox.openCurrent(
            context,server,owner,"u",ConquestServerPartition.PLEOS,"roads-v1"))
        assertFalse(root.exists())
        server.current=owner
        val result=ConquestOutbox.openCurrent(context,server,owner,"u",ConquestServerPartition.PLEOS,"roads-v1")
        assertTrue("Factory failed: ${server.applyFailure?.javaClass?.simpleName}: ${server.applyFailure?.message}",result is ConquestAccountResult.Success)
        (result as ConquestAccountResult.Success).value.close();assertTrue(root.exists())
        assertFalse(ConquestAccountRestore.canRestore(File(context.filesDir,"conquest")))
    }

    @Test fun cancellationPropagatesWithPendingProofIntact() {
        val server=Server().apply {beforeReply={throw CancellationException("fixture")}}
        open().use {box ->
            box.enqueue(packet())
            try {ConquestOutboxSync(box,server).sync(owner);fail("Must propagate cancellation")} catch(_:CancellationException) {}
            assertEquals(1,box.pendingCount())
        }
    }

    @Test fun cancellationAfterReplyBeforeAckPreservesPendingPayload() {
        var cancelled=false
        val server=Server().apply {beforeReply={cancelled=true}}
        open().use {box ->
            box.enqueue(packet())
            try {
                ConquestOutboxSync(box,server).sync(owner) {if(cancelled) throw CancellationException("after response")}
                fail("Must propagate cancellation before ACK")
            } catch(_:CancellationException) {}
            assertEquals(1,box.pendingCount());assertNull(box.outcome(packet().eventId))
        }
    }

    @Test fun realCapturedCompletionRestartsAndRetriesUnderFreshSameAccountGeneration() {
        val fixture=JSONArray(File("../docs/data/conquest-proof-fixtures.json").readText()).getJSONObject(0)
        val a=ConquestPoint(37.2,126.8);val b=ConquestPoint(37.2,126.802)
        val catalog=ConquestCatalog("roads-v1",listOf(DirectedRoad("ab","road","a","b",listOf(a,b)),
            DirectedRoad("ba","road","b","a",listOf(b,a))))
        val capture=ConquestServerCapture("u",catalog,0)
        val rows=fixture.getJSONArray("samples")
        val captured=(0 until rows.length()).flatMap {i ->val s=rows.getJSONObject(i)
            capture.onSample(LocationSample(s.getString("id"),"u",s.getDouble("lat"),s.getDouble("lon"),
                s.getLong("time"),s.getDouble("accuracy").toFloat(),LocationSourceType.PLEOS))
        }.single().request!!
        val server=Server()
        open().use {box ->assertTrue(ConquestOutboxSync(box,server).enqueue(owner,captured) is ConquestAccountResult.Success)}
        open().use {box ->
            val sync=ConquestOutboxSync(box,server)
            server.beforeReply={server.current=owner.copy(generation=3)}
            assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED),sync.sync(owner))
            assertEquals(captured.requestJson,box.batch().single().request.requestJson)
            server.beforeReply={}
            assertTrue(sync.sync(owner.copy(generation=3)) is ConquestAccountResult.Success)
            assertEquals(0,box.pendingCount());assertEquals(ConquestSubmitStatus.ACCEPTED,box.outcome(captured.eventId))
        }
    }
}
