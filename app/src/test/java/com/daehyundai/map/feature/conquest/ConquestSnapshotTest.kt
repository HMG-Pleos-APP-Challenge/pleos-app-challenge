package com.daehyundai.map.feature.conquest

import com.daehyundai.map.feature.conquest.cloud.*
import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class ConquestSnapshotTest {
    @get:Rule val folder=TemporaryFolder()
    private val owner=ConquestAuthSession("abcdefghijklmnopqrst","11111111-1111-4111-8111-111111111111",1)
    private val binding=ConquestAccountBinding(owner.projectRef,owner.authUserId,"u",ConquestServerPartition.PLEOS,"settlement-fixture")
    private val catalog=ConquestCatalog(binding.catalogVersion,listOf(
        DirectedRoad("ab","road","a","b",listOf(ConquestPoint(37.2,126.8),ConquestPoint(37.2,126.802))),
        DirectedRoad("ba","road","b","a",listOf(ConquestPoint(37.2,126.802),ConquestPoint(37.2,126.8))),
    ))
    private val teams=listOf("red","blue","green")
    private val start=Instant.parse("2026-11-09T03:00:00Z").toEpochMilli()
    private val day=86400000L
    private val file get()=File(folder.root,"conquest/server-accounts/${binding.storageKey()}.snapshot")
    private fun cache(scope:ConquestAccountBinding=binding,hook:()->Unit={})=ConquestSnapshotCache(file,scope,catalog,teams,hook)
    private fun json(revision:Long=1,count:Int=2):JSONObject {
        val cutoff=if(revision==0L) JSONObject.NULL else start+day*revision
        val rows=JSONArray().put(JSONObject().put("roadId","ab").put("ownerTeamId",if(revision==0L) JSONObject.NULL else "red")
            .put("previousOwnerTeamId",if(revision<=1L) JSONObject.NULL else "red")
            .put("scores",if(revision==0L) JSONObject() else JSONObject().put("red",1))
            .put("reason",if(revision==0L) "INITIAL" else if(revision==1L) "CAPTURED" else "RETAINED"))
            .put(JSONObject().put("roadId","ba").put("ownerTeamId",JSONObject.NULL).put("previousOwnerTeamId",JSONObject.NULL)
                .put("scores",JSONObject()).put("reason",if(revision==0L) "INITIAL" else "NO_CONTRIBUTIONS"))
        return JSONObject().put("protocol",1).put("userId",owner.authUserId).put("partition","pleos").put("catalogVersion",catalog.version)
            .put("revision",revision).put("startedUtcMs",start).put("generatedUtcMs",start+day*revision+1000)
            .put("lastSettlementUtcMs",cutoff).put("nextSettlementUtcMs",start+day*(revision+1))
            .put("summary",JSONObject().put("teamId","red").put("contributionCount",count)
                .put("ownedRoadCount",if(revision==0L) 0 else 1).put("lastUpdatedUtcMs",start+day*revision))
            .put("roads",rows)
    }
    private fun parse(json:JSONObject)=ConquestSnapshot.parse(json.toString(),owner,binding.partition,catalog,teams)
    private fun rejects(action:()->Unit) { try {action()} catch(_:Exception) {return}; fail("Expected rejected snapshot") }

    private inner class Server:ConquestServerAccess {
        var current:ConquestAuthSession?=owner
        var reply=json().toString()
        var replySession=owner
        var beforeReply:()->Unit={}
        override fun existingAccount():ConquestAccountResult<Unit> = current?.let {ConquestAccountResult.Success(it,Unit)}
            ?: ConquestAccountResult.Unavailable(ConquestAccountIssue.NO_SESSION)
        override fun <T> withCurrentAccount(expected:ConquestAuthSession,apply:()->T):ConquestAccountResult<T> {
            if(current==null) return ConquestAccountResult.Unavailable(ConquestAccountIssue.NO_SESSION)
            if(current!=expected) return ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED)
            return try {ConquestAccountResult.Success(expected,apply())} catch(cancelled:CancellationException) {throw cancelled}
                catch(_:Exception) {ConquestAccountResult.Unavailable(ConquestAccountIssue.APPLY)}
        }
        override fun snapshot(expected:ConquestAuthSession,partition:ConquestServerPartition,catalog:String):ConquestAccountResult<String> {
            beforeReply();return ConquestAccountResult.Success(replySession,reply)
        }
        override fun membership(expected:ConquestAuthSession,partition:ConquestServerPartition,catalog:String):ConquestAccountResult<String> = error("No membership")
        override fun selectTeam(expected:ConquestAuthSession,partition:ConquestServerPartition,catalog:String,teamId:String):ConquestAccountResult<String> = error("No selection")
        override fun submit(expected:ConquestAuthSession,partition:ConquestServerPartition,catalog:String,events:List<ConquestSubmission>):ConquestAccountResult<String> = error("No submission")
    }
    private fun sync(server:Server,store:ConquestSnapshotCache=cache())=
        ConquestSnapshotSync(store,server,owner,binding.partition,catalog,teams,"red")

    @Test fun validatesInitialAndCompletedSnapshotsAndDetectsSettlementDelay() {
        assertNull(parse(json(0)).settledMs)
        assertEquals(1L,parse(json()).revision)
        assertFalse(parse(json()).settlementDelayed)
        assertTrue(parse(json().put("generatedUtcMs",start+2*day)).settlementDelayed)
    }

    @Test fun wrongIdentityScopeTypesAndInconsistentNoonMetadataAreRejected() {
        val changes=listOf<(JSONObject)->Unit>(
            {it.put("userId","other")},{it.put("partition","pleos-emulator")},{it.put("catalogVersion","other")},
            {it.put("protocol","1")},{it.put("revision",-1)},{it.put("revision",1.5)},
            {it.put("lastSettlementUtcMs",JSONObject.NULL)},{it.put("nextSettlementUtcMs",start+2*day+1)},
            {it.put("generatedUtcMs",start)},{it.remove("lastSettlementUtcMs")},
            {it.getJSONObject("summary").put("ownedRoadCount",2)},
            {it.getJSONObject("summary").put("teamId",JSONObject.NULL)},
            {it.getJSONObject("summary").put("contributionCount",Int.MAX_VALUE.toLong()+1)},
        )
        changes.forEach {change -> val value=json();change(value);rejects {parse(value)}}
    }

    @Test fun missingDuplicateUnknownRoadAndContradictoryScoresOrReasonsRejectEntirePayload() {
        val changes=listOf<(JSONObject)->Unit>(
            {it.getJSONArray("roads").remove(1)},
            {val rows=it.getJSONArray("roads");rows.put(1,rows.getJSONObject(0))},
            {it.getJSONArray("roads").getJSONObject(0).put("roadId","unknown")},
            {it.getJSONArray("roads").getJSONObject(0).put("ownerTeamId","blue")},
            {it.getJSONArray("roads").getJSONObject(0).put("reason","TIE_NEUTRAL")},
            {it.getJSONArray("roads").getJSONObject(0).getJSONObject("scores").put("red",0)},
            {it.getJSONArray("roads").getJSONObject(0).getJSONObject("scores").put("unknown",2)},
        )
        changes.forEach {change ->val value=json();change(value);rejects {parse(value)}}
    }

    @Test fun atomicCacheRestartsAndAcceptsSameRevisionContributionGrowth() {
        cache().save(parse(json()))
        assertEquals(2,cache().read()!!.contributions)
        cache().save(parse(json(count=3).put("generatedUtcMs",start+day+2000)))
        assertEquals(3,cache().read()!!.contributions)
        cache().save(parse(json(2,count=4)))
        assertEquals(2L,cache().read()!!.revision)
    }

    @Test fun revisionClockCountsAndSameRevisionOwnerScoreChangesPreservePreviousBytes() {
        cache().save(parse(json(2,count=4)))
        val original=file.readBytes()
        for(value in listOf(json(1,count=4),json(2,count=3),json(2,count=4).put("generatedUtcMs",start+2*day+500),
            json(2,count=4).put("startedUtcMs",start+1),json(3,count=5).put("generatedUtcMs",start+2*day-1),
            json(2,count=4).also {it.getJSONArray("roads").getJSONObject(0).getJSONObject("scores").put("red",2)})) {
            rejects {cache().save(parse(value))};assertArrayEquals(original,file.readBytes())
        }
    }

    @Test fun corruptionForeignBindingAndInterruptedReplaceDoNotResetCache() {
        cache().save(parse(json()));val original=file.readBytes()
        rejects {cache(binding.copy(authUserId="22222222-2222-4222-8222-222222222222")).read()}
        rejects {cache(hook={throw IOException("fixture")}).save(parse(json(2)))}
        assertArrayEquals(original,file.readBytes())
        file.writeBytes(original.copyOf(original.size-1))
        val damaged=file.readBytes();rejects {cache().read()};rejects {cache().save(parse(json(2)))}
        assertArrayEquals(damaged,file.readBytes())
    }

    @Test fun changedEpochNoSessionAndWrongReplyBindingCannotCreateOrOverwriteCache() {
        cache().save(parse(json()));val original=file.readBytes()
        for(next in listOf(owner.copy(generation=2),null)) {
            val server=Server().also {it.beforeReply={it.current=next}}
            assertTrue(sync(server).refresh() is ConquestAccountResult.Unavailable)
            assertArrayEquals(original,file.readBytes())
        }
        val server=Server().also {it.replySession=owner.copy(generation=2)}
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED),sync(server).refresh())
    }

    @Test fun malformedResponseAndStaleRevisionLeaveCurrentCachedStateIntact() {
        cache().save(parse(json(2,count=4)));val original=file.readBytes()
        val server=Server()
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.STALE_SNAPSHOT),sync(server).refresh())
        server.reply="broken"
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.INVALID_RESPONSE),sync(server).refresh())
        assertArrayEquals(original,file.readBytes())
    }

    @Test fun canceledResponseBeforeAtomicSaveNeverCreatesDurableNamespace() {
        val server=Server();var probes=0
        try {sync(server).refresh { if(++probes==3) throw CancellationException("fixture") };fail("Cancellation must propagate")}
        catch(_:CancellationException) { }
        assertFalse(file.parentFile!!.exists())
    }

    @Test fun cachedReadAlsoRequiresCurrentAccountAndCorruptSaveFailureIsExplicit() {
        cache().save(parse(json()))
        val server=Server().also {it.current=owner.copy(generation=2)}
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED),sync(server).cached())
        server.current=owner;file.writeText("damaged")
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.APPLY),sync(server).refresh())
        assertEquals("damaged",file.readText())
    }

    @Test fun actualSqlSnapshotsIncludingFullRuntimeCatalogAreAccepted() {
        val values=JSONArray(File("../docs/data/conquest-server-snapshots.json").readText())
        assertEquals(3,values.length())
        for(i in 0 until values.length()) {
            val row=values.getJSONObject(i)
            val actual=if(row.getString("kind")=="full") File("src/main/assets/conquest/namyang.segments").reader().use(ConquestCatalogReader::read) else catalog
            val parsed=ConquestSnapshot.parse(row.getJSONObject("snapshot").toString(),owner,binding.partition,actual,teams)
            assertEquals(actual.roads.size,parsed.roads.size)
            if(row.getString("kind")=="full") assertEquals(1453,parsed.roads.size)
        }
    }
}
