package app.stickersearch.device

import app.stickersearch.device.BatteryAccess.Level
import app.stickersearch.device.BatteryAccess.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryAccessTest {
    @Test fun restrictedWinsOverEverything() {
        assertEquals(Level.RESTRICTED, BatteryAccess.level(State(dozeExempt = true, backgroundRestricted = true)))
        assertEquals(Level.RESTRICTED, BatteryAccess.level(State(dozeExempt = true, restrictedBucket = true)))
        assertEquals(Level.RESTRICTED, BatteryAccess.level(State(backgroundRestricted = true)))
    }

    @Test fun dozeExemptAloneIsUnrestricted() =
        assertEquals(Level.UNRESTRICTED, BatteryAccess.level(State(dozeExempt = true)))

    @Test fun nothingIsOptimised() = assertEquals(Level.OPTIMISED, BatteryAccess.level(State()))

    @Test fun android12AndNewerSayUnrestricted() {
        for (sdk in 31..36) {
            val s = BatteryAccess.steps(sdk, "Google")
            assertTrue(s, s.contains("App battery usage") && s.contains("Unrestricted"))
            assertFalse(s, s.contains("Don't optimise"))
        }
    }

    @Test fun android9To11UseBackgroundRestrictionAndOptimisationList() {
        for (sdk in 28..30) {
            val s = BatteryAccess.steps(sdk, "Google")
            assertTrue(s, s.contains("Background restriction") && s.contains("Don't optimise"))
        }
    }

    @Test fun makerTipIsAppended() {
        assertTrue(BatteryAccess.steps(34, "samsung").contains("Never sleeping apps"))
        assertTrue(BatteryAccess.steps(29, "Xiaomi").contains("Autostart"))
        assertTrue(BatteryAccess.steps(34, "SomeBrand").contains("auto-start"))
    }
}
