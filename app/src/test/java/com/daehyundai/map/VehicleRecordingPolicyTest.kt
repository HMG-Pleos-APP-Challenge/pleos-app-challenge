package com.daehyundai.map

import com.daehyundai.map.feature.exploration.tracking.*
import org.junit.Assert.*
import org.junit.Test

class VehicleRecordingPolicyTest {
    @Test fun conquestRetainsItsVehicleRecordingPolicy() {
        assertFalse(shouldRecord(true,VehicleInteraction.UNKNOWN))
        assertFalse(shouldRecord(true,VehicleInteraction.UNRESTRICTED))
        assertTrue(shouldRecord(true,VehicleInteraction.RESTRICTED))
        VehicleInteraction.values().forEach { assertTrue(shouldRecord(false,it)) }
    }

    @Test fun vehicleRestrictionsGateBrowsing() {
        assertFalse(canBrowseDetails(VehicleInteraction.UNKNOWN,false))
        assertFalse(canBrowseDetails(VehicleInteraction.RESTRICTED,false))
        assertTrue(canBrowseDetails(VehicleInteraction.UNRESTRICTED,false))
        assertTrue(canBrowseDetails(VehicleInteraction.UNKNOWN,true))
    }
}
