package com.daehyundai.map.feature.conquest

import com.daehyundai.map.ConquestAccountAdapter
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.cloud.*
import com.daehyundai.map.feature.exploration.cloud.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class ConquestSubmissionProtocolTest {
    private val owner=ConquestAuthSession("abcdefghijklmnopqrst","11111111-1111-4111-8111-111111111111",1)
    private fun proof()=(0..3).map { LocationSample("s$it","u-install",37.2,126.8+it*.0002,
        1000L+it*2000,3f,LocationSourceType.PLEOS) }
    private fun packet():ConquestSubmission {
        val samples=proof()
        return ConquestSubmission.create(RoadVisited("s3:conquest:ab","u-install","ab",samples.last().timestampUtcMs,
            LocationSourceType.PLEOS),samples)
    }
    private fun result(request:ConquestSubmission,status:ConquestSubmitStatus=ConquestSubmitStatus.ACCEPTED)=
        JSONObject().put("eventId",request.eventId).put("roadId",request.roadId).put("occurredMs",request.occurredMs)
            .put("requestHash",request.requestHash).put("status",status.name)
            .put("acknowledge",status.acknowledge).put("retryable",status.retryable)
    private fun response(rows:JSONArray)=JSONObject().put("protocol",1).put("userId",owner.authUserId)
        .put("partition","pleos").put("catalogVersion","roads-v1").put("receivedUtcMs",9000L).put("results",rows)
    private fun parse(json:JSONObject,sent:List<ConquestSubmission> = listOf(packet()))=
        ConquestSubmitResponse.parse(json.toString(),owner,ConquestServerPartition.PLEOS,"roads-v1",sent)
    private fun rejects(action:()->Unit) {
        try {action()} catch(_:IllegalArgumentException) {return} catch(_:org.json.JSONException) {return}
        fail("Unverified submission or response must be rejected")
    }

    @Test fun immutableTextSurvivesRestoreWithoutReencodingAndHashesExactUtf8() {
        val packet=packet()
        val restored=ConquestSubmission.restore(packet.requestJson)
        assertEquals(packet.requestJson,restored.requestJson);assertEquals(packet.requestHash,restored.requestHash)
        assertNotEquals(packet.requestHash,ConquestSubmission.restore(" "+packet.requestJson).requestHash)
        assertFalse(JSONObject(packet.requestJson).has("userId"))
        assertFalse(JSONObject(packet.requestJson).has("receivedUtcMs"))
    }

    @Test fun mockAndMixedUsersCannotCreateServerPacket() {
        val samples=proof()
        val event=RoadVisited("s3:conquest:ab","u-install","ab",7000,LocationSourceType.PLEOS)
        rejects {ConquestSubmission.create(event.copy(source=LocationSourceType.MOCK),samples)}
        rejects {ConquestSubmission.create(event,samples.map {it.copy(userId="another-user")})}
        rejects {ConquestSubmission.create(event.copy(timestampUtcMs=8000),samples)}
    }

    @Test fun onlyVerifiedReturnedItemsAreEligibleForAckAndOmittedItemsStayPending() {
        val packet=packet()
        assertEquals(listOf(ConquestSubmitOutcome(packet.eventId,ConquestSubmitStatus.ACCEPTED)),
            parse(response(JSONArray().put(result(packet)))).outcomes)
        assertTrue(parse(response(JSONArray())).outcomes.isEmpty())
        for(status in listOf(ConquestSubmitStatus.FUTURE,ConquestSubmitStatus.CONFLICT,ConquestSubmitStatus.INVALID_PROOF)) {
            assertEquals(status,parse(response(JSONArray().put(result(packet,status)))).outcomes.single().status)
        }
    }

    @Test fun wrongOwnerPartitionCatalogOrProtocolRejectsEntireResponse() {
        for(change in listOf<(JSONObject)->Unit>(
            {it.put("userId","22222222-2222-4222-8222-222222222222")},
            {it.put("partition","pleos-emulator")},{it.put("catalogVersion","old")},
            {it.put("protocol",2)},{it.put("protocol",1.5)},{it.put("protocol","1")},
        )) {
            val json=response(JSONArray().put(result(packet())));change(json);rejects {parse(json)}
        }
    }

    @Test fun changedHashMetadataUnknownStatusesAndContradictoryFlagsCannotAck() {
        for(change in listOf<(JSONObject)->Unit>(
            {it.put("requestHash","0".repeat(64))},{it.put("roadId","ba")},{it.put("occurredMs",7001)},
            {it.put("eventId","not-sent")},{it.put("status","UNKNOWN")},
            {it.put("acknowledge",false)},{it.put("retryable",true)},{it.put("acknowledge","true")},
        )) {
            val row=result(packet());change(row);rejects {parse(response(JSONArray().put(row)))}
        }
        val duplicate=result(packet());rejects {parse(response(JSONArray().put(duplicate).put(duplicate)))}
    }

    @Test fun corruptStoredPacketOrOwnerAndClockFieldsCannotBeRestored() {
        val raw=JSONObject(packet().requestJson)
        rejects {ConquestSubmission.restore(raw.put("userId",owner.authUserId).toString())}
        raw.remove("userId");rejects {ConquestSubmission.restore(raw.put("receivedUtcMs",1).toString())}
        raw.remove("receivedUtcMs");rejects {ConquestSubmission.restore(raw.put("occurredMs",7000.5).toString())}
        rejects {ConquestSubmission.restore("broken")}
    }

    @Test fun teamSelectionChecksCallerAndDistinguishesLockedServerMembership() {
        val json=response(JSONArray()).put("status","SELECTED").put("teamId","red").put("selectedUtcMs",8000L)
        fun team()=ConquestTeamResponse.parse(json.toString(),owner,ConquestServerPartition.PLEOS,"roads-v1","red")
        assertEquals(ConquestTeamStatus.SELECTED,team().status)
        json.put("status","TEAM_LOCKED").put("teamId","blue")
        assertEquals("blue",team().teamId)
        json.put("status","SELECTED");rejects {team()}
        json.put("status","TEAM_LOCKED").put("userId","other-owner");rejects {team()}
    }

    @Test fun assemblyUsesAllowedRpcAndExpectedAccountWhileKeepingTokensPrivate() {
        var saved=StoredAuthSession(AccountBinding(owner.authUserId,1),10000,"fixture-access","fixture-refresh")
        var calls=0;var rpc="";var body=""
        val auth=ExistingAccountBoundary(Any(),{saved},{error("No renewal expected")},{name,json,_->
            calls++;rpc=name;body=json;"response"
        },{1000})
        val adapter=ConquestAccountAdapter(auth,owner.projectRef)
        assertTrue(adapter.submit(owner,ConquestServerPartition.PLEOS,"roads-v1",listOf(packet())) is ConquestAccountResult.Success)
        assertEquals("conquest_submit_v1",rpc)
        assertEquals(packet().requestJson,JSONObject(body).getJSONArray("p_events").getString(0))
        assertFalse(body.contains("fixture-access"))
        adapter.selectTeam(owner,ConquestServerPartition.PLEOS,"roads-v1","red")
        assertEquals("conquest_select_team_v1",rpc);assertEquals(2,calls)
        adapter.membership(owner,ConquestServerPartition.PLEOS,"roads-v1")
        assertEquals("conquest_membership_v1",rpc);assertEquals(3,calls)
        assertEquals(setOf("p_partition","p_catalog"),JSONObject(body).keys().asSequence().toSet())
        saved=StoredAuthSession(AccountBinding(owner.authUserId,3),10000,"new-access","new-refresh")
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED),
            adapter.submit(owner,ConquestServerPartition.PLEOS,"roads-v1",listOf(packet())))
        assertEquals(3,calls)
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED),
            adapter.selectTeam(owner.copy(projectRef="tsrqponmlkjihgfedcba"),ConquestServerPartition.PLEOS,"roads-v1","red"))
        assertEquals(3,calls)
        assertEquals(ConquestAccountResult.Unavailable(ConquestAccountIssue.ACCOUNT_CHANGED),
            adapter.membership(owner,ConquestServerPartition.PLEOS,"roads-v1"))
        assertEquals(3,calls)
    }
}
