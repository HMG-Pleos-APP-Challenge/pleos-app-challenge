package com.daehyundai.map.feature.exploration.domain

import java.io.Reader
import kotlin.math.*

data class LandmarkDefinition(
    val id: String, val name: String, val point: GeoPoint,
    val radiusMeters: Double, val dwellMillis: Long, val maxGapMillis: Long,
    val sourceUrl: String,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && sourceUrl.startsWith("https://"))
        require(radiusMeters.isFinite() && radiusMeters in 30.0..500.0)
        require(maxGapMillis in 1_000..30_000 && dwellMillis in maxGapMillis..3_600_000)
    }
    fun distanceMeters(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.latitude - point.latitude)
        val dLon = Math.toRadians(other.longitude - point.longitude)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(point.latitude)) *
            cos(Math.toRadians(other.latitude)) * sin(dLon / 2).pow(2)
        return 2 * 6_371_008.8 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
data class LandmarkCatalog(val version: String, val regionId: String, val landmarks: List<LandmarkDefinition>) {
    init {
        require(version.isNotBlank() && regionId.isNotBlank())
        require(landmarks.map { it.id }.distinct().size == landmarks.size)
    }
}
object LandmarkCatalogReader {
    fun read(reader: Reader): LandmarkCatalog {
        val lines = reader.buffered().readLines().filter(String::isNotBlank)
        require(lines.size >= 3 && lines[0] == "PLEOS_LANDMARK_CATALOG_V1")
        val landmarks = lines.drop(3).map { line ->
            val v = line.split('|')
            require(v.size == 8)
            LandmarkDefinition(v[0],v[1],GeoPoint(v[2].toDouble(),v[3].toDouble()),
                v[4].toDouble(),v[5].toLong(),v[6].toLong(),v[7])
        }
        return LandmarkCatalog(lines[1],lines[2],landmarks)
    }
}
data class LandmarkUnlock(val userId: String, val landmarkId: String, val timestampUtcMs: Long,
    val source: com.daehyundai.map.core.model.LocationSourceType)
data class LandmarkCard(
    val id: String, val name: String, val radiusMeters: Double, val requiredMillis: Long,
    val observedMillis: Long, val unlockedUtcMs: Long?,
)
/** Feature-owned view model; common ExplorationSummary remains a road-cell progress contract. */
data class LandmarkCollectionSummary(val userId: String, val cards: List<LandmarkCard>) {
    val unlockedCount: Int get() = cards.count { it.unlockedUtcMs != null }
    val totalCount: Int get() = cards.size
}
