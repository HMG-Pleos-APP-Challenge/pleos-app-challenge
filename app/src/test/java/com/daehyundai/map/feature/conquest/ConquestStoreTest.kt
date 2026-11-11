package com.daehyundai.map.feature.conquest

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.data.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConquestStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun record() = ConquestRecord("roads-v1", LocationSourceType.MOCK, 100, 200,
        listOf(TeamSelection("u", "red", 100)), listOf(RoadVisited("e", "u", "ab", 150, LocationSourceType.MOCK)))

    @Test fun roundTripAndReplacementPreserveIndependentFiles() {
        val mock = FileConquestStore(File(folder.root, "conquest/mock.bin"))
        val real = FileConquestStore(File(folder.root, "conquest/pleos.bin"))
        assertNull(mock.read())
        mock.write(record())
        assertEquals(record(), mock.read())
        assertNull(real.read())
        mock.write(record().copy(observedUtcMs = 300))
        assertEquals(300L, mock.read()!!.observedUtcMs)
        assertNull(real.read())
    }

    @Test fun malformedAndTruncatedRecordsAreReportedInsteadOfOverwritten() {
        val file = folder.newFile()
        file.writeText("broken")
        val bytes = file.readBytes()
        try {
            FileConquestStore(file).read()
            fail("Corrupt record must not silently reset progress")
        } catch (_: java.io.IOException) { }
        assertArrayEquals(bytes, file.readBytes())
    }
}
