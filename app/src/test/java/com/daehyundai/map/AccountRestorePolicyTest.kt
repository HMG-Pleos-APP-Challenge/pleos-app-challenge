package com.daehyundai.map
import com.daehyundai.map.feature.profile.canRestoreAccount
import org.junit.Assert.*
import org.junit.Test
class AccountRestorePolicyTest {
    @Test fun existingAchievementsOrRunningRecordingBlockAccountSwitch() {
        assertTrue(canRestoreAccount(false,false));assertFalse(canRestoreAccount(true,false))
        assertFalse(canRestoreAccount(false,true));assertFalse(canRestoreAccount(true,true))
    }
}
