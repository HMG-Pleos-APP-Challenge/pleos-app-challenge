package com.daehyundai.map.feature.conquest.cloud

import com.daehyundai.map.core.model.*
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Preserve this exact string on disk/retry. Never reconstruct a queued event after sending it. */
internal class ConquestSubmission private constructor(
    val requestJson: String, val eventId: String, val roadId: String, val occurredMs: Long,
) {
    val requestHash: String = MessageDigest.getInstance("SHA-256").digest(requestJson.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        fun create(event: RoadVisited, proof: List<LocationSample>): ConquestSubmission {
            require(event.source == LocationSourceType.PLEOS && proof.isNotEmpty())
            require(proof.all { it.userId == event.userId && it.source == event.source })
            require(proof.last().timestampUtcMs == event.timestampUtcMs)
            require(event.eventId == "${proof.last().eventId}:conquest:${event.roadSegmentId}")
            val samples = JSONArray()
            proof.forEach { sample -> samples.put(JSONObject().put("id",sample.eventId).put("time",sample.timestampUtcMs)
                .put("lat",sample.latitude).put("lon",sample.longitude)
                .put("accuracy",sample.accuracyMeters ?: JSONObject.NULL)) }
            return restore(JSONObject().put("eventId",event.eventId).put("roadId",event.roadSegmentId)
                .put("occurredMs",event.timestampUtcMs).put("samples",samples).toString())
        }

        fun restore(raw: String): ConquestSubmission {
            require(raw.toByteArray(Charsets.UTF_8).size <= 262144)
            val json = JSONObject(raw)
            require(json.keys().asSequence().toSet() == setOf("eventId","roadId","occurredMs","samples"))
            val id = json.strictString("eventId").also { require(it.isNotBlank() && it.length <= 200) }
            val road = json.strictString("roadId").also { require(it.isNotBlank()) }
            val time = json.strictLong("occurredMs")
            require(json.getJSONArray("samples").length() in 3..2048)
            return ConquestSubmission(raw,id,road,time)
        }
    }
}

internal enum class ConquestSubmitStatus {
    ACCEPTED, DUPLICATE_HALF_DAY, LATE, NO_TEAM, BEFORE_TEAM_SELECTION, UNKNOWN_ROAD, WRONG_CATALOG,
    INVALID_PROOF, FUTURE, CONFLICT;
    val acknowledge get() = this != FUTURE && this != CONFLICT
    val retryable get() = this == FUTURE
}
internal data class ConquestSubmitOutcome(val eventId: String, val status: ConquestSubmitStatus)
internal enum class ConquestTeamStatus { SELECTED, UNCHANGED, TEAM_LOCKED }
internal data class ConquestMembershipResponse(val team: ConquestTeamResponse?) {
    companion object {
        fun parse(raw: String, expected: ConquestAuthSession, partition: ConquestServerPartition,
            catalog: String): ConquestMembershipResponse {
            require(raw.toByteArray(Charsets.UTF_8).size <= 16384)
            val json = JSONObject(raw)
            require(json.strictLong("protocol") == 1L && json.strictString("userId") == expected.authUserId)
            require(json.strictString("partition") == partition.wireName && json.strictString("catalogVersion") == catalog)
            require(json.has("teamId") && json.has("selectedUtcMs"))
            return when (json.strictString("status")) {
                "NO_TEAM" -> { require(json.isNull("teamId") && json.isNull("selectedUtcMs")); ConquestMembershipResponse(null) }
                "MEMBER" -> ConquestMembershipResponse(ConquestTeamResponse(ConquestTeamStatus.UNCHANGED,
                    json.strictString("teamId").also { require(it.isNotBlank()) }, json.strictLong("selectedUtcMs")))
                else -> error("Invalid conquest membership")
            }
        }
    }
}

internal data class ConquestTeamResponse(val status: ConquestTeamStatus, val teamId: String, val selectedUtcMs: Long) {
    companion object {
        fun parse(raw: String, expected: ConquestAuthSession, partition: ConquestServerPartition,
            catalog: String, requestedTeam: String): ConquestTeamResponse {
            require(raw.toByteArray(Charsets.UTF_8).size <= 16384)
            val json = JSONObject(raw)
            require(json.strictLong("protocol") == 1L)
            require(json.strictString("userId") == expected.authUserId)
            require(json.strictString("partition") == partition.wireName)
            require(json.strictString("catalogVersion") == catalog)
            val status = ConquestTeamStatus.valueOf(json.strictString("status"))
            val team = json.strictString("teamId").also { require(it.isNotBlank()) }
            require((status == ConquestTeamStatus.TEAM_LOCKED) == (team != requestedTeam))
            return ConquestTeamResponse(status,team,json.strictLong("selectedUtcMs"))
        }
    }
}
internal data class ConquestSubmitResponse(val receivedUtcMs: Long, val outcomes: List<ConquestSubmitOutcome>) {
    companion object {
        /** Validate the entire response before any ACK. Omitted events remain pending. */
        fun parse(raw: String, expected: ConquestAuthSession, partition: ConquestServerPartition,
            catalog: String, sent: List<ConquestSubmission>): ConquestSubmitResponse {
            require(raw.toByteArray(Charsets.UTF_8).size <= 1048576)
            val json = JSONObject(raw)
            require(json.strictLong("protocol") == 1L)
            require(json.strictString("userId") == expected.authUserId)
            require(json.strictString("partition") == partition.wireName)
            require(json.strictString("catalogVersion") == catalog)
            val byId = sent.associateBy { it.eventId }
            require(byId.size == sent.size)
            val rows = json.getJSONArray("results")
            require(rows.length() <= sent.size)
            val seen = mutableSetOf<String>()
            val outcomes = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val id = row.strictString("eventId")
                require(seen.add(id))
                val request = requireNotNull(byId[id])
                require(row.strictString("requestHash") == request.requestHash)
                require(row.strictString("roadId") == request.roadId)
                require(row.strictLong("occurredMs") == request.occurredMs)
                val status = ConquestSubmitStatus.valueOf(row.strictString("status"))
                require(row.get("acknowledge") is Boolean && row.getBoolean("acknowledge") == status.acknowledge)
                require(row.get("retryable") is Boolean && row.getBoolean("retryable") == status.retryable)
                ConquestSubmitOutcome(id,status)
            }
            return ConquestSubmitResponse(json.strictLong("receivedUtcMs"),outcomes)
        }
    }
}

private fun JSONObject.strictString(key: String): String = get(key).also { require(it is String) } as String
private fun JSONObject.strictLong(key: String): Long {
    val value = get(key)
    require(value is Number && value.toString().matches(Regex("0|[1-9][0-9]*")))
    return value.toString().toLong()
}

/** Blocking, operation-specific port. No arbitrary RPC name or token reaches conquest. */
internal interface ConquestServerAccess : ConquestAccountAccess {
    fun membership(expected: ConquestAuthSession, partition: ConquestServerPartition,
        catalog: String): ConquestAccountResult<String>
    fun selectTeam(expected: ConquestAuthSession, partition: ConquestServerPartition,
        catalog: String, teamId: String): ConquestAccountResult<String>
    fun submit(expected: ConquestAuthSession, partition: ConquestServerPartition,
        catalog: String, events: List<ConquestSubmission>): ConquestAccountResult<String>
}
