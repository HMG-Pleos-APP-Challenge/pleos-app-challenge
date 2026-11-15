package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exports actual Kotlin outcomes for independent SQL parity; synthetic game data only. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class ConquestSettlementParityTest {
    private val start=Instant.parse("2026-11-09T03:00:00Z").toEpochMilli()
    private val day=86_400_000L
    private data class Event(val user:String,val team:String,val time:Long,val road:String="ab")
    private data class Scenario(val name:String,val events:List<Event>,val days:Int=5,val started:Long?=null)
    private fun uid(label:String)=UUID.nameUUIDFromBytes(label.toByteArray(Charsets.UTF_8)).toString()

    @Test fun exportActualKotlinOwnersScoresAndReasonsAcrossNoons() {
        val cases=listOf(
            Scenario("capture-retain-takeover",listOf(Event("r","red",start+1),Event("r","red",start+day+1),
                Event("b1","blue",start+2*day+1),Event("b2","blue",start+2*day+1))),
            Scenario("initial-tie",listOf(Event("r","red",start+1),Event("b","blue",start+1))),
            Scenario("owner-tie-and-nonowner-tie",listOf(Event("r","red",start+1),Event("r","red",start+day+1),
                Event("b","blue",start+day+1),Event("b","blue",start+2*day+1),Event("g","green",start+2*day+1))),
            Scenario("36h-and-exact-noon-boundaries",listOf(Event("outside","green",start+day/2-1),
                Event("inside","red",start+day/2),Event("cutoff","blue",start+2*day))),
            Scenario("directed-roads",listOf(Event("r","red",start+1),Event("b","blue",start+1,"ba"))),
            Scenario("non-noon-start",listOf(Event("r","red",start+5*3_600_000+1)),started=start+5*3_600_000),
            Scenario("empty",emptyList()),
        )
        val output=JSONArray()
        val reasons=mutableSetOf<String>()
        for(case in cases) {
            val began=case.started ?: start
            val ledger=ContributionLedger(setOf("red","blue","green"),setOf("ab","ba"),LocationSourceType.PLEOS)
            val members=JSONArray()
            case.events.distinctBy { it.user }.forEach {
                assertTrue(ledger.selectTeam(uid(it.user),it.team,began))
                members.put(JSONObject().put("userId",uid(it.user)).put("teamId",it.team))
            }
            val events=JSONArray()
            case.events.forEachIndexed { index,event ->
                val id="${case.name}-$index"
                val visit=RoadVisited(id,uid(event.user),event.road,event.time,LocationSourceType.PLEOS)
                assertEquals(ContributionResult.ACCEPTED,ledger.accept(visit,event.time))
                events.put(JSONObject().put("userId",visit.userId).put("eventId",id).put("roadId",event.road).put("occurredMs",event.time))
            }
            val game=ConquestSettlement(ledger,began)
            val settlements=JSONArray()
            for(i in 1..case.days) {
                val cutoff=start+i*day
                game.settleThrough(cutoff)
                val roads=JSONArray()
                game.lastExplanations().toSortedMap().forEach { (id,result) ->
                    reasons.add(result.reason.name)
                    roads.put(JSONObject().put("roadId",id).put("ownerTeamId",result.ownerTeamId ?: JSONObject.NULL)
                        .put("previousOwnerTeamId",result.previousOwnerTeamId ?: JSONObject.NULL)
                        .put("scores",JSONObject(result.scores)).put("reason",result.reason.name))
                }
                settlements.put(JSONObject().put("cutoffMs",cutoff).put("roads",roads))
            }
            output.put(JSONObject().put("name",case.name).put("startedMs",began).put("members",members)
                .put("events",events).put("settlements",settlements))
        }
        assertEquals(SettlementReason.values().map { it.name }.toSet(),reasons)
        val file=File("build/conquest-proof/settlements.json")
        requireNotNull(file.parentFile).mkdirs()
        file.writeText(output.toString())
    }
}
