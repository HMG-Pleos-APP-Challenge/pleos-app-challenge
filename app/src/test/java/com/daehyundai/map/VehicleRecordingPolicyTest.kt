package com.daehyundai.map
import com.daehyundai.map.feature.exploration.tracking.*
import org.junit.Assert.*
import org.junit.Test
class VehicleRecordingPolicyTest {
    @Test fun automaticRecordingWaitsForTrustedOperatingState() {
        assertFalse(shouldRecord(true,VehicleInteraction.UNKNOWN))
        assertFalse(shouldRecord(true,VehicleInteraction.UNRESTRICTED))
        assertTrue(shouldRecord(true,VehicleInteraction.RESTRICTED))
    }
    @Test fun explicitManualRecordingRemainsAvailableForLocationTesting() {
        VehicleInteraction.values().forEach { assertTrue(shouldRecord(false,it)) }
    }
}
