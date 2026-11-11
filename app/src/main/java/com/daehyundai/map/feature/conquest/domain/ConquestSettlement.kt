package com.daehyundai.map.feature.conquest.domain

import com.daehyundai.map.core.model.ConquestSummary

data class SettlementResult(val timestampUtcMs: Long, val owners: Map<String, String?>)

/** Runs missed noons in order: a tie depends on the owner from the preceding settlement. */
class ConquestSettlement(private val ledger: ContributionLedger, startedUtcMs: Long) {
    private var lastSettlement = ConquestTime.latestSettlement(startedUtcMs)
    private var owners: Map<String, String?> = ledger.segments.associateWith { null }
    private var observedUtcMs = startedUtcMs

    @Synchronized fun settleThrough(nowUtcMs: Long): List<SettlementResult> {
        require(nowUtcMs >= observedUtcMs) { "Game time must not move backwards" }
        val contributions = ledger.contributions()
        val results = mutableListOf<SettlementResult>()
        var next = ConquestTime.nextSettlement(lastSettlement)
        while (next <= nowUtcMs) {
            val scores = contributions.filter { ConquestTime.inWindow(it.event.timestampUtcMs, next) }
                .groupBy { it.event.roadSegmentId }
            owners = ledger.segments.associateWith { segment ->
                val counts = scores[segment].orEmpty().groupingBy { it.teamId }.eachCount()
                val high = counts.values.maxOrNull()
                val leaders = counts.filterValues { it == high }.keys
                when {
                    leaders.isEmpty() -> null
                    leaders.size == 1 -> leaders.first()
                    owners[segment] in leaders -> owners[segment]
                    else -> null
                }
            }
            lastSettlement = next
            results.add(SettlementResult(next, owners.toMap()))
            next = ConquestTime.nextSettlement(next)
        }
        observedUtcMs = nowUtcMs
        return results
    }

    @Synchronized fun currentOwners(): Map<String, String?> = owners.toMap()

    /** Summary reads do not run settlements or mutate another feature's state. */
    @Synchronized fun summary(userId: String): ConquestSummary {
        require(userId.isNotBlank())
        val team = ledger.teamOf(userId)
        val own = ledger.contributions().filter { it.event.userId == userId }
        val updated = ledger.selectedAt(userId)?.let {
            maxOf(it, own.maxOfOrNull { entry -> entry.event.timestampUtcMs } ?: it, lastSettlement)
        }
        return ConquestSummary(userId, team,
            if (team == null) 0 else owners.values.count { it == team }, own.size, updated)
    }
}
