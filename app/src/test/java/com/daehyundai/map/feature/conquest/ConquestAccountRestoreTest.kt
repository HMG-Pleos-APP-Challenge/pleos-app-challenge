package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.data.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConquestAccountRestoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun emptyRecord() = ConquestRecord("v1", LocationSourceType.PLEOS, 100, 200)

    @Test fun missingFolderAndEmptySettledFilesPermitRestore() {
        assertTrue(ConquestAccountRestore.canRestore(File(temp.root, "missing")))
        FileConquestStore(File(temp.root, "pleos-v1.bin")).write(emptyRecord())
        FileConquestStore(File(temp.root, "pleos-emulator-v1.bin")).write(emptyRecord())
        assertTrue(ConquestAccountRestore.canRestore(temp.root))
        var called = false
        ConquestAccountRestore.restoreWhenEmpty(temp.root) { called = true }
        assertTrue(called)
    }

    @Test fun teamSelectionBlocksRestoreEvenWithoutContribution() {
        FileConquestStore(File(temp.root, "pleos-v1.bin")).write(
            emptyRecord().copy(teams = listOf(TeamSelection("u", "red", 150))))
        assertFalse(ConquestAccountRestore.canRestore(temp.root))
        var called = false
        try { ConquestAccountRestore.restoreWhenEmpty(temp.root) { called = true }; fail("Expected refusal") }
        catch (_: IllegalStateException) { }
        assertFalse(called)
    }

    @Test fun pendingVisitsInOtherSdkPartitionBlockRestore() {
        FilePendingVisitsStore(File(temp.root, "pleos-emulator-v1.bin.pending"), "v1", LocationSourceType.PLEOS, "u")
            .write(listOf(RoadVisited("event", "u", "road", 150, LocationSourceType.PLEOS)))
        assertFalse(ConquestAccountRestore.canRestore(temp.root))
    }

    @Test fun corruptSdkFileBlocksRestoreAndIsPreserved() {
        val file = File(temp.root, "pleos-v1.bin").apply { writeText("corrupt") }
        assertFalse(ConquestAccountRestore.canRestore(temp.root))
        assertEquals("corrupt", file.readText())
    }

    @Test fun mockRecordsDoNotBlockSdkAccountRestore() {
        File(temp.root, "mock-v1.bin").writeText("mock fixture")
        assertTrue(ConquestAccountRestore.canRestore(temp.root))
    }
}
