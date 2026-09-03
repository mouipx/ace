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
    fun validationRejectsUnsortedPointsAndWarnsAboutDecreasingGain() {
        val points = listOf(
            ProfileEditorEngine.LutPoint(0.0, 0.0),
            ProfileEditorEngine.LutPoint(100.0, 140.0),
            ProfileEditorEngine.LutPoint(90.0, 90.0)
        )

        val validation = ProfileEditorEngine.validate(points)

        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { "greater than" in it })
        assertTrue(validation.warnings.any { "decreases" in it })
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
}
