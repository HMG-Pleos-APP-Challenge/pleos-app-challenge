package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.domain.*
import com.daehyundai.map.feature.conquest.data.*
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.system.measureNanoTime

/** JVM baseline, not an Android performance guarantee. No timing-dependent pass/fail. */
class ConquestScaleMeasurementTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun measureExpandedDetectionSettlementAndFileRestore() {
        val metrics = mutableListOf<String>()
        fun metric(name: String, nanos: Long) { metrics += "$name=${nanos / 1_000_000.0}ms" }
        lateinit var catalog: ConquestCatalog
        metric("catalog_load", measureNanoTime {
            catalog = File("src/main/assets/conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
        })
        assertEquals(1453, catalog.roads.size)
        lateinit var detector: DirectedRoadDetector
        metric("detector_index_build", measureNanoTime { detector = DirectedRoadDetector(catalog, LocationSourceType.MOCK) })
        val base = Instant.parse("2026-11-11T00:00:00Z").toEpochMilli()
        val samples = catalog.roads.flatMapIndexed { roadIndex, road ->
            val points = road.points.zipWithNext().flatMap { (a,b) ->
                val count = kotlin.math.ceil(road.distance(a,b)/12.0).toInt().coerceAtLeast(1)
                (0 until count).map { i -> ConquestPoint(a.latitude+(b.latitude-a.latitude)*i/count,
                    a.longitude+(b.longitude-a.longitude)*i/count) }
            }
            points.mapIndexed { i, point -> LocationSample("s$roadIndex:$i", "driver$roadIndex",
                point.latitude, point.longitude, base+i*2000L, 3f, LocationSourceType.MOCK) }
        }.take(3000)
        samples.take(300).forEach { detector.onSample(it) }
        samples.map { it.userId }.toSet().forEach { detector.reset(it) }
        val times = samples.map { measureNanoTime { detector.onSample(it) } }.sorted()
        metric("sample_p50", times[times.size/2]); metric("sample_p95", times[(times.size*95)/100])
        metric("sample_max", times.last())
        val teams = (0 until 2000).map { TeamSelection("u$it", if(it%2==0) "red" else "blue", base) }
        val events = teams.mapIndexed { i, t -> RoadVisited("e$i", t.userId, catalog.roads[i%1453].id,
            base+1000+i, LocationSourceType.MOCK) }
        val store = FileConquestStore(File(folder.root, "scale.bin"))
        metric("file_write_2000", measureNanoTime { store.write(ConquestRecord(catalog.version,
            LocationSourceType.MOCK, base, base+5000, teams, events)) })
        lateinit var feature: LocalConquestFeature
        var now = base+5000
        metric("restore_2000", measureNanoTime { feature = LocalConquestFeature(setOf("red","blue"),
            catalog.roads.map { it.id }.toSet(), catalog.version, LocationSourceType.MOCK, store, { now }) })
        assertEquals(1, feature.readSummary("u0").contributionCount)
        now = base+7*86_400_000L
        metric("seven_day_settlement_2000", measureNanoTime { feature.readSummary("u0") })
        assertEquals(0, feature.readSummary("u0").ownedRoadCount)
        metric("restore_after_settlement", measureNanoTime { feature = LocalConquestFeature(setOf("red","blue"),
            catalog.roads.map { it.id }.toSet(), catalog.version, LocationSourceType.MOCK, store, { now }) })
        assertEquals(1, feature.readSummary("u0").contributionCount)
        val report = File("build/reports/conquest-scale-jvm.txt")
        requireNotNull(report.parentFile).mkdirs()
        report.writeText("JVM baseline; 1453 directions; 3000 synthetic samples; 2000 users/events; 7 missed noons\n"+
            metrics.joinToString("\n")+"\n")
    }
}
