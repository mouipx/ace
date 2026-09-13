package rawaccel.app.service

import rawaccel.app.model.Profile
import rawaccel.app.model.PresetStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotkeyManagerTest {
    @Test
    fun deadEndHotkeyCycleMatchesPistolShotgunSniperLoadout() {
        assertEquals(
            deadEndWeaponStages,
            deadEndHotkeyPresetCycle.map { it.label }
        )
        assertFalse(deadEndHotkeyPresetCycle.any { it.label == "RIFLE" })
    }

    @Test
    fun sniperShotgunStepUsesTargetLockVerticalForHeadshots() {
        val combo = deadEndHotkeyPresetCycle.single { it.label == "SNIPER + SHOTGUN" }

        assertEquals(false, combo.whole)
        assertEquals(ProfileEditorEngine.CurveShape.TARGET_LOCK, combo.verticalShape)
        // The vertical headshot lock is a real boost at micro speed…
        assertTrue(combo.verticalPeak > 1.5, "sniper vertical floor should be a real headshot lock")
        // …and the vertical ceiling stays calmer than the horizontal turn ceiling.
        assertTrue(combo.verticalCeil < combo.peak, "vertical ceiling should stay calmer than horizontal")
        // The lock engages from the very lowest hand speeds.
        assertTrue(combo.verticalStartIn <= combo.startIn)
    }

    @Test
    fun sniperStagesCarryStrongerYawTurnsThanOldCycle() {
        // The old cycle did nothing below ~90 in/s and capped at 1.25x.
        // The new sniper stages must be working from low speeds with real reach.
        val sniper = deadEndHotkeyPresetCycle.single { it.label == "SNIPER + SHOTGUN" }
        assertTrue(sniper.startIn < 30.0, "sniper yaw ramp must start at low speed")
        assertTrue(sniper.peak >= 2.0, "sniper yaw ceiling must give real turn reach")
    }

    @Test
    fun lateWeaponCycleIncludesThreeSlotFallback() {
        val utility = deadEndHotkeyPresetCycle.single { it.label == "ZAPPER + ELDER GUN + GOLD DIGGER" }
        val fallback = deadEndHotkeyPresetCycle.single { it.label == "SNIPER FALLBACK (3-SLOT)" }

        assertFalse(utility.whole == true)
        assertEquals(false, fallback.whole)
        assertEquals(1.00, fallback.yx)
        assertEquals(ProfileEditorEngine.CurveShape.TARGET_LOCK, fallback.verticalShape)
        // The fallback is the pure-precision stage: the strongest headshot lock in the cycle.
        assertTrue(fallback.verticalPeak >= utility.verticalPeak)
    }

    @Test
    fun hotkeyCycleLutsAreValidAndTargetLockIsNotFlaggedAsDips() {
        deadEndHotkeyPresetCycle.forEach { preset ->
            val horizontal = ProfileEditorEngine.generateFor(
                preset.startIn, preset.endIn, preset.peak, preset.shape, preset.ceilGain
            )
            val hValidation = ProfileEditorEngine.validate(horizontal)
            assertTrue(
                hValidation.isValid,
                "Horizontal cycle preset ${preset.label} should be valid: ${hValidation.errors}"
            )

            val vertical = ProfileEditorEngine.generateFor(
                preset.verticalStartIn,
                preset.verticalEndIn,
                preset.verticalPeak,
                preset.verticalShape,
                preset.verticalCeil
            )
            val vValidation = ProfileEditorEngine.validate(vertical)
            assertTrue(
                vValidation.isValid,
                "Vertical cycle preset ${preset.label} should be valid: ${vValidation.errors}"
            )

            if (preset.verticalShape == ProfileEditorEngine.CurveShape.TARGET_LOCK) {
                assertTrue(
                    vValidation.warnings.none { "decreases" in it },
                    "Target-lock vertical for ${preset.label} must not be flagged as gain dips"
                )
            }
        }
    }

    @Test
    fun resolveCycleFallsBackToBuiltInWhenProfileHasNoCycle() {
        val plain = Profile(name = "plain")
        assertEquals(deadEndHotkeyPresetCycle, resolveCycle(plain))
        assertEquals(deadEndHotkeyPresetCycle, resolveCycle(null))
    }

    @Test
    fun resolveCyclePrefersProfileDefinedStages() {
        val profile = Profile(
            name = "custom",
            presetCycle = listOf(
                PresetStage(
                    label = "MY STAGE ONE",
                    startIn = 10.0,
                    endIn = 60.0,
                    peak = 2.0,
                    yx = 1.0,
                    shape = "smooth"
                ),
                PresetStage(
                    label = "MY STAGE TWO",
                    startIn = 5.0,
                    endIn = 70.0,
                    peak = 2.5,
                    yx = 1.0,
                    whole = false,
                    shape = "SMOOTH",
                    verticalStartIn = 3.0,
                    verticalEndIn = 45.0,
                    verticalPeak = 2.1,
                    verticalShape = "target_lock",
                    verticalCeil = 1.4
                )
            )
        )

        val cycle = resolveCycle(profile)

        assertEquals(listOf("MY STAGE ONE", "MY STAGE TWO"), cycle.map { it.label })
        assertEquals(ProfileEditorEngine.CurveShape.SMOOTH, cycle[0].shape)
        val second = cycle[1]
        assertEquals(ProfileEditorEngine.CurveShape.TARGET_LOCK, second.verticalShape)
        assertEquals(2.1, second.verticalPeak)
        assertEquals(1.4, second.verticalCeil)
        assertEquals(3.0, second.verticalStartIn)
    }

    @Test
    fun resolveCycleIgnoresInvalidStagesAndFallsBackWhenAllInvalid() {
        val onlyInvalid = Profile(
            name = "bad",
            presetCycle = listOf(
                PresetStage(label = "", startIn = 5.0, endIn = 50.0, peak = 2.0),
                PresetStage(label = "NO RANGE", startIn = 50.0, endIn = 50.0, peak = 2.0)
            )
        )
        assertEquals(deadEndHotkeyPresetCycle, resolveCycle(onlyInvalid))

        val mixed = Profile(
            name = "mixed",
            presetCycle = listOf(
                PresetStage(label = "GOOD", startIn = 10.0, endIn = 60.0, peak = 1.8),
                PresetStage(label = "BAD", startIn = 0.0, endIn = 60.0, peak = 1.8)
            )
        )
        assertEquals(listOf("GOOD"), resolveCycle(mixed).map { it.label })
    }
}
