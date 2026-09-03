package rawaccel.app.service

import rawaccel.app.model.AccelParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurveEngineTest {
    @Test
    fun lookupGainInterpolatesVelocityMode() {
        val points = listOf(10.0, 10.0, 20.0, 30.0)

        val gain = CurveEngine.lookupGain(points, velocity = true, x = 15.0)

        assertEquals(20.0 / 15.0, gain, absoluteTolerance = 1e-9)
    }

    @Test
    fun monotonicityIssuesDetectSensitivityDips() {
        val accel = AccelParams(
            mode = "lut",
            gain = true,
            data = listOf(10.0, 20.0, 20.0, 10.0, 30.0, 45.0)
        )

        assertTrue(CurveEngine.monotonicityIssues(accel).isNotEmpty())
    }

    @Test
    fun monotonicityIssuesAcceptIncreasingSensitivity() {
        val accel = AccelParams(
            mode = "lut",
            gain = true,
            data = listOf(10.0, 10.0, 20.0, 30.0, 30.0, 60.0)
        )

        assertTrue(CurveEngine.monotonicityIssues(accel).isEmpty())
    }
}
