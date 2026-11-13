package com.daehyundai.map.feature.conquest

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.cloud.*
import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class ConquestServerTrackingTest {
    @get:Rule val folder = TemporaryFolder()
    private val owner = ConquestAuthSession("abcdefghijklmnopqrst", "11111111-1111-4111-8111-111111111111", 1)
    private val catalog = ConquestCatalog("fixture", listOf(
        DirectedRoad("ab", "road", "a", "b", listOf(ConquestPoint(37.2,126.8),ConquestPoint(37.2,126.802))),
        DirectedRoad("ba", "road", "b", "a", listOf(ConquestPoint(37.2,126.802),ConquestPoint(37.2,126.8))),
    ))
    private val context get() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(folder.root,"app").apply { mkdirs() }
    }
    private val binding get() = ConquestAccountBinding(owner.projectRef, owner.authUserId, "u", ConquestServerPartition.PLEOS, catalog.version)
    private val database get() = File(context.filesDir,"conquest/server-accounts/${binding.storageKey()}.sqlite")
    private fun samples(suffix: String = "", offset: Long = 0): List<LocationSample> {
        val rows = JSONArray(File("../docs/data/conquest-proof-fixtures.json").readText()).getJSONObject(0).getJSONArray("samples")
        return (0 until rows.length()).map { i -> val row = rows.getJSONObject(i)
            LocationSample(row.getString("id")+suffix, "u", row.getDouble("lat"), row.getDouble("lon"),
                row.getLong("time")+offset, row.getDouble("accuracy").toFloat(), LocationSourceType.PLEOS)
        }
    }
    private fun teamReply(expected: ConquestAuthSession = owner, selectedMs: Long = 0, team: String = "red") =
        ConquestAccountResult.Success(expected, JSONObject().put("protocol",1).put("userId",expected.authUserId)
            .put("partition","pleos").put("catalogVersion",catalog.version).put("status","SELECTED")
            .put("teamId",team).put("selectedUtcMs",selectedMs).toString())

    private class Server(private val owner: ConquestAuthSession) : ConquestServerAccess {
        @Volatile var current: ConquestAuthSession? = owner
        @Volatile var calls = 0
        @Volatile var failRequest = false
        var beforeApply: () -> Unit = {}
        var beforeReply: () -> Unit = {}
        override fun existingAccount(): ConquestAccountResult<Unit> = current?.let { ConquestAccountResult.Success(it,Unit) }
            ?: ConquestAccountResult.Unavailable(ConquestAccountIssue.NO_SESSION)
        override fun <T> withCurrentAccount(expected: ConquestAuthSession, apply: () -> T): ConquestAccountResult<T> {
            beforeApply()
            if (current == null) return ConquestAccountResult.Unavailable(ConquestAccountIssue.NO_SESSION)
            if (current != expected) return ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED)
            return try { ConquestAccountResult.Success(expected,apply()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ConquestAccountResult.Unavailable(ConquestAccountIssue.APPLY) }
        }
        override fun selectTeam(expected: ConquestAuthSession, partition: ConquestServerPartition, catalog: String, teamId: String)
            : ConquestAccountResult<String> = error("No team selection from tracking")
        override fun submit(expected: ConquestAuthSession, partition: ConquestServerPartition, catalog: String, events: List<ConquestSubmission>)
            : ConquestAccountResult<String> {
            calls++
            if (failRequest) return ConquestAccountResult.Unavailable(ConquestAccountIssue.REQUEST)
            beforeReply()
            val rows = JSONArray()
            events.forEach { rows.put(JSONObject().put("eventId",it.eventId).put("roadId",it.roadId)
                .put("occurredMs",it.occurredMs).put("requestHash",it.requestHash).put("status","ACCEPTED")
                .put("acknowledge",true).put("retryable",false)) }
            return ConquestAccountResult.Success(expected,JSONObject().put("protocol",1).put("userId",expected.authUserId)
                .put("partition",partition.wireName).put("catalogVersion",catalog).put("receivedUtcMs",999999)
                .put("results",rows).toString())
        }
    }
    private fun prepare(server: Server, upload: Boolean = false, limits: ConquestOutboxLimits = ConquestOutboxLimits(),
        maxSamples: Int = 2048, capacity: Int = 16, selectedMs: Long = 0): ConquestServerTracking {
        val result = ConquestServerTracking.prepare(context,"u",catalog,ConquestServerPartition.PLEOS,listOf("red","blue"),
            owner,teamReply(selectedMs=selectedMs),"red",server,upload,limits,capacity,60_000,maxSamples)
        return (result as ConquestAccountResult.Success).value
    }
    private suspend fun await(tracking: ConquestServerTracking, condition: (ConquestTransferState) -> Boolean) =
        withTimeout(10_000) { tracking.state.first(condition) }

    @Test fun defaultDisabledUploadPersistsActualCapturedInputAndSurvivesRestart() = runBlocking {
        val server = Server(owner)
        val tracking = prepare(server)
        try {
            samples().forEach(tracking::onSample)
            val state = await(tracking) { it.pending == 1 }
            assertFalse(state.uploadsEnabled)
            tracking.requestRetry()
        } finally { tracking.stopAndJoin() }
        assertEquals(0,server.calls)
        ConquestOutbox(database,binding,ConquestOutboxLimits()).use {
            val packet = it.batch().single().request
            assertEquals("ab",packet.roadId)
            assertEquals(samples().last().timestampUtcMs,packet.occurredMs)
            assertFalse(packet.requestJson.contains(owner.authUserId))
        }
    }

    @Test fun invalidTeamReplyOrOldGenerationDoesNotCreateDurableScope() {
        val server = Server(owner)
        val bad = ConquestServerTracking.prepare(context,"u",catalog,ConquestServerPartition.PLEOS,listOf("red","blue"),
            owner,teamReply(team="unknown"),"red",server)
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.INVALID_RESPONSE),bad)
        server.current = owner.copy(generation=2)
        val changed = ConquestServerTracking.prepare(context,"u",catalog,ConquestServerPartition.PLEOS,listOf("red","blue"),
            owner,teamReply(),"red",server)
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED),changed)
        assertFalse(database.parentFile!!.exists())
        assertEquals(0,server.calls)
    }

    @Test fun sameAccountNewGenerationStopsCollectionBeforeEnqueue() = runBlocking {
        val server = Server(owner)
        val tracking = prepare(server)
        try {
            server.current = owner.copy(generation=2)
            samples().forEach(tracking::onSample)
            val state = await(tracking) { it.issue == ConquestAccountIssue.ACCOUNT_CHANGED }
            assertFalse(state.collecting)
            assertEquals(1,state.notStored)
        } finally { tracking.stopAndJoin() }
        ConquestOutbox(database,binding,ConquestOutboxLimits()).use { assertEquals(0,it.pendingCount()) }
    }

    @Test fun fullDurableQueuePreservesOldProofAndReportsNewProofAsUnstored() = runBlocking {
        val tracking = prepare(Server(owner),limits=ConquestOutboxLimits(1,64*1024))
        try {
            samples().forEach(tracking::onSample)
            await(tracking) { it.pending == 1 }
            tracking.resetInput()
            samples("-next",60_000).forEach(tracking::onSample)
            val state = await(tracking) { it.issue == ConquestAccountIssue.OUTBOX_FULL }
            assertEquals(1,state.notStored)
            assertTrue(state.message.contains("새 제출 보류"))
        } finally { tracking.stopAndJoin() }
        ConquestOutbox(database,binding,ConquestOutboxLimits()).use {
            assertEquals(1,it.pendingCount())
            assertFalse(it.batch().single().request.eventId.contains("-next"))
        }
    }

    @Test fun oversizedProofIsNeverReportedAsDurablyPending() = runBlocking {
        val tracking = prepare(Server(owner),maxSamples=3)
        try {
            samples().forEach(tracking::onSample)
            val state = await(tracking) { it.notStored == 1 }
            assertEquals(0,state.pending)
            assertTrue(state.message.contains("기기에 저장하지 못한 근거 1건"))
        } finally { tracking.stopAndJoin() }
    }

    @Test fun explicitRetryAfterRequestFailureUsesOriginalDurablePacket() = runBlocking {
        val server = Server(owner).also { it.failRequest=true }
        val tracking = prepare(server,upload=true)
        try {
            samples().forEach(tracking::onSample)
            await(tracking) { it.issue == ConquestAccountIssue.REQUEST && it.pending == 1 }
            server.failRequest=false
            assertTrue(tracking.requestRetry())
            await(tracking) { it.pending == 0 && it.issue == null }
            assertEquals(2,server.calls)
        } finally { tracking.stopAndJoin() }
        ConquestOutbox(database,binding,ConquestOutboxLimits()).use { assertEquals(0,it.pendingCount()) }
    }

    @Test fun responseAfterAccountTransitionCannotAckStoredProof() = runBlocking {
        val server = Server(owner).also { it.beforeReply={ it.current=owner.copy(generation=2) } }
        val tracking = prepare(server,upload=true)
        try {
            samples().forEach(tracking::onSample)
            assertFalse(await(tracking) { it.issue == ConquestAccountIssue.ACCOUNT_CHANGED }.collecting)
        } finally { tracking.stopAndJoin() }
        ConquestOutbox(database,binding,ConquestOutboxLimits()).use { assertEquals(1,it.pendingCount()) }
    }

    @Test fun stalledAuthNeverBlocksSampleCallerAndBoundedHandoffReportsOverflowThenDrains() = runBlocking {
        val server = Server(owner)
        val tracking = prepare(server,capacity=1)
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor()
        try {
            server.beforeApply={ entered.countDown(); check(release.await(10,TimeUnit.SECONDS)) }
            samples().forEach(tracking::onSample)
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            executor.submit {
                tracking.resetInput()
                samples("-two",60_000).forEach(tracking::onSample)
                tracking.resetInput()
                samples("-three",120_000).forEach(tracking::onSample)
                tracking.stop()
            }.get(2,TimeUnit.SECONDS)
            assertEquals(1,tracking.state.value.notStored)
        } finally {
            release.countDown()
            tracking.stopAndJoin()
            executor.shutdownNow()
        }
        assertEquals(0,server.calls)
        ConquestOutbox(database,binding,ConquestOutboxLimits()).use { assertEquals(2,it.pendingCount()) }
    }

    @Test fun resetAndServerTeamSelectionBoundaryRequireNewEntrance() = runBlocking {
        val points = samples()
        val tracking = prepare(Server(owner),selectedMs=points[3].timestampUtcMs)
        try {
            points.forEach(tracking::onSample)
            tracking.resetInput()
            val next = samples("-next",60_000)
            next.take(3).forEach(tracking::onSample)
            tracking.resetInput()
            next.drop(3).forEach(tracking::onSample)
        } finally { tracking.stopAndJoin() }
        ConquestOutbox(database,binding,ConquestOutboxLimits()).use { assertEquals(0,it.pendingCount()) }
    }

    private fun sessionContext(): Context {
        // Native AssetManager expects an APK-style archive, not a loose JVM fixture directory.
        val archive = File(folder.root,"test-assets.apk")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for (name in listOf("namyang.segments","namyang-legacy.segments")) {
                zip.putNextEntry(ZipEntry("assets/conquest/$name"))
                File("src/main/assets/conquest/$name").inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val cookie = AssetManager::class.java.getMethod("addAssetPath",String::class.java)
            .invoke(assets,archive.absolutePath) as Int
        check(cookie != 0)
        return object : ContextWrapper(context) {
            override fun getAssets() = assets
            override fun getApplicationContext(): Context = this
        }
    }

    @Test fun actualSessionLocationEntryFeedsPreparedTrackingWithoutChangingLocalTeamOrSummary() = runBlocking {
        val sessionContext = sessionContext()
        val session = ConquestSession.open(sessionContext,"u",source=LocationSourceType.PLEOS,recordPrefix="pleos")
        session.refresh()
        val actualCatalog = session.state!!.catalog
        val raw = JSONObject(teamReply().value).put("catalogVersion",actualCatalog.version).toString()
        val server = Server(owner)
        assertTrue(session.prepareServerTracking(server,owner,ConquestAccountResult.Success(owner,raw),"team-red")
            is ConquestAccountResult.Unavailable) // Requested team and reply must agree.
        val valid = JSONObject(raw).put("teamId","team-red").toString()
        assertTrue(session.prepareServerTracking(server,owner,ConquestAccountResult.Success(owner,valid),"team-red")
            is ConquestAccountResult.Success)
        val tracking = requireNotNull(session.serverTracking)
        try {
            val fixtures = JSONArray(File("../docs/data/conquest-proof-fixtures.json").readText())
            val runtime = (0 until fixtures.length()).map(fixtures::getJSONObject).single { it.optBoolean("runtimeCatalog") }
            val rows = runtime.getJSONArray("samples")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                session.onSample(LocationSample(row.getString("id"),"u",row.getDouble("lat"),row.getDouble("lon"),
                    row.getLong("time"),row.getDouble("accuracy").toFloat(),LocationSourceType.PLEOS))
            }
            await(tracking) { it.pending == 1 }
            assertNull(session.state!!.summary.teamId)
            assertEquals(0,session.state!!.summary.contributionCount)
            assertEquals(0,server.calls)
        } finally { tracking.stopAndJoin() }
    }

    @Test fun mockSessionCannotPrepareAccountBoundCapture() = runBlocking {
        val session = ConquestSession.open(sessionContext(),"u",source=LocationSourceType.MOCK)
        val result = session.prepareServerTracking(Server(owner),owner,teamReply(),"team-red")
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.NOT_CONFIGURED),result)
        assertNull(session.serverTracking)
        assertFalse(database.parentFile!!.exists())
    }
}
