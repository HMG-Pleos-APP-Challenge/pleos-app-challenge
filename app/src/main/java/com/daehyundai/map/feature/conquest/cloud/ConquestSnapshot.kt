package com.daehyundai.map.feature.conquest.cloud

import com.daehyundai.map.feature.conquest.domain.*
import org.json.JSONObject

internal data class ConquestSnapshotRoad(val owner: String?, val previous: String?, val scores: Map<String,Int>, val reason: String)

/** Validated immutable server statement; no token, proof or other users' contribution records. */
internal class ConquestSnapshot private constructor(
    val raw: String, val revision: Long, val generatedMs: Long, val startedMs: Long,
    val settledMs: Long?, val nextMs: Long, val team: String?, val contributions: Int,
    val owned: Int, val updatedMs: Long?, val roads: Map<String,ConquestSnapshotRoad>,
) {
    val settlementDelayed get() = generatedMs >= nextMs

    fun requireSuccessorOf(old: ConquestSnapshot) {
        require(startedMs == old.startedMs && revision >= old.revision && generatedMs >= old.generatedMs)
        require(contributions >= old.contributions && (old.team == null || team == old.team))
        require(old.updatedMs == null || (updatedMs != null && updatedMs >= old.updatedMs))
        if (revision == old.revision) require(roads == old.roads)
        else if (revision == old.revision+1) require(roads.all { (id,r) -> r.previous == old.roads.getValue(id).owner })
    }

    companion object {
        const val MAX_BYTES = 1048576
        fun parse(raw: String, expected: ConquestAuthSession, partition: ConquestServerPartition,
            catalog: ConquestCatalog, teams: List<String>): ConquestSnapshot {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val json = JSONObject(raw)
            require(json.number("protocol") == 1L && json.string("userId") == expected.authUserId)
            require(json.string("partition") == partition.wireName && json.string("catalogVersion") == catalog.version)
            val revision = json.number("revision")
            val generated = json.number("generatedUtcMs")
            val started = json.number("startedUtcMs")
            val settled = json.optionalNumber("lastSettlementUtcMs")
            val next = json.number("nextSettlementUtcMs")
            val first = Math.addExact(ConquestTime.latestSettlement(started),86_400_000L)
            val expectedSettled = if (revision == 0L) null else Math.addExact(first,Math.multiplyExact(revision-1,86_400_000L))
            require(settled == expectedSettled && next == (settled?.let { Math.addExact(it,86_400_000L) } ?: first))
            require(generated >= started && (settled == null || generated >= settled))
            val summary = json.getJSONObject("summary")
            val team = summary.optionalTeam("teamId",teams)
            val count = summary.count("contributionCount")
            val owned = summary.count("ownedRoadCount")
            val updated = summary.optionalNumber("lastUpdatedUtcMs")
            require(updated == null || updated <= generated)
            require(if (team == null) count == 0 && owned == 0 && updated == null
                else updated != null && (settled == null || updated >= settled))
            val rows = json.getJSONArray("roads")
            require(rows.length() == catalog.roads.size)
            val known = catalog.roads.map { it.id }.toSet()
            val roads = linkedMapOf<String,ConquestSnapshotRoad>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val id = row.string("roadId")
                require(id in known && id !in roads)
                val owner = row.optionalTeam("ownerTeamId",teams)
                val previous = row.optionalTeam("previousOwnerTeamId",teams)
                val points = row.getJSONObject("scores")
                val scores = points.keys().asSequence().associateWith { key ->
                    require(key in teams); points.count(key).also { require(it > 0) }
                }
                val reason = row.string("reason")
                if (revision == 0L) require(owner == null && previous == null && scores.isEmpty() && reason == "INITIAL")
                else {
                    if (revision == 1L) require(previous == null)
                    val high = scores.values.maxOrNull()
                    val leaders = scores.filterValues { it == high }.keys
                    val wanted = when {
                        leaders.size == 1 -> leaders.first()
                        previous in leaders -> previous
                        else -> null
                    }
                    require(owner == wanted)
                    val why = when {
                        leaders.isEmpty() -> SettlementReason.NO_CONTRIBUTIONS
                        leaders.size > 1 -> if (owner == null) SettlementReason.TIE_NEUTRAL else SettlementReason.TIE_RETAINED
                        previous == null -> SettlementReason.CAPTURED
                        owner == previous -> SettlementReason.RETAINED
                        else -> SettlementReason.TAKEN_OVER
                    }
                    require(reason == why.name)
                }
                roads[id] = ConquestSnapshotRoad(owner,previous,scores.toMap(),reason)
            }
            require(owned == if (team == null) 0 else roads.values.count { it.owner == team })
            return ConquestSnapshot(raw,revision,generated,started,settled,next,team,count,owned,updated,roads.toMap())
        }
    }
}

private fun JSONObject.string(key: String) = get(key).also { require(it is String) } as String
private fun JSONObject.number(key: String): Long {
    val value = get(key)
    require(value is Number && value.toString().matches(Regex("0|[1-9][0-9]*")))
    return value.toString().toLong()
}
private fun JSONObject.count(key: String) = number(key).also { require(it <= Int.MAX_VALUE) }.toInt()
private fun JSONObject.optionalNumber(key: String): Long? { require(has(key)); return if (isNull(key)) null else number(key) }
private fun JSONObject.optionalTeam(key: String, teams: List<String>): String? {
    require(has(key)); return if (isNull(key)) null else string(key).also { require(it in teams) }
}
