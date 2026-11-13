package com.daehyundai.map.feature.conquest.domain

import kotlin.math.*

/** Conservative broad phase only. Exact geometry and all gameplay decisions stay in the detector. */
internal class RoadCandidateIndex(private val catalog: ConquestCatalog, marginMeters: Double) {
    private val cells = mutableMapOf<Pair<Int, Int>, MutableSet<Int>>()
    private val fallback = mutableSetOf<Int>()
    private val ordinal = catalog.roads.mapIndexed { index, road -> road.id to index }.toMap()
    private fun cell(value: Double) = floor(value / .001).toInt()

    init {
        catalog.roads.forEachIndexed { index, road ->
            val latMargin = marginMeters / 110_000.0
            val maxLat = road.points.maxOf { abs(it.latitude) } + latMargin
            val lonMargin = latMargin / cos(Math.toRadians(maxLat.coerceAtMost(89.0)))
            val x = cell(road.points.minOf { it.longitude } - lonMargin)..cell(road.points.maxOf { it.longitude } + lonMargin)
            val y = cell(road.points.minOf { it.latitude } - latMargin)..cell(road.points.maxOf { it.latitude } + latMargin)
            if (x.count().toLong() * y.count() > 10_000) fallback.add(index)
            else for (col in x) for (row in y) cells.getOrPut(col to row) { mutableSetOf() }.add(index)
        }
    }

    fun candidates(point: ConquestPoint, active: Set<String>): List<DirectedRoad> {
        val indices = mutableSetOf<Int>()
        indices.addAll(fallback)
        indices.addAll(cells[cell(point.longitude) to cell(point.latitude)].orEmpty())
        // Exit departure can be outside the road bounds; never drop an already armed trip here.
        active.mapNotNullTo(indices) { ordinal[it] }
        return indices.sorted().map { catalog.roads[it] }
    }
}
