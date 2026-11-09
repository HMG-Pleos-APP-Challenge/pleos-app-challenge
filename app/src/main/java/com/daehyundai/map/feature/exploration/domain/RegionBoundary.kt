package com.daehyundai.map.feature.exploration.domain

import java.io.Reader
import kotlin.math.*

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

data class BoundaryPolygon(val rings: List<List<GeoPoint>>) {
    init {
        require(rings.isNotEmpty())
        require(rings.all { it.size >= 4 && it.first() == it.last() })
    }

    fun confidentlyContains(point: GeoPoint, accuracyMeters: Float): Boolean =
        insideRing(point, rings.first()) && rings.drop(1).none { insideRing(point, it) } &&
            rings.all { ring -> ring.zipWithNext().all { (a, b) ->
                edgeDistanceMeters(point, a, b) > accuracyMeters
            } }

    private fun insideRing(p: GeoPoint, ring: List<GeoPoint>): Boolean {
        var inside = false
        for ((a, b) in ring.zipWithNext()) {
            if ((a.latitude > p.latitude) != (b.latitude > p.latitude) &&
                p.longitude < (b.longitude - a.longitude) * (p.latitude - a.latitude) /
                    (b.latitude - a.latitude) + a.longitude) inside = !inside
        }
        return inside
    }

    // Local tangent-plane distance in metres. Limited to this small Korean test region.
    private fun edgeDistanceMeters(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val lonScale = Math.toRadians(1.0) * 6_371_008.8 * cos(Math.toRadians(p.latitude))
        val latScale = Math.toRadians(1.0) * 6_371_008.8
        val ax = (a.longitude - p.longitude) * lonScale
        val ay = (a.latitude - p.latitude) * latScale
        val bx = (b.longitude - p.longitude) * lonScale
        val by = (b.latitude - p.latitude) * latScale
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }
}

data class RegionBoundary(
    val id: String, val name: String, val datasetVersion: String,
    val polygons: List<BoundaryPolygon>,
) {
    init { require(id.isNotBlank() && name.isNotBlank() && datasetVersion.isNotBlank() && polygons.isNotEmpty()) }
    fun contains(point: GeoPoint, accuracyMeters: Float) = polygons.any { it.confidentlyContains(point, accuracyMeters) }
}

/** Narrow, versioned text export of the source GeoJSON. No simplification or invented rectangle. */
object RegionBoundaryReader {
    fun read(reader: Reader): RegionBoundary {
        val lines = reader.buffered().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        require(lines.first() == "PLEOS_BOUNDARY_V1") { "지원하지 않는 경계 형식" }
        val polygons = mutableListOf<BoundaryPolygon>()
        var rings: MutableList<List<GeoPoint>>? = null
        var ring: MutableList<GeoPoint>? = null
        for (line in lines.drop(4)) when (line) {
            "POLYGON" -> {
                require(rings == null)
                rings = mutableListOf()
            }
            "RING" -> {
                require(rings != null && ring == null)
                ring = mutableListOf()
            }
            "END_RING" -> {
                rings!!.add(requireNotNull(ring))
                ring = null
            }
            "END_POLYGON" -> {
                require(ring == null)
                polygons.add(BoundaryPolygon(requireNotNull(rings)))
                rings = null
            }
            else -> {
                val values = line.split(',')
                require(values.size == 2)
                requireNotNull(ring).add(GeoPoint(values[1].toDouble(), values[0].toDouble()))
            }
        }
        require(rings == null && ring == null)
        return RegionBoundary(lines[2], lines[3], lines[1], polygons)
    }
}
