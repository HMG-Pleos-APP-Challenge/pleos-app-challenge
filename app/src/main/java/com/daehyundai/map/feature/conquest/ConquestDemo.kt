package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.RoadVisited
import com.daehyundai.map.core.model.LocationSample
import com.daehyundai.map.core.model.LocationSourceType
import com.daehyundai.map.feature.conquest.data.ConquestRecord
import com.daehyundai.map.feature.conquest.data.ConquestStore
import com.daehyundai.map.feature.conquest.data.LocalConquestFeature
import com.daehyundai.map.feature.conquest.domain.ConquestCatalog
import com.daehyundai.map.feature.conquest.domain.ConquestPoint
import java.time.Instant
import kotlin.math.ceil

enum class DemoRoute(val label: String) {
    COMPLETE("완주"), MIDWAY("중간 출발"), U_TURN("중간 U턴")
}

internal class ConquestDemo(
    private val userId: String,
    private val catalog: ConquestCatalog,
    private val teams: List<String>,
    private val teamId: String,
    roadId: String = "osm:way:55055096:v24:414929156:4641680981",
) {
    private val demoRoad = catalog.roads.firstOrNull { it.id == roadId } ?: catalog.roads.first()
    private val start = Instant.parse("2026-11-11T00:00:00Z").toEpochMilli()
    private val noon = Instant.parse("2026-11-11T03:00:00Z").toEpochMilli()
    private var now = start
    val settled get() = now >= noon
    var competitionStep = 0
        private set
    private var completeRoute = false
    val canCompete get() = completeRoute && settled && teams.any { it != teamId } && competitionStep < 3
    val simulatedUtcMs get() = now
    private val store = object : ConquestStore {
        private var saved: ConquestRecord? = null
        override fun read() = saved
        override fun write(record: ConquestRecord) { saved = record }
    }
    private val feature = LocalConquestFeature(teams.toSet(), catalog.roads.map { it.id }.toSet(),
        catalog.version, LocationSourceType.MOCK, store, { now })
    private val pipeline = ConquestLocationPipeline(feature, catalog, LocationSourceType.MOCK)

    init { check(pipeline.selectTeam(userId, teamId)) }

    suspend fun replay(route: DemoRoute, onSample: suspend (LocationSample) -> Unit = {}): ConquestViewState {
        completeRoute = route == DemoRoute.COMPLETE
        val road = demoRoad
        val points = road.points.zipWithNext().flatMap { (a, b) ->
            val steps = ceil(road.distance(a, b) / 12.0).toInt().coerceAtLeast(1)
            (0 until steps).map { step ->
                val fraction = step.toDouble() / steps
                ConquestPoint(a.latitude + (b.latitude - a.latitude) * fraction,
                    a.longitude + (b.longitude - a.longitude) * fraction)
            }
        } + road.points.last()
        val beforeExit = road.points[road.points.lastIndex - 1]
        val exit = points.last()
        val extension = 8.0 / road.distance(beforeExit, exit)
        val beyondExit = ConquestPoint(exit.latitude + (exit.latitude - beforeExit.latitude) * extension,
            exit.longitude + (exit.longitude - beforeExit.longitude) * extension)
        val halfway = points.size / 2
        val outward = points.take(halfway + 1)
        val path = when (route) {
            DemoRoute.COMPLETE -> points + beyondExit
            DemoRoute.MIDWAY -> points.drop(halfway) + beyondExit
            DemoRoute.U_TURN -> outward + outward.asReversed().drop(1)
        }
        path.forEachIndexed { index, point ->
            now += 2_000
            val sample = LocationSample("namyang-demo-$index", userId, point.latitude,
                point.longitude, now, 3f, LocationSourceType.MOCK)
            pipeline.onSample(sample)
            onSample(sample)
        }
        return snapshot()
    }

    suspend fun settle(): ConquestViewState {
        if (now < noon) now = noon
        return snapshot()
    }

    suspend fun advanceCompetition(): ConquestViewState {
        if (!canCompete) return snapshot()
        val day = 86_400_000L
        val opponent = teams.first { it != teamId }
        when (competitionStep) {
            0 -> {
                now = start + day
                awardParticipants(opponent, "opponent")
                now = noon + day
            }
            1 -> {
                now = start + 2 * day
                awardParticipants(teamId, "allies")
                now = noon + 2 * day
            }
            2 -> now = noon + 5 * day
        }
        val result = snapshot()
        competitionStep++
        return result
    }

    private fun awardParticipants(team: String, group: String) {
        repeat(2) { index ->
            val participant = "competition:$userId:$group:$index"
            check(feature.selectTeam(participant, team))
            check(feature.accept(RoadVisited("$participant:$now", participant, demoRoad.id,
                now, LocationSourceType.MOCK)) == com.daehyundai.map.feature.conquest.domain.ContributionResult.ACCEPTED)
        }
    }

    private suspend fun snapshot() = ConquestViewState(catalog, feature.getSummary(userId), feature.owners(), teams, feature.roadDetails(userId))
}
