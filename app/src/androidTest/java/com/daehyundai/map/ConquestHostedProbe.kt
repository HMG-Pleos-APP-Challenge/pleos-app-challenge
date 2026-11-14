package com.daehyundai.map

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.cloud.*
import com.daehyundai.map.feature.conquest.data.ConquestAccountRestore
import com.daehyundai.map.feature.conquest.domain.ConquestCatalogReader
import com.daehyundai.map.feature.exploration.cloud.SupabaseCloud
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Explicit isolated HTTPS test only. Synthetic input; never injects device GPS or enables app rollout. */
class ConquestHostedProbe : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments=arguments ?: Bundle(); start() }
    private fun <T> value(result: ConquestAccountResult<T>): T {
        check(result is ConquestAccountResult.Success) { "Account operation: ${(result as ConquestAccountResult.Unavailable).issue}" }
        return result.value
    }

    override fun onStart() {
        try {
            check(BuildConfig.DEBUG && BuildConfig.SUPABASE_URL == "https://hjqyiwqgukequtlrxgee.supabase.co") {
                "Only the isolated test project's Debug APK is allowed"
            }
            val phase=requireNotNull(arguments.getString("phase"))
            check(phase in setOf("prepare","queue","lost-reply","sync","inspect"))
            val catalog=targetContext.assets.open("conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
            val teams=listOf("team-red","team-blue")
            val partition=ConquestServerPartition.PLEOS_EMULATOR
            val access=conquestAccountAccess(targetContext)
            val preferences=targetContext.getSharedPreferences("conquest-hosted-probe",0)
            val installation=preferences.getString("installation",null) ?: "probe-${UUID.randomUUID()}".also {
                check(preferences.edit().putString("installation",it).commit())
            }
            if(phase=="prepare") {
                // Signup is explicit in this test step, through the existing authentication owner.
                // No tokens are read, copied, returned to conquest, or printed.
                SupabaseCloud(targetContext).account()
            }
            val current=access.existingAccount()
            check(current is ConquestAccountResult.Success) { "Existing account unavailable" }
            val expected=current.session
            if(phase=="prepare") {
                val team=requireNotNull(arguments.getString("team"));check(team in teams)
                val response=ConquestTeamResponse.parse(value(access.selectTeam(expected,partition,catalog.version,team)),
                    expected,partition,catalog.version,team)
                check(response.teamId==team)
            }
            val membership=ConquestMembershipResponse.parse(value(access.membership(expected,partition,catalog.version)),
                expected,partition,catalog.version)
            val team=requireNotNull(membership.team)
            val outbox=value(ConquestOutbox.openCurrent(targetContext,access,expected,installation,partition,catalog.version))
            val binding=outbox.binding
            val cache=ConquestSnapshotCache(File(targetContext.filesDir,"conquest/server-accounts/${binding.storageKey()}.snapshot"),
                binding,catalog,teams)
            val snapshots=ConquestSnapshotSync(cache,access,expected,partition,catalog,teams,team.teamId)
            var note=""
            outbox.use {
                val sync=ConquestOutboxSync(outbox,access)
                when(phase) {
                    "queue" -> {
                        check(outbox.pendingCount()==0) { "Existing pending work must be preserved" }
                        val before=value(snapshots.refresh())
                        val fixture=context.assets.open("conquest/live-road-proof.json").reader().use { JSONObject(it.readText()) }
                        val rows=fixture.getJSONArray("samples")
                        val nonce=UUID.randomUUID().toString()
                        val first=rows.getJSONObject(0).getLong("time")
                        val base=maxOf(before.generatedMs+100,team.selectedUtcMs+100)
                        val samples=(0 until rows.length()).map { i -> val row=rows.getJSONObject(i)
                            LocationSample("android-$nonce-$i",installation,row.getDouble("lat"),row.getDouble("lon"),
                                base+row.getLong("time")-first,row.getDouble("accuracy").toFloat(),LocationSourceType.PLEOS)
                        }
                        check(samples.last().timestampUtcMs-base in 1..45_000)
                        val capture=ConquestServerCapture(installation,catalog,team.selectedUtcMs)
                        val completed=samples.flatMap(capture::onSample)
                        val packet=requireNotNull(completed.single().request)
                        check(packet.roadId==fixture.getJSONArray("expected").getString(0))
                        check(value(sync.enqueue(expected,packet)))
                        check(preferences.edit().putString("event",packet.eventId).putLong("baseline",before.contributions.toLong()).commit())
                        val offline=object:ConquestServerAccess by access {
                            override fun submit(expected:ConquestAuthSession,partition:ConquestServerPartition,
                                catalog:String,events:List<ConquestSubmission>):ConquestAccountResult<String> =
                                ConquestAccountResult.Unavailable(ConquestAccountIssue.REQUEST)
                        }
                        check(ConquestOutboxSync(outbox,offline).sync(expected) is ConquestAccountResult.Unavailable)
                        check(outbox.batch().single().request.requestJson==packet.requestJson)
                        // Wait until this short, server-clock-based synthetic drive has completed.
                        Thread.sleep(samples.last().timestampUtcMs-base+1500)
                        note="synthetic full road captured; offline failure retained exact durable packet"
                    }
                    "lost-reply" -> {
                        check(outbox.pendingCount()==1)
                        var committed=false
                        val lost=object:ConquestServerAccess by access {
                            override fun submit(expected:ConquestAuthSession,partition:ConquestServerPartition,
                                catalog:String,events:List<ConquestSubmission>):ConquestAccountResult<String> {
                                val response=access.submit(expected,partition,catalog,events)
                                val parsed=ConquestSubmitResponse.parse(value(response),expected,partition,catalog,events)
                                check(parsed.outcomes.single().status in setOf(ConquestSubmitStatus.ACCEPTED,ConquestSubmitStatus.DUPLICATE_HALF_DAY))
                                committed=true
                                return ConquestAccountResult.Unavailable(ConquestAccountIssue.REQUEST)
                            }
                        }
                        check(ConquestOutboxSync(outbox,lost).sync(expected) is ConquestAccountResult.Unavailable)
                        check(committed && outbox.pendingCount()==1)
                        note="real server committed; simulated lost reply preserved pending row"
                    }
                    "sync" -> {
                        val event=requireNotNull(preferences.getString("event",null))
                        check(value(sync.sync(expected)).pending==0)
                        check(outbox.outcome(event) in setOf(ConquestSubmitStatus.ACCEPTED,ConquestSubmitStatus.DUPLICATE_HALF_DAY))
                        check(value(sync.sync(expected)).returned==0)
                        check(value(snapshots.refresh()).contributions>=1)
                        note="reopened SQLite; real HTTPS retry ACK and result atomic; empty retry no submission"
                    }
                    "prepare", "inspect" -> note="existing encrypted session and membership read"
                }
                var applied=false
                val stale=access.withCurrentAccount(expected.copy(generation=expected.generation+1)) { applied=true }
                check(stale==ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED) && !applied)
                check(!ConquestAccountRestore.canRestore(File(targetContext.filesDir,"conquest")))
                val snapshot=value(snapshots.refresh())
                val cached=value(snapshots.cached())
                check(cached?.raw==snapshot.raw && snapshot.roads.size==1453)
                val result=JSONObject().put("phase",phase).put("authUserId",expected.authUserId).put("generation",expected.generation)
                    .put("team",team.teamId).put("pending",outbox.pendingCount()).put("blocked",outbox.blockedCount())
                    .put("contributions",snapshot.contributions).put("revision",snapshot.revision)
                    .put("roads",snapshot.roads.size).put("note",note)
                finish(Activity.RESULT_OK,Bundle().apply { putString("result","PASS $result") })
            }
        } catch(failure:Exception) {
            // Do not print exception messages: a network/library error could contain credentials.
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("result","FAIL ${failure.javaClass.simpleName}") })
        }
    }
}
