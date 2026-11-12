package com.kukreja.stickersearch.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapsTest {
    @Test
    fun `describing needs about 6 GB and not a low-RAM phone`() {
        assertTrue(DeviceCaps.phoneCanDescribe(7.4, lowRamDevice = false))   // an "8 GB" phone reports ~7.4
        assertTrue(DeviceCaps.phoneCanDescribe(5.6, lowRamDevice = false))   // a "6 GB" phone reports ~5.6
        assertFalse(DeviceCaps.phoneCanDescribe(3.7, lowRamDevice = false))  // "4 GB"
        assertFalse(DeviceCaps.phoneCanDescribe(11.0, lowRamDevice = true))
    }

    @Test
    fun `each maker gets its own battery tip, anything else the generic one`() {
        assertTrue(DeviceCaps.batteryTip("Xiaomi").contains("Autostart"))
        assertTrue(DeviceCaps.batteryTip("POCO").contains("Autostart"))
        assertTrue(DeviceCaps.batteryTip("samsung").contains("Never sleeping"))
        assertTrue(DeviceCaps.batteryTip("HONOR").contains("Manage manually"))
        assertTrue(DeviceCaps.batteryTip("OnePlus").contains("Auto-launch"))
        assertTrue(DeviceCaps.batteryTip("vivo").contains("background power"))
        assertTrue(DeviceCaps.batteryTip("Google").contains("auto-start"))
    }
}
