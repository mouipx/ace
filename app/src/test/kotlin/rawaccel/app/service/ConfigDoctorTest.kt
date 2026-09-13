package rawaccel.app.service

import org.junit.jupiter.api.Test
import rawaccel.app.model.*
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigDoctorTest {

    private fun goodSettings(): Settings {
        val accel = AccelParams(
            mode = "lut",
            gain = true,
            data = ProfileEditorEngine.flatten(ProfileEditorEngine.generate(50.0, 250.0, 1.4))
        )
        val profile = Profile(
            name = "test",
            accelX = accel,
            accelY = AccelParams(mode = "noaccel"),
            outputDpi = 1200.0,
            yxRatio = 0.9
        )
        val device = DeviceSettings(
            name = "Test Mouse",
            profile = "test",
            id = "HID\\VID_0000&PID_0000",
            config = DeviceConfig(dpi = 1200, pollingRate = 1000)
        )
        return Settings(
            profiles = listOf(profile),
            devices = listOf(device),
            defaultDeviceConfig = DeviceConfig(disable = true, dpi = 1200, pollingRate = 1000)
        )
    }

    @Test
    fun `good config scores high`() {
        val r = ConfigDoctor.diagnose(goodSettings())
        assertTrue(r.criticals.isEmpty(), "good config should have no criticals, got ${r.criticals}")
        assertTrue(r.score >= 80, "good config should score >=80, got ${r.score}")
    }

    @Test
    fun `no profiles is critical`() {
        val s = goodSettings().copy(profiles = emptyList())
        val r = ConfigDoctor.diagnose(s)
        assertTrue(r.hasErrors)
        assertTrue(r.criticals.any { it.code == "NO_PROFILES" })
    }

    @Test
    fun `dpi mismatch in profile name triggers warning`() {
        val s = goodSettings().let {
            it.copy(profiles = it.profiles.map { p -> p.copy(name = "1200 DPI Dead End") },
                defaultDeviceConfig = it.defaultDeviceConfig.copy(dpi = 6400),
                devices = it.devices.map { d -> d.copy(config = d.config.copy(dpi = 6400)) })
        }
        val r = ConfigDoctor.diagnose(s)
        assertTrue(r.warnings.any { it.code == "DPI_NAME_MISMATCH" },
            "should warn about DPI mismatch, got ${r.warnings.map { it.code }}")
    }

    @Test
    fun `non monotonic lut is warned`() {
        // Create a LUT with a dip (higher output then lower output at higher input)
        val data = listOf(
            0.0, 0.0,
            50.0, 50.0,
            100.0, 130.0,
            150.0, 170.0,  // gain 1.13
            200.0, 210.0,  // gain 1.05 <-- dip!
            250.0, 350.0,  // gain 1.4
            500.0, 700.0
        )
        val badAccel = AccelParams(mode = "lut", gain = true, data = data)
        val s = goodSettings().copy(
            profiles = goodSettings().profiles.map { it.copy(accelX = badAccel) }
        )
        val r = ConfigDoctor.diagnose(s)
        assertTrue(r.warnings.any { it.code == "LUT_DIP" },
            "should detect gain dip, got ${r.warnings.map { it.code }}")
    }

    @Test
    fun `pure target lock lut is not warned`() {
        // A sniper target-lock vertical (high low-speed gain decaying to a
        // lower ceiling) is an intended shape, not a dip.
        val lockAccel = AccelParams(
            mode = "lut",
            gain = true,
            data = ProfileEditorEngine.flatten(
                ProfileEditorEngine.generateTargetLock(3.0, 45.0, 2.2, 1.5)
            )
        )
        val s = goodSettings().copy(
            profiles = goodSettings().profiles.map {
                it.copy(accelY = lockAccel, accelX = lockAccel, speed = SpeedParams(whole = false))
            }
        )
        val r = ConfigDoctor.diagnose(s)
        assertTrue(r.warnings.none { it.code == "LUT_DIP" },
            "pure target lock must not be flagged as a dip, got ${r.warnings.map { it.code }}")
        assertTrue(r.findings.any { it.code == "LUT_MONO" },
            "target lock should pass the monotonic check")
    }

    @Test
    fun `dead vertical lut warned in whole mode`() {
        val accel = AccelParams(
            mode = "lut",
            gain = true,
            data = ProfileEditorEngine.flatten(ProfileEditorEngine.generate(50.0, 250.0, 1.4))
        )
        val s = goodSettings().copy(
            profiles = goodSettings().profiles.map {
                it.copy(accelY = accel, speed = SpeedParams(whole = true))
            }
        )
        val r = ConfigDoctor.diagnose(s)
        assertTrue(r.warnings.any { it.code == "DEAD_VERT_LUT" },
            "should warn about dead vertical LUT, got ${r.warnings.map { it.code }}")
    }

    @Test
    fun `high smoothing triggers warning`() {
        val s = goodSettings().copy(
            profiles = goodSettings().profiles.map {
                it.copy(speed = SpeedParams(inputSmoothHalflife = 10.0))
            }
        )
        val r = ConfigDoctor.diagnose(s)
        assertTrue(r.warnings.any { it.code == "SMOOTH_HIGH" })
    }

    @Test
    fun `duplicate profile names are critical`() {
        val s = goodSettings().let {
            it.copy(profiles = it.profiles + it.profiles)
        }
        val r = ConfigDoctor.diagnose(s)
        assertTrue(r.criticals.any { it.code == "DUP_NAMES" })
    }

    @Test
    fun `zero dpi is critical`() {
        val s = goodSettings().let {
            it.copy(defaultDeviceConfig = it.defaultDeviceConfig.copy(dpi = 0),
                devices = it.devices.map { d -> d.copy(config = d.config.copy(dpi = 0)) })
        }
        val r = ConfigDoctor.diagnose(s)
        assertTrue(r.criticals.any { it.code == "DPI_ZERO" })
    }
}
