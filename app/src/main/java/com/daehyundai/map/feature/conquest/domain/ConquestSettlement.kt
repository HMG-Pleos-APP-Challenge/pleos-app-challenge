package com.daehyundai.map.feature.conquest.domain

import com.daehyundai.map.core.model.ConquestSummary

data class SettlementResult(val timestampUtcMs: Long, val owners: Map<String, String?>)

enum class SettlementReason(val label: String) {
    CAPTURED("신규 점령 · 단독 최다 득점"), RETAINED("점령 유지 · 단독 최다 득점"),
    TAKEN_OVER("탈환 · 다른 팀이 단독 최다 득점"), TIE_RETAINED("점령 유지 · 기존 소유 팀이 공동 1위"),
    TIE_NEUTRAL("중립 · 공동 1위에 기존 소유 팀 없음"), NO_CONTRIBUTIONS("중립 · 인정 기여 없음")
}

data class RoadSettlementExplanation(
    val timestampUtcMs: Long, val scores: Map<String, Int>, val previousOwnerTeamId: String?,
    val ownerTeamId: String?, val reason: SettlementReason,
)

/** Runs missed noons in order: a tie depends on the owner from the preceding settlement. */
class ConquestSettlement(private val ledger: ContributionLedger, startedUtcMs: Long) {
    private var lastSettlement = ConquestTime.latestSettlement(startedUtcMs)
    private var owners: Map<String, String?> = ledger.segments.associateWith { null }
    private var observedUtcMs = startedUtcMs
    private var explanations: Map<String, RoadSettlementExplanation> = emptyMap()

    @Synchronized fun settleThrough(nowUtcMs: Long): List<SettlementResult> {
        require(nowUtcMs >= observedUtcMs) { "Game time must not move backwards" }
        val contributions = ledger.contributions()
        val results = mutableListOf<SettlementResult>()
        var next = ConquestTime.nextSettlement(lastSettlement)
        while (next <= nowUtcMs) {
            val scores = contributions.filter { ConquestTime.inWindow(it.event.timestampUtcMs, next) }
                .groupBy { it.event.roadSegmentId }
            val previous = owners
            explanations = ledger.segments.associateWith { segment ->
                val counts = scores[segment].orEmpty().groupingBy { it.teamId }.eachCount()
                val high = counts.values.maxOrNull()
                val leaders = counts.filterValues { it == high }.keys
                val old = previous[segment]
                val owner = when {
                    leaders.isEmpty() -> null
                    leaders.size == 1 -> leaders.first()
                    old in leaders -> old
                    else -> null
                }
                val reason = when {
                    leaders.isEmpty() -> SettlementReason.NO_CONTRIBUTIONS
                    leaders.size > 1 -> if (old in leaders) SettlementReason.TIE_RETAINED else SettlementReason.TIE_NEUTRAL
                    old == null -> SettlementReason.CAPTURED
                    old == owner -> SettlementReason.RETAINED
                    else -> SettlementReason.TAKEN_OVER
                }
                RoadSettlementExplanation(next, counts.toMap(), old, owner, reason)
            }
            owners = explanations.mapValues { it.value.ownerTeamId }
            lastSettlement = next
            results.add(SettlementResult(next, owners.toMap()))
            next = ConquestTime.nextSettlement(next)
        }
        observedUtcMs = nowUtcMs
        return results
    }

    @Synchronized fun currentOwners(): Map<String, String?> = owners.toMap()

    @Synchronized fun lastExplanations(): Map<String, RoadSettlementExplanation> = explanations.toMap()

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
