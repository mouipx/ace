package rawaccel.app.service

import org.junit.jupiter.api.Test
import rawaccel.app.model.*
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SensitivityCalculatorTest {

    @Test
    fun `cm per 360 decreases as DPI increases`() {
        val low = SensitivityCalculator.cmPer360(400.0)
        val high = SensitivityCalculator.cmPer360(3200.0)
        assertTrue(low > high, "higher DPI should produce smaller cm/360")
    }

    @Test
    fun `cm per 360 at higher gain is smaller`() {
        // A profile with 1.4x peak should give smaller cm/360 at peak
        val accel = AccelParams(
            mode = "lut",
            gain = true,
            data = ProfileEditorEngine.flatten(ProfileEditorEngine.generate(50.0, 250.0, 1.4))
        )
        val profile = Profile(
            name = "test",
            accelX = accel,
            accelY = AccelParams(mode = "noaccel"),
            outputDpi = 1200.0
        )
        val device = DeviceSettings(name = "test", profile = "test",
            id = "HID\\VID_0000&PID_0000",
            config = DeviceConfig(dpi = 1200, pollingRate = 1000))
        val settings = Settings(
            profiles = listOf(profile),
            devices = listOf(device),
            defaultDeviceConfig = DeviceConfig(dpi = 1200, pollingRate = 1000)
        )
        val m = SensitivityCalculator.compute(settings)
        // peak cm/360 should be less than base
        assertTrue(m.peakCmPer360 < m.baseCmPer360, "peak cm/360 should be smaller than base")
        assertTrue(m.cmAt120 < m.baseCmPer360, "at high hand speed, cm/360 should be lower due to accel")
        // gain at 120 in/s should be > 1
        assertTrue(m.gain120 > 1.0, "gain at 120 in/s should be > 1 for this curve")
        // peak gain ~1.4
        assertTrue(abs(m.peakGain - 1.4) < 0.05, "peak gain should be ~1.4")
    }

    @Test
    fun `minecraft cm360 increases as sensitivity decreases`() {
        val high = SensitivityCalculator.minecraftCm360(800.0, 100.0)
        val low = SensitivityCalculator.minecraftCm360(800.0, 30.0)
        assertTrue(low > high, "lower sensitivity should give larger cm/360")
    }

    @Test
    fun `minecraft default sens matches community reference`() {
        // Widely-cited reference: 800 DPI / 100% in-game ≈ 32.6 cm/360
        val cm = SensitivityCalculator.minecraftCm360(800.0, 100.0)
        assertTrue(abs(cm - 32.6) < 1.0, "expected ~32.6 cm/360 at 800DPI/100%, got $cm")
    }

    @Test
    fun `minecraft sens roundtrip is consistent`() {
        val cm = 30.0
        val dpi = 800.0
        val sens = SensitivityCalculator.minecraftSensForCm360(dpi, cm)
        val cmBack = SensitivityCalculator.minecraftCm360(dpi, sens)
        // Forward + inverse are mathematically exact (same constant), so within floating-point tolerance
        assertTrue(abs(cmBack - cm) < 0.5, "roundtrip should be within 0.5cm, got $cmBack vs $cm (sens=$sens)")
    }

    @Test
    fun `format cm handles ranges`() {
        assertEquals("250", SensitivityCalculator.formatCm(250.0))
        assertEquals("25.5", SensitivityCalculator.formatCm(25.5))
        assertEquals("2.54", SensitivityCalculator.formatCm(2.54))
    }
}
