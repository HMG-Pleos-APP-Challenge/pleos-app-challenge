package com.daehyundai.map.feature.conquest.domain

import java.io.Reader
import kotlin.math.*

data class ConquestPoint(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && latitude in -80.0..80.0 && longitude.isFinite() && longitude in -180.0..180.0) }
}

/** Feature-owned geometry; does not depend on exploration cells or a map SDK. */
class DirectedRoad(val id: String, val name: String, val fromNode: String, val toNode: String,
    points: List<ConquestPoint>) {
    val points = points.toList()
    private val origin = this.points.firstOrNull() ?: error("Empty road")
    private val xScale = Math.PI / 180 * 6_371_008.8 * cos(Math.toRadians(origin.latitude))
    private val yScale = Math.PI / 180 * 6_371_008.8
    private fun project(point: ConquestPoint) = Pair((point.longitude - origin.longitude) * xScale,
        (point.latitude - origin.latitude) * yScale)
    private val xy = this.points.map(::project)
    private val lengths = xy.zipWithNext { a, b -> hypot(b.first - a.first, b.second - a.second) }
    val lengthMeters = lengths.sum()

    init {
        require(id.isNotBlank() && name.isNotBlank() && fromNode.isNotBlank() && toNode.isNotBlank() && fromNode != toNode)
        require(this.points.size >= 2 && lengths.all { it > 0 } && lengthMeters >= 50)
    }

    data class Position(val distanceMeters: Double, val progressMeters: Double)

    /** Endpoint extensions let the detector require crossing the exit rather than merely approaching it. */
    fun locate(point: ConquestPoint): Position {
        val p = project(point)
        var best = Position(Double.POSITIVE_INFINITY, 0.0)
        var before = 0.0
        for (i in lengths.indices) {
            val a = xy[i]; val b = xy[i + 1]
            val dx = b.first - a.first; val dy = b.second - a.second
            val raw = ((p.first - a.first) * dx + (p.second - a.second) * dy) / (lengths[i] * lengths[i])
            val t = when {
                i == 0 && raw < 0 -> raw.coerceAtLeast(-30 / lengths[i])
                i == lengths.lastIndex && raw > 1 -> raw.coerceAtMost(1 + 30 / lengths[i])
                else -> raw.coerceIn(0.0, 1.0)
            }
            val candidate = Position(hypot(p.first - a.first - t * dx, p.second - a.second - t * dy),
                before + t * lengths[i])
            if (candidate.distanceMeters < best.distanceMeters) best = candidate
            before += lengths[i]
        }
        return best
    }

    fun distance(a: ConquestPoint, b: ConquestPoint): Double {
        val p = project(a); val q = project(b)
        return hypot(p.first - q.first, p.second - q.second)
    }
}

class ConquestCatalog(val version: String, roads: List<DirectedRoad>) {
    val roads = roads.toList()
    init {
        require(version.isNotBlank() && this.roads.isNotEmpty())
        require(this.roads.map { it.id }.distinct().size == this.roads.size)
    }
}

object ConquestCatalogReader {
    fun read(reader: Reader): ConquestCatalog {
        val lines = reader.buffered().readLines()
        require(lines.size >= 3 && lines[0] == "PLEOS_CONQUEST_SEGMENTS_V1")
        return ConquestCatalog(lines[1], lines.drop(2).filter(String::isNotBlank).map { line ->
            val fields = line.split('|')
            require(fields.size == 5)
            DirectedRoad(fields[0], fields[1], fields[2], fields[3], fields[4].split(';').map {
                val coordinates = it.split(',')
                require(coordinates.size == 2)
                ConquestPoint(coordinates[0].toDouble(), coordinates[1].toDouble())
            })
        })
    }
}
