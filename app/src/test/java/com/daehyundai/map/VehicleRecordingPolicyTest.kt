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

    @Test fun releaseReplayToolsOnlyAcceptKnownPleosEmulator() {
        assertTrue(isVerifiedPleosEmulator("ranchu","Pleos/crp_avd/crp_avd_x86_64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys"))
        assertFalse(isVerifiedPleosEmulator("vehicle","Pleos/crp_avd/release_connect_v2.1.0:"))
        assertFalse(isVerifiedPleosEmulator("ranchu","google/sdk/emulator:14/test"))
    }

    @Test fun vehicleRestrictionsGateBrowsing() {
        assertFalse(canBrowseDetails(VehicleInteraction.UNKNOWN,false))
        assertFalse(canBrowseDetails(VehicleInteraction.RESTRICTED,false))
        assertTrue(canBrowseDetails(VehicleInteraction.UNRESTRICTED,false))
        assertTrue(canBrowseDetails(VehicleInteraction.UNKNOWN,true))
    }
}
