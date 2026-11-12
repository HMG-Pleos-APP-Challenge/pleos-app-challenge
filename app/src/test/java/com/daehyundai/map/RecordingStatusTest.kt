package com.daehyundai.map

import com.daehyundai.map.feature.exploration.tracking.recordingStatus
import org.junit.Assert.*
import org.junit.Test

class RecordingStatusTest {
    @Test fun staleOrMissingSignalNeverClaimsActiveRecording() {
        assertTrue(recordingStatus(true,null,"",100_000).contains("기다리고"))
        assertTrue(recordingStatus(true,69_999,"",100_000).contains("기다리고"))
        assertTrue(recordingStatus(true,105_001,"",100_000).contains("기다리고"))
        assertTrue(recordingStatus(true,70_000,"",100_000).startsWith("탐험 기록 중"))
    }
    @Test fun stoppedServiceAndStorageFaultKeepActionableReason() {
        assertEquals("위치 권한 필요",recordingStatus(false,99_000,"위치 권한 필요",100_000))
        assertTrue(recordingStatus(true,99_000,"탐험 저장 오류 · 저장 공간 확인",100_000).contains("저장 오류"))
    }
}
