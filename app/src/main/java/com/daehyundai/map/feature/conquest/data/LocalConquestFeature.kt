package com.daehyundai.map.feature.conquest.data

import com.daehyundai.map.core.contract.ConquestFeature
import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.domain.*

/** Existing contract adapter. Construction and calls must run off the UI thread (file I/O).
 * A single application-owned instance per file is required. No SDK subscription or exploration access.
 */
class LocalConquestFeature(
    teamIds: Set<String>, directedSegmentIds: Set<String>,
    private val datasetVersion: String,
    private val source: LocationSourceType,
    private val store: ConquestStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : ConquestFeature {
    private val teams = teamIds.toSet()
    private val segments = directedSegmentIds.toSet()
    private var record: ConquestRecord
    private var ledger: ContributionLedger
    private var settlement: ConquestSettlement

    init {
        require(datasetVersion.isNotBlank())
        val now = clock().also { require(it >= 0) }
        record = store.read() ?: ConquestRecord(datasetVersion, source, now, now)
        val restored = restore(record)
        ledger = restored.first
        settlement = restored.second
    }

    private fun restore(value: ConquestRecord): Pair<ContributionLedger, ConquestSettlement> {
        require(value.datasetVersion == datasetVersion && value.source == source) { "Conquest dataset/source mismatch" }
        require(value.startedUtcMs >= 0 && value.observedUtcMs >= value.startedUtcMs)
        require(value.teams.map { it.userId }.distinct().size == value.teams.size)
        val ledger = ContributionLedger(teams, segments, source)
        value.teams.forEach {
            require(it.selectedUtcMs in value.startedUtcMs..value.observedUtcMs)
            check(ledger.selectTeam(it.userId, it.teamId, it.selectedUtcMs))
        }
        value.events.forEach {
            require(it.timestampUtcMs in value.startedUtcMs..value.observedUtcMs)
            require(ledger.accept(it, it.timestampUtcMs) == ContributionResult.ACCEPTED) { "Invalid saved contribution" }
        }
        val settlement = ConquestSettlement(ledger, value.startedUtcMs)
        settlement.settleThrough(value.observedUtcMs)
        return ledger to settlement
    }

    private fun now(): Long = clock().also { require(it >= record.observedUtcMs) { "Game time moved backwards" } }

    private fun save(next: ConquestRecord) {
        // Validate candidate and persist before publishing; failed writes keep the previous in-memory state.
        val restored = restore(next)
        store.write(next)
        record = next
        ledger = restored.first
        settlement = restored.second
    }

    @Synchronized fun selectTeam(userId: String, teamId: String): Boolean {
        require(userId.isNotBlank() && teamId in teams)
        val existing = ledger.teamOf(userId)
        if (existing != null) return existing == teamId
        val now = now()
        save(record.copy(observedUtcMs = now, teams = record.teams + TeamSelection(userId, teamId, now)))
        return true
    }

    @Synchronized fun teamOf(userId: String): String? = ledger.teamOf(userId)

    @Synchronized fun accept(event: RoadVisited): ContributionResult {
        val now = now()
        val candidate = restore(record).first
        val result = candidate.accept(event, now)
        if (result == ContributionResult.ACCEPTED) {
            save(record.copy(observedUtcMs = now, events = record.events + event))
        }
        return result
    }

    override suspend fun onRoadVisited(event: RoadVisited) { accept(event) }

    override suspend fun getSummary(userId: String): ConquestSummary = readSummary(userId)

    @Synchronized fun readSummary(userId: String): ConquestSummary {
        advance()
        return settlement.summary(userId)
    }

    @Synchronized fun owners(): Map<String, String?> {
        advance()
        return settlement.currentOwners()
    }

    /** Display accepted contributions without changing shared contracts or storage. */
    @Synchronized fun roadDetails(userId: String): Map<String, ConquestRoadDetail> {
        require(userId.isNotBlank())
        advance()
        val completed = ConquestTime.latestSettlement(record.observedUtcMs)
            .takeIf { it > ConquestTime.latestSettlement(record.startedUtcMs) }
        val owners = settlement.currentOwners()
        return segments.associateWith { segment ->
            ConquestRoadDetail(owners[segment], completed,
                record.events.count { it.userId == userId && it.roadSegmentId == segment })
        }
    }

    private fun advance() {
        val now = now()
        if (ConquestTime.latestSettlement(now) > ConquestTime.latestSettlement(record.observedUtcMs)) {
            save(record.copy(observedUtcMs = now))
        }
    }
}

data class ConquestRoadDetail(val ownerTeamId: String?, val settledUtcMs: Long?, val contributionCount: Int)
