package com.daehyundai.map
import com.daehyundai.map.feature.exploration.data.DiscoveryResult
import com.daehyundai.map.feature.exploration.domain.EntryStatus
import com.daehyundai.map.feature.exploration.tracking.UnlockAnnouncements
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockAnnouncementsTest {
    @Test fun onlyNewUnlocksAreBatchedAndDuplicatesStaySilent()=runTest {
        val spoken=mutableListOf<String>()
        val notifier=UnlockAnnouncements(this,{ spoken.add(it) })
        notifier.record(DiscoveryResult(EntryStatus.CONFIRMED,false))
        advanceUntilIdle(); assertTrue(spoken.isEmpty())
        notifier.record(DiscoveryResult(EntryStatus.CONFIRMED,true,1,newlyCollectedLandmarks=1))
        notifier.record(DiscoveryResult(EntryStatus.CONFIRMED,false,2))
        advanceTimeBy(9_999); assertTrue(spoken.isEmpty())
        advanceUntilIdle()
        assertEquals(1,spoken.size)
        assertTrue(spoken.single().contains("도로 셀 3개"))
        assertTrue(spoken.single().contains("랜드마크 도감 1개"))
        notifier.record(DiscoveryResult(EntryStatus.CONFIRMED,false)); advanceUntilIdle(); assertEquals(1,spoken.size)
    }
    @Test fun stoppingCancelsPendingSpeech()=runTest {
        val spoken=mutableListOf<String>();val notifier=UnlockAnnouncements(this,{ spoken.add(it) })
        notifier.record(DiscoveryResult(EntryStatus.CONFIRMED,true));notifier.cancel()
        advanceUntilIdle();assertTrue(spoken.isEmpty())
    }
}
