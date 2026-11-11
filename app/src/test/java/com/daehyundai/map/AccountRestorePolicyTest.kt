package com.daehyundai.map

import com.daehyundai.map.feature.profile.canRestoreAccount
import org.junit.Assert.*
import org.junit.Test

class AccountRestorePolicyTest {
    @Test fun onlyEmptyInstallationsCanRestore() {
        assertTrue(canRestoreAccount(false))
        assertFalse(canRestoreAccount(true))
    }
}
