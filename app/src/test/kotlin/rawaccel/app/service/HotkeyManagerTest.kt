package rawaccel.app.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotkeyManagerTest {
    @Test
    fun deadEndHotkeyCycleMatchesPistolShotgunSniperLoadout() {
        assertEquals(
            listOf(
                "PISTOL",
                "PISTOL + SHOTGUN",
                "SNIPER + SHOTGUN",
                "ZAPPER + ELDER GUN + GOLD DIGGER",
                "SNIPER FALLBACK (3-SLOT)"
            ),
            deadEndHotkeyPresetCycle.map { it.label }
        )
        assertFalse(deadEndHotkeyPresetCycle.any { it.label == "RIFLE" })
    }

    @Test
    fun sniperShotgunCycleStepKeepsVerticalAimCalmerThanHorizontalTurns() {
        val combo = deadEndHotkeyPresetCycle.single { it.label == "SNIPER + SHOTGUN" }

        assertEquals(false, combo.whole)
        assertTrue(combo.verticalStartIn > combo.startIn)
        assertTrue(combo.verticalPeak < combo.peak)
        assertEquals(ProfileEditorEngine.CurveShape.SOFT_START, combo.verticalShape)
    }

    @Test
    fun lateWeaponCycleIncludesThreeSlotFallback() {
        val utility = deadEndHotkeyPresetCycle.single { it.label == "ZAPPER + ELDER GUN + GOLD DIGGER" }
        val fallback = deadEndHotkeyPresetCycle.single { it.label == "SNIPER FALLBACK (3-SLOT)" }

        assertFalse(utility.whole == true)
        assertEquals(true, fallback.whole)
        assertEquals(1.00, fallback.yx)
        assertTrue(fallback.startIn > utility.startIn)
    }

    @Test
    fun hotkeyCycleLutsAreMonotonic() {
        deadEndHotkeyPresetCycle.forEach { preset ->
            val horizontal = ProfileEditorEngine.generate(preset.startIn, preset.endIn, preset.peak, preset.shape)
            assertTrue(
                ProfileEditorEngine.validate(horizontal).isValid,
                "Horizontal cycle preset ${preset.label} should be valid"
            )

            val vertical = ProfileEditorEngine.generate(
                preset.verticalStartIn,
                preset.verticalEndIn,
                preset.verticalPeak,
                preset.verticalShape
            )
            assertTrue(
                ProfileEditorEngine.validate(vertical).isValid,
                "Vertical cycle preset ${preset.label} should be valid"
            )
        }
    }
}
