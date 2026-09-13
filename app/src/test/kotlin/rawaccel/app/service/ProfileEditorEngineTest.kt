package rawaccel.app.service

import rawaccel.app.model.AccelParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileEditorEngineTest {
    @Test
    fun generatedSmoothCurveIsMonotonicAndReachesRequestedPeak() {
        val points = ProfileEditorEngine.generate(50.0, 250.0, 1.4)
        val validation = ProfileEditorEngine.validate(points)

        assertTrue(validation.isValid, validation.errors.joinToString())
        assertEquals(1.0, points.first { it.input == 50.0 }.gain, 1e-9)
        assertEquals(1.4, points.last().gain, 1e-9)
    }

    @Test
    fun validationRejectsUnsortedPointsAndWarnsAboutMixedDirectionGains() {
        val unsorted = listOf(
            ProfileEditorEngine.LutPoint(0.0, 0.0),
            ProfileEditorEngine.LutPoint(100.0, 140.0),
            ProfileEditorEngine.LutPoint(90.0, 90.0)
        )

        val unsortedValidation = ProfileEditorEngine.validate(unsorted)
        assertFalse(unsortedValidation.isValid)
        assertTrue(unsortedValidation.errors.any { "greater than" in it })

        // A sorted curve that rises then falls (a valley) is the real
        // "feels off" defect and must be warned about.
        val mixed = listOf(
            ProfileEditorEngine.LutPoint(0.0, 0.0),
            ProfileEditorEngine.LutPoint(50.0, 50.0),
            ProfileEditorEngine.LutPoint(100.0, 140.0),
            ProfileEditorEngine.LutPoint(150.0, 150.0),
            ProfileEditorEngine.LutPoint(200.0, 300.0)
        )

        val mixedValidation = ProfileEditorEngine.validate(mixed)
        assertTrue(mixedValidation.isValid)
        assertTrue(mixedValidation.warnings.any { "decreases" in it })
    }

    @Test
    fun repairGainDipsPreservesInputsAndProducesMonotonicGain() {
        val original = listOf(
            ProfileEditorEngine.LutPoint(0.0, 0.0),
            ProfileEditorEngine.LutPoint(50.0, 50.0),
            ProfileEditorEngine.LutPoint(100.0, 140.0),
            ProfileEditorEngine.LutPoint(150.0, 180.0),
            ProfileEditorEngine.LutPoint(200.0, 300.0)
        )

        val repaired = ProfileEditorEngine.repairGainDips(original)

        assertEquals(original.map { it.input }, repaired.map { it.input })
        assertTrue(ProfileEditorEngine.validate(repaired).errors.isEmpty())
        assertTrue(ProfileEditorEngine.validate(repaired).warnings.none { "decreases" in it })
        assertEquals(1.4, repaired[3].gain, 1e-9)
    }

    @Test
    fun flatDataRoundTripsThroughPointModel() {
        val accel = AccelParams(mode = "lut", data = listOf(0.0, 0.0, 50.0, 50.0, 100.0, 125.0))
        val points = ProfileEditorEngine.fromAccel(accel)

        assertEquals(accel.data, ProfileEditorEngine.flatten(points))
    }

    @Test
    fun targetLockDecaysFromFloorToCeilAndStaysMonotonicDown() {
        val points = ProfileEditorEngine.generateTargetLock(3.0, 45.0, 2.2, 1.5)

        // Starts at the floor gain, decays, and settles on the ceiling.
        assertTrue(points.any { it.input == 3.0 })
        assertEquals(2.2, points.first { it.input == 3.0 }.gain, 1e-9)
        assertEquals(1.5, points.last().gain, 1e-9)

        // Gain must only ever decrease (never bump back up). The (0,0) origin
        // has no defined gain, so direction starts at the first real point.
        val gains = points.drop(1).map { it.gain }
        for (i in 1 until gains.size) {
            assertTrue(gains[i] <= gains[i - 1] + 1e-9, "target lock must be monotonic non-increasing")
        }
    }

    @Test
    fun targetLockIsNotFlaggedAsGainDips() {
        val points = ProfileEditorEngine.generateTargetLock(3.0, 45.0, 2.2, 1.5)
        val validation = ProfileEditorEngine.validate(points)

        assertTrue(validation.isValid, validation.errors.joinToString())
        assertTrue(validation.warnings.none { "decreases" in it }, "pure target lock is intended")
    }

    @Test
    fun mixedDirectionCurveStillFlaggedAsDips() {
        // Falls then rises — the classic "feels off" valley, must be warned.
        val points = listOf(
            ProfileEditorEngine.LutPoint(0.0, 0.0),
            ProfileEditorEngine.LutPoint(5.0, 10.0),
            ProfileEditorEngine.LutPoint(20.0, 30.6),
            ProfileEditorEngine.LutPoint(50.0, 52.0),
            ProfileEditorEngine.LutPoint(100.0, 108.0),
            ProfileEditorEngine.LutPoint(200.0, 230.0)
        )
        val validation = ProfileEditorEngine.validate(points)

        assertTrue(validation.warnings.any { "decreases" in it })
    }

    @Test
    fun generateForDispatchesTargetLockAndAccelerators() {
        val lock = ProfileEditorEngine.generateFor(3.0, 45.0, 2.2, ProfileEditorEngine.CurveShape.TARGET_LOCK, 1.5)
        assertEquals(ProfileEditorEngine.generateTargetLock(3.0, 45.0, 2.2, 1.5), lock)

        val accel = ProfileEditorEngine.generateFor(10.0, 70.0, 2.7, ProfileEditorEngine.CurveShape.SMOOTH)
        assertEquals(ProfileEditorEngine.generate(10.0, 70.0, 2.7, ProfileEditorEngine.CurveShape.SMOOTH), accel)
    }

    @Test
    fun targetLockRejectsInvertedGains() {
        val thrown = runCatching {
            ProfileEditorEngine.generateTargetLock(3.0, 45.0, 1.2, 1.8)
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }
}
