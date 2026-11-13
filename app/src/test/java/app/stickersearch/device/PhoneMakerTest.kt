package app.stickersearch.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMakerTest {
    @Test fun manufacturerStringsMap() {
        assertEquals(PhoneMaker.HONOR, PhoneMaker.of("HONOR"))
        assertEquals(PhoneMaker.HONOR, PhoneMaker.of("hihonor"))
        assertEquals(PhoneMaker.HUAWEI, PhoneMaker.of("HUAWEI"))
        assertEquals(PhoneMaker.XIAOMI, PhoneMaker.of("Xiaomi"))
        assertEquals(PhoneMaker.XIAOMI, PhoneMaker.of("POCO"))
        assertEquals(PhoneMaker.SAMSUNG, PhoneMaker.of("samsung"))
        assertEquals(PhoneMaker.ONEPLUS, PhoneMaker.of("OnePlus"))
        assertEquals(PhoneMaker.OPPO, PhoneMaker.of("realme"))
        assertEquals(PhoneMaker.VIVO, PhoneMaker.of("vivo"))
        assertEquals(PhoneMaker.ASUS, PhoneMaker.of("asus"))
    }

    @Test fun unknownMakerHasNoRow() {
        assertNull(PhoneMaker.of("Google"))
        assertNull(PhoneMaker.of("motorola"))
    }

    @Test fun honorStepsMatchMagicOs() {
        val s = PhoneMaker.HONOR.steps
        for (w in listOf("App launch", "Power usage details", "Manage automatically", "Auto-launch", "Secondary launch", "Run in background")) {
            assertTrue(w, s.contains(w))
        }
        assertTrue(PhoneMaker.HONOR.screens.first().first == "com.hihonor.systemmanager")
    }
}
