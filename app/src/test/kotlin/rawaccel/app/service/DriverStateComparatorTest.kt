package rawaccel.app.service

import rawaccel.app.model.AccelParams
import rawaccel.app.model.Profile
import rawaccel.app.model.Settings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DriverStateComparatorTest {
    @Test
    fun acceptsNativeFloatRoundingInLutData() {
        val expected = settingsWithData(listOf(55.0, 55.04, 2867.2, 4014.08))
        val actual = settingsWithData(
            listOf(
                55.0f.toDouble(),
                55.04f.toDouble(),
                2867.2f.toDouble(),
                4014.08f.toDouble()
            )
        )

        assertTrue(DriverStateComparator.equivalent(expected, actual))
    }

    @Test
    fun detectsRealLutDrift() {
        val expected = settingsWithData(listOf(50.0, 50.0, 100.0, 125.0))
        val actual = settingsWithData(listOf(50.0, 50.0, 100.0, 110.0))

        assertFalse(DriverStateComparator.equivalent(expected, actual))
        assertNotNull(DriverStateComparator.firstDifference(expected, actual))
    }

    private fun settingsWithData(data: List<Double>) = Settings(
        profiles = listOf(
            Profile(
                name = "test",
                accelX = AccelParams(mode = "lut", gain = true, data = data)
            )
        )
    )
}
