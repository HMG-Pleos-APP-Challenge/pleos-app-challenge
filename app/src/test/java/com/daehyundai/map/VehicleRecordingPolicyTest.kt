package com.daehyundai.map

import com.daehyundai.map.feature.exploration.tracking.*
import org.junit.Assert.*
import org.junit.Test

class VehicleRecordingPolicyTest {
    @Test fun vehicleRestrictionsGateBrowsing() {
        assertFalse(canBrowseDetails(VehicleInteraction.UNKNOWN,false))
        assertFalse(canBrowseDetails(VehicleInteraction.RESTRICTED,false))
        assertTrue(canBrowseDetails(VehicleInteraction.UNRESTRICTED,false))
        assertTrue(canBrowseDetails(VehicleInteraction.UNKNOWN,true))
    }
}
