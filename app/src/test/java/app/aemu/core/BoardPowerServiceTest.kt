package app.aemu.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-14: on the Samsung a30 (LineageOS 18.1) the board's android.hardware.power@1.0-service.universal7904 registered IPower/default
 * over the engine's android.hardware.power@1.0-service, failed on ILineagePower (not in the VINTF manifest) and exited, and the
 * engine's replaced instance aborted: nothing served IPower and system_server waited in StartPowerManager.
 */
class BoardPowerServiceTest {
    private val re = TreeFixer.BOARD_POWER_SERVICE

    @Test fun boardsHidlPowerServiceIsSkipped() {
        assertTrue(re.matches("android.hardware.power@1.0-service.universal7904"))
        assertTrue(re.matches("android.hardware.power@1.0-service.exynos"))
    }

    @Test fun engineOwnPowerServicesAreNotSkipped() {
        assertFalse(re.matches("android.hardware.power@1.0-service"))
        assertFalse(re.matches("android.hardware.power-service.aemu"))
    }

    @Test fun earlierBoardsStillMatch() {
        assertTrue(re.matches("android.hardware.power-service-mediatek"))
        assertTrue(re.matches("vendor.mediatek.hardware.mtkpower-service"))
    }
}
