package com.daehyundai.map.feature.exploration.domain

import java.io.Reader
import kotlin.math.*

data class MeterPoint(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) }
    fun distance(other: MeterPoint) = hypot(x - other.x, y - other.y)
}
data class CellIndex(val col: Int, val row: Int)
data class RoadPath(val id: String, val points: List<MeterPoint>) {
    init { require(id.isNotBlank() && points.size >= 2) }
    fun distance(point: MeterPoint): Double = points.zipWithNext().minOf { (a, b) ->
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        val t = if (length == 0.0) 0.0 else
            (((point.x - a.x) * dx + (point.y - a.y) * dy) / length).coerceIn(0.0, 1.0)
        point.distance(MeterPoint(a.x + t * dx, a.y + t * dy))
    }
}

/** Fixed local metre grid; a cell is not an OSM road ID and never becomes a conquest road event. */
data class LocalGrid(val originLongitude: Double, val originLatitude: Double, val sizeMeters: Double) {
    init {
        require(originLongitude.isFinite() && originLongitude in -180.0..180.0)
        require(originLatitude.isFinite() && originLatitude in -80.0..80.0)
        require(sizeMeters.isFinite() && sizeMeters in 1.0..1_000.0)
    }
    private val xScale = Math.toRadians(1.0) * 6_371_008.8 * cos(Math.toRadians(originLatitude))
    private val yScale = Math.toRadians(1.0) * 6_371_008.8
    fun project(point: GeoPoint) = MeterPoint((point.longitude - originLongitude) * xScale,
        (point.latitude - originLatitude) * yScale)
    fun unproject(point: MeterPoint) = GeoPoint(originLatitude + point.y / yScale,
        originLongitude + point.x / xScale)
    fun index(point: MeterPoint) = CellIndex(floor(point.x / sizeMeters).toInt(), floor(point.y / sizeMeters).toInt())
    fun unambiguous(point: MeterPoint, index: CellIndex, accuracyMeters: Float): Boolean {
        val x = point.x - index.col * sizeMeters
        val y = point.y - index.row * sizeMeters
        return x > accuracyMeters && x < sizeMeters - accuracyMeters &&
            y > accuracyMeters && y < sizeMeters - accuracyMeters
    }
}

data class DemoRoadFix(val id: String, val point: GeoPoint, val offsetMillis: Long)
data class RoadCellCatalog(
    val version: String, val regionId: String, val grid: LocalGrid,
    val roads: List<RoadPath>, val cells: Map<CellIndex, List<Int>>,
    val demo: List<DemoRoadFix> = emptyList(),
) {
    init {
        require(version.isNotBlank() && regionId.isNotBlank())
        require(roads.map { it.id }.distinct().size == roads.size)
        require(cells.values.all { refs -> refs.isNotEmpty() && refs.distinct().size == refs.size && refs.all { it in roads.indices } })
        require(demo.all { it.id.isNotBlank() && it.offsetMillis >= 0 })
        require(demo.map { it.id }.distinct().size == demo.size)
        require(demo.zipWithNext().all { (a, b) -> a.offsetMillis < b.offsetMillis })
    }
    fun cellId(cell: CellIndex) = "$version:${cell.col}:${cell.row}"
    fun roadDistance(point: MeterPoint, cell: CellIndex): Double? = cells[cell]?.minOf { roads[it].distance(point) }
}

object RoadCellCatalogReader {
    fun read(reader: Reader): RoadCellCatalog {
        val lines = reader.buffered().readLines()
        require(lines.size >= 4 && lines[0] == "PLEOS_ROAD_CELLS_V1")
        val gridValues = lines[3].split(',').map(String::toDouble)
        require(gridValues.size == 3)
        val roads = mutableListOf<RoadPath>()
        val cells = mutableMapOf<CellIndex, List<Int>>()
        val demo = mutableListOf<DemoRoadFix>()
        for (line in lines.drop(4).filter(String::isNotBlank)) {
            val values = line.split('|')
            when (values[0]) {
                "ROAD" -> {
                    require(values.size == 3)
                    val points = values[2].split(';').map {
                        val p = it.split(',').map(String::toDouble)
                        require(p.size == 2)
                        MeterPoint(p[0], p[1])
                    }
                    roads.add(RoadPath(values[1], points))
                }
                "CELL" -> {
                    require(values.size == 4)
                    val index = CellIndex(values[1].toInt(), values[2].toInt())
                    require(index !in cells) { "중복 셀" }
                    cells[index] = values[3].split(',').map(String::toInt)
                }
                "DEMO" -> {
                    require(values.size == 5)
                    demo.add(DemoRoadFix(values[1], GeoPoint(values[2].toDouble(), values[3].toDouble()), values[4].toLong()))
                }
                else -> error("알 수 없는 도로 카탈로그 항목")
            }
        }
        return RoadCellCatalog(lines[1], lines[2], LocalGrid(gridValues[0], gridValues[1], gridValues[2]), roads, cells, demo)
    }
}
