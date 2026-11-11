package com.daehyundai.map.feature.conquest.domain

import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.core.model.RoadVisited

/** A roadSegmentId identifies a directed intersection-to-intersection segment. */
data class Contribution(val event: RoadVisited, val teamId: String)
enum class ContributionResult { ACCEPTED, INVALID, WRONG_SOURCE, UNKNOWN_SEGMENT, NO_TEAM,
    BEFORE_TEAM_SELECTION, DUPLICATE_EVENT, DUPLICATE_HALF_DAY, LATE, FUTURE }

/** Feature-owned state; never reads exploration records or changes shared event types. */
class ContributionLedger(
    teamIds: Set<String>,
    directedSegmentIds: Set<String>,
    val source: LocationSourceType,
) {
    private val teams = teamIds.toSet().also { require(it.isNotEmpty() && it.all(String::isNotBlank)) }
    val segments: Set<String> get() = knownSegments.toSet()
    private val knownSegments = directedSegmentIds.toSet().also {
        require(it.isNotEmpty() && it.all(String::isNotBlank))
    }
    private data class Membership(val teamId: String, val selectedUtcMs: Long)
    private data class Key(val userId: String, val segmentId: String, val halfDay: ConquestTime.HalfDay)
    private val memberships = mutableMapOf<String, Membership>()
    private val events = mutableSetOf<Pair<String, String>>()
    private val halfDays = mutableSetOf<Key>()
    private val accepted = mutableListOf<Contribution>()

    @Synchronized fun selectTeam(userId: String, teamId: String, selectedUtcMs: Long): Boolean {
        require(userId.isNotBlank() && teamId in teams && selectedUtcMs >= 0)
        if (userId in memberships) return memberships.getValue(userId).teamId == teamId
        memberships[userId] = Membership(teamId, selectedUtcMs)
        return true
    }

    @Synchronized fun teamOf(userId: String): String? = memberships[userId]?.teamId
    @Synchronized fun selectedAt(userId: String): Long? = memberships[userId]?.selectedUtcMs
    @Synchronized fun contributions(): List<Contribution> = accepted.toList()

    @Synchronized fun accept(event: RoadVisited, receivedUtcMs: Long): ContributionResult {
        require(receivedUtcMs >= 0)
        if (event.eventId.isBlank() || event.userId.isBlank() || event.roadSegmentId.isBlank() ||
            event.timestampUtcMs < 0) return ContributionResult.INVALID
        if (event.source != source) return ContributionResult.WRONG_SOURCE
        if (event.roadSegmentId !in knownSegments) return ContributionResult.UNKNOWN_SEGMENT
        val membership = memberships[event.userId] ?: return ContributionResult.NO_TEAM
        if (event.timestampUtcMs < membership.selectedUtcMs) return ContributionResult.BEFORE_TEAM_SELECTION
        val eventKey = event.userId to event.eventId
        if (eventKey in events) return ContributionResult.DUPLICATE_EVENT
        if (event.timestampUtcMs > receivedUtcMs) return ContributionResult.FUTURE
        // MVP rejects new records before the most recent noon, even while its settlement is pending.
        // Already accepted records remain available for ordered recovery of missed settlements.
        if (event.timestampUtcMs < ConquestTime.latestSettlement(receivedUtcMs)) return ContributionResult.LATE
        val key = Key(event.userId, event.roadSegmentId, ConquestTime.halfDay(event.timestampUtcMs))
        if (key in halfDays) return ContributionResult.DUPLICATE_HALF_DAY
        accepted.add(Contribution(event, membership.teamId))
        events.add(eventKey)
        halfDays.add(key)
        return ContributionResult.ACCEPTED
    }
}
