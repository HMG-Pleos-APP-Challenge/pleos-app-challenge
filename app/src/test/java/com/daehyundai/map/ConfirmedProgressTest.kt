package com.daehyundai.map
import com.daehyundai.map.feature.exploration.cloud.*
import org.junit.Assert.*
import org.junit.Test
class ConfirmedProgressTest {
    @Test fun provisionalRecordsCannotInventServerProgress() {
        val view=confirmedSummary("u",243,null)
        assertEquals(0,view.exploredUnitCount);assertEquals(243,view.totalExplorableUnitCount)
    }
    @Test fun serverViewCountsOnlyConfirmedCellsAndRegions() {
        val view=confirmedSummary("u",243,CloudSnapshot(listOf(CloudGrant("region","r",1),CloudGrant("cell","c",2),CloudGrant("landmark","l",3)),310))
        assertEquals(1,view.discoveredRegionCount);assertEquals(1,view.exploredUnitCount);assertEquals(0,view.unlockedRoadCount)
    }
}
