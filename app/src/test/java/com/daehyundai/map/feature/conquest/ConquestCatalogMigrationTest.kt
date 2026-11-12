package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.data.*
import com.daehyundai.map.feature.conquest.domain.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConquestCatalogMigrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val old = File("src/main/assets/conquest/namyang.segments").reader().use(ConquestCatalogReader::read)
    private val next = File("../docs/data/namyang-conquest-full-v1.segments").reader().use(ConquestCatalogReader::read)
    private val source = LocationSourceType.MOCK
    private val base = java.time.Instant.parse("2026-11-11T00:00:00Z").toEpochMilli()
    private fun legacy() = File(folder.root, "mock-${old.version}.bin")
    private fun event(id: String) = RoadVisited(id, "u", old.roads.first().id, base + 150, source)
    private fun seed() {
        FileConquestStore(legacy()).write(ConquestRecord(old.version, source, base + 100, base + 200,
            listOf(TeamSelection("u", "red", base + 100)), listOf(event("accepted"))))
        FilePendingVisitsStore(File(folder.root, "${legacy().name}.pending"), old.version, source, "u")
            .write(listOf(event("accepted"), event("pending")))
    }
    private fun migrate() = ConquestCatalogMigration.prepare(folder.root, "mock", "u", setOf("red"), source, old, next)

    @Test fun copiesRecordAndPendingWithoutChangingLegacyAndRetryIsIdempotent() {
        seed()
        val bytes = legacy().readBytes()
        val before = FileConquestStore(legacy()).read()!!
        val result = migrate()
        assertEquals(before.copy(datasetVersion = next.version), FileConquestStore(result.record).read())
        assertEquals(listOf(event("accepted"), event("pending")),
            FilePendingVisitsStore(result.pending, next.version, source, "u").read())
        assertArrayEquals(bytes, legacy().readBytes())
        assertEquals(result, migrate())
        val restored = LocalConquestFeature(setOf("red"), next.roads.map { it.id }.toSet(), next.version,
            source, FileConquestStore(result.record), { base + 200 })
        assertEquals(ContributionResult.DUPLICATE_EVENT, restored.accept(event("accepted")))
    }

    @Test fun changedSourceDoesNotOverwritePreparedBundle() {
        seed()
        val result = migrate()
        val bytes = result.record.readBytes()
        FileConquestStore(legacy()).write(FileConquestStore(legacy()).read()!!.copy(observedUtcMs = base + 300))
        try { migrate(); fail("Source conflict must be reported") } catch (_: IllegalStateException) { }
        assertArrayEquals(bytes, result.record.readBytes())
    }

    @Test fun corruptLegacyFailsWithoutPublishing() {
        legacy().writeText("broken")
        try { migrate(); fail("Corruption must be reported") } catch (_: java.io.IOException) { }
        assertFalse(folder.root.listFiles()!!.any { it.name.startsWith("prepared-") })
        assertEquals("broken", legacy().readText())
    }

    @Test fun geometryMismatchAndCrossSourceAreRejected() {
        seed()
        try {
            ConquestCatalogMigration.prepare(folder.root, "pleos", "u", setOf("red"), source, old, next)
            fail("Prefix isolation required")
        } catch (_: IllegalArgumentException) { }
        val changed = ConquestCatalog(next.version, next.roads.filter { it.id != old.roads.first().id })
        try {
            ConquestCatalogMigration.prepare(folder.root, "mock", "u", setOf("red"), source, old, changed)
            fail("Missing legacy road must block migration")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun incompleteStagingDirectoryDoesNotBecomeActive() {
        seed()
        File(folder.root, ".migration-interrupted").mkdir()
        File(folder.root, ".migration-interrupted/record.bin").writeText("partial")
        assertNotNull(FileConquestStore(migrate().record).read())
        assertEquals("partial", File(folder.root, ".migration-interrupted/record.bin").readText())
    }

    @Test fun missingPreparedJournalIsNotTreatedAsEmptySuccess() {
        seed()
        val result = migrate()
        assertTrue(result.pending.delete())
        try { migrate(); fail("Incomplete bundle must fail") } catch (_: IllegalStateException) { }
        assertNotNull(FileConquestStore(legacy()).read())
    }

    @Test fun wrongUserAndUnknownPendingRoadCannotBeImported() {
        seed()
        try {
            ConquestCatalogMigration.prepare(folder.root, "mock", "other", setOf("red"), source, old, next)
            fail("Foreign user must fail")
        } catch (_: IllegalArgumentException) { }
        FilePendingVisitsStore(File(folder.root, "${legacy().name}.pending"), old.version, source, "u")
            .write(listOf(event("unknown").copy(roadSegmentId = "not-in-catalog")))
        try { migrate(); fail("Unknown pending road must fail") } catch (_: IllegalArgumentException) { }
        assertFalse(folder.root.listFiles()!!.any { it.name.startsWith("prepared-") })
    }
}
