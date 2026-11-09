package com.daehyundai.map.feature.exploration

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.exploration.data.*
import com.daehyundai.map.feature.exploration.domain.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RegionDiscoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = LocationSourceType.MOCK
    private fun boundary() = File("src/main/assets/exploration/namyang.boundary").reader().use(RegionBoundaryReader::read)
    private fun sample(t: Long = 10_000, user: String = "u-a") = LocationSample(
        "sample-$t", user, 37.1995, 126.8310, t, 5f, source,
    )
    private fun store(file: File = File(temporary.root, "discoveries.bin")) =
        FileDiscoveryStore(file, boundary().datasetVersion, source)
    private fun detector() = RegionEntryDetector(boundary(), source)

    @Test fun actualDatasetContainsNamyangAndExcludesSeoul() {
        val region = boundary()
        assertEquals("KR:SGIS:31241723", region.id)
        assertEquals("경기도 화성시만세구 남양읍", region.name)
        assertTrue(region.contains(GeoPoint(37.1995, 126.8310), 5f))
        assertFalse(region.contains(GeoPoint(37.55, 126.98), 5f))
        assertEquals(1, region.polygons.size)
    }

    @Test fun aSingleFixDoesNotDiscoverButBoundaryTimeDoes() {
        val detector = detector()
        assertEquals(EntryStatus.CONFIRMING, detector.evaluate(sample()).status)
        assertEquals(EntryStatus.CONFIRMING, detector.evaluate(sample(12_999)).status)
        val found = detector.evaluate(sample(13_000))
        assertEquals(EntryStatus.CONFIRMED, found.status)
        assertEquals(13_000L, found.event!!.timestampUtcMs)
        assertEquals(boundary().id, requireNotNull(found.event).regionId)
    }

    @Test fun poorOrUnknownAccuracyCannotDiscover() {
        for (accuracy in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f, 25.1f)) {
            val detector = detector()
            detector.evaluate(sample())
            assertEquals(EntryStatus.REJECTED, detector.evaluate(sample(15_000).copy(accuracyMeters = accuracy)).status)
            assertEquals(EntryStatus.CONFIRMING, detector.evaluate(sample(20_000)).status)
        }
    }

    @Test fun malformedFixesBreakUnconfirmedEntry() {
        val original = sample(15_000)
        for (invalid in listOf(original.copy(latitude = Double.NaN), original.copy(longitude = 181.0),
            original.copy(eventId = ""), original.copy(timestampUtcMs = -1))) {
            val detector = detector()
            detector.evaluate(sample())
            assertEquals(EntryStatus.REJECTED, detector.evaluate(invalid).status)
            assertEquals(EntryStatus.CONFIRMING, detector.evaluate(sample(20_000)).status)
        }
    }

    @Test fun sourceAndUserAreIsolated() {
        val detector = detector()
        detector.evaluate(sample())
        assertEquals(EntryStatus.REJECTED, detector.evaluate(sample(15_000).copy(source = LocationSourceType.PLEOS)).status)
        assertEquals(EntryStatus.CONFIRMING, detector.evaluate(sample(15_000, "u-b")).status)
        assertEquals(EntryStatus.CONFIRMED, detector.evaluate(sample(15_000)).status)
        assertEquals(EntryStatus.REJECTED, detector.evaluate(sample(20_000, " ")).status)
    }

    @Test fun exitOrLongSignalGapRequiresFreshConfirmation() {
        val detector = detector()
        detector.evaluate(sample())
        assertEquals(EntryStatus.OUTSIDE, detector.evaluate(sample(15_000).copy(latitude = 37.55)).status)
        assertEquals(EntryStatus.CONFIRMING, detector.evaluate(sample(20_000)).status)
        assertEquals(EntryStatus.CONFIRMING, detector.evaluate(sample(50_001)).status)
        assertEquals(EntryStatus.CONFIRMED, detector.evaluate(sample(53_001)).status)
    }

    @Test fun duplicateAndReversedTimesCannotConfirm() {
        val detector = detector()
        detector.evaluate(sample())
        assertEquals(EntryStatus.REJECTED, detector.evaluate(sample()).status)
        assertEquals(EntryStatus.REJECTED, detector.evaluate(sample(9_000)).status)
        assertEquals(EntryStatus.REJECTED, detector.evaluate(sample(15_000).copy(eventId = "sample-10000")).status)
        assertEquals(EntryStatus.CONFIRMED, detector.evaluate(sample(20_000)).status)
    }

    @Test fun holesAndAccuracyCircleDoNotGrantNeighboringRegion() {
        fun square(low: Double, high: Double) = listOf(GeoPoint(low, low), GeoPoint(low, high),
            GeoPoint(high, high), GeoPoint(high, low), GeoPoint(low, low))
        val polygon = BoundaryPolygon(listOf(square(0.0, 1.0), square(0.4, 0.6)))
        assertTrue(polygon.confidentlyContains(GeoPoint(0.2, 0.2), 5f))
        assertFalse(polygon.confidentlyContains(GeoPoint(0.5, 0.5), 5f))
        assertFalse(polygon.confidentlyContains(GeoPoint(0.0, 0.2), 5f))
        assertFalse(polygon.confidentlyContains(GeoPoint(0.00001, 0.2), 5f))
        assertFalse(polygon.confidentlyContains(GeoPoint(1.1, 0.2), 5f))
    }

    @Test fun multipolygonIncludesSeparateIsland() {
        fun square(lon: Double) = BoundaryPolygon(listOf(listOf(GeoPoint(37.0, lon),
            GeoPoint(37.0, lon + .01), GeoPoint(37.01, lon + .01), GeoPoint(37.01, lon), GeoPoint(37.0, lon))))
        val region = RegionBoundary("islands", "islands", "test", listOf(square(126.0), square(127.0)))
        assertTrue(region.contains(GeoPoint(37.005, 127.005), 5f))
        assertFalse(region.contains(GeoPoint(37.005, 126.5), 5f))
    }

    @Test fun discoveriesSurviveReopenAndReentryDoesNotDoubleCount() = runTest {
        val first = LocalExplorationFeature(boundary(), source, store())
        first.onLocation(sample())
        assertTrue(first.onLocation(sample(15_000)).newlyDiscovered)
        val reopened = LocalExplorationFeature(boundary(), source, store())
        assertEquals(1, reopened.getSummary("u-a").discoveredRegionCount)
        assertEquals(0, reopened.getSummary("u-b").discoveredRegionCount)
        assertFalse(reopened.onLocation(sample(15_000)).newlyDiscovered)
        reopened.onLocation(sample(20_000))
        assertFalse(reopened.onLocation(sample(25_000)).newlyDiscovered)
        assertEquals(15_000L, reopened.getSummary("u-a").lastUpdatedUtcMs)
        assertEquals("집계 없음", explorationRate(reopened.getSummary("u-a")))
        assertEquals(0, reopened.getSummary("u-a").exploredUnitCount)
        assertEquals(0, reopened.getSummary("u-a").unlockedRoadCount)
    }

    @Test fun concurrentRepeatedTrustedEventsArePersistedOnce() = runTest {
        val feature = LocalExplorationFeature(boundary(), source, store())
        val event = RegionDiscovered("e1", "u-a", boundary().id, 1_000, source)
        (1..30).map { async { feature.onRegionDiscovered(event.copy(eventId = "event-$it")) } }.awaitAll()
        assertEquals(1, feature.discoveries("u-a").size)
        feature.onRegionDiscovered(event.copy(userId = "u-b"))
        assertEquals(1, feature.getSummary("u-b").discoveredRegionCount)
        assertEquals(2, store().read().size)
    }

    @Test fun writeFailureDoesNotPublishAndExactSameInputCanBeRetried() = runTest {
        var failing = true
        val durable = store()
        val faulting = object : DiscoveryStore {
            override fun read() = durable.read()
            override fun write(records: List<RegionDiscovered>) {
                if (failing) throw IOException("disk full")
                durable.write(records)
            }
        }
        val feature = LocalExplorationFeature(boundary(), source, faulting)
        feature.onLocation(sample())
        try { feature.onLocation(sample(15_000)); fail("write must fail") } catch (_: IOException) { }
        assertEquals(0, feature.getSummary("u-a").discoveredRegionCount)
        assertTrue(durable.read().isEmpty())
        failing = false
        assertTrue(feature.onLocation(sample(15_000)).newlyDiscovered)
        assertEquals(1, durable.read().size)
    }

    @Test fun unrecognizedRegionOrSourceDoesNotPolluteProgress() = runTest {
        val feature = LocalExplorationFeature(boundary(), source, store())
        val event = RegionDiscovered("e1", "u-a", "KR:demo:region-01", 1_000, source)
        try { feature.onRegionDiscovered(event); fail("unknown region") } catch (_: IllegalArgumentException) { }
        try { feature.onRegionDiscovered(event.copy(regionId = boundary().id, source = LocationSourceType.PLEOS))
            fail("wrong source") } catch (_: IllegalArgumentException) { }
        assertEquals(0, feature.getSummary("u-a").discoveredRegionCount)
    }

    @Test fun corruptionIsReportedAndNeverSilentlyOverwritten() {
        val file = File(temporary.root, "broken.bin").apply { writeText("broken content") }
        val original = file.readBytes()
        try { store(file).read(); fail("invalid storage") } catch (_: Exception) { }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun incompatibleDatasetRequiresMigration() {
        val file = File(temporary.root, "versioned.bin")
        store(file).write(listOf(RegionDiscovered("e1", "u-a", boundary().id, 1_000, source)))
        assertThrows(IllegalArgumentException::class.java) { FileDiscoveryStore(file, "next-version", source).read() }
        assertEquals(1, store(file).read().size)
    }

    @Test fun koreanDisplayUsesSeoulRegardlessOfDeviceTimezone() {
        assertEquals("1970-01-01 09:00:00 KST", formatDiscoveryTime(0))
    }
}
