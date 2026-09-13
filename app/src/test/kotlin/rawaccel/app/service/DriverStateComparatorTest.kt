package rawaccel.app.service

import rawaccel.app.model.AccelParams
import rawaccel.app.model.DeviceConfig
import rawaccel.app.model.DeviceSettings
import rawaccel.app.model.PresetStage
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

    @Test
    fun bindsPortableProfileToConnectedMouseWithoutChangingCurveOrDpi() {
        val original = settingsWithData(listOf(55.0, 55.04, 2867.2, 4014.08)).copy(
            defaultDeviceConfig = DeviceConfig(dpi = 1200, pollingRate = 1000),
            devices = listOf(DeviceSettings(name = "old", id = "HID\\VID_OLD&PID_OLD"))
        )
        val report = DeviceInspector.Report(
            defaultDevicesDisabled = false,
            targets = emptyList(),
            connectedMice = listOf(
                DeviceInspector.ConnectedMouse(
                    "Logitech USB Receiver",
                    "HID\\VID_046D&PID_C539&MI_01&Col01"
                )
            )
        )

        val rebound = DeviceInspector.bindToConnectedMouse(original, report)

        assertTrue(rebound.defaultDeviceConfig.disable)
        assertTrue(rebound.devices.single().id == "HID\\VID_046D&PID_C539&MI_01&Col01")
        assertTrue(rebound.devices.single().config == original.devices.single().config)
        assertTrue(rebound.profiles == original.profiles)
    }

    @Test
    fun appOnlyPresetCycleFieldIsIgnoredSoWatchdogDoesNotLoop() {
        // The in-memory profile carries an app-only "Preset cycle" section;
        // the driver read-back never does. The comparator must treat the two
        // as equivalent, or the watchdog re-applies forever.
        val withCycle = settingsWithData(listOf(50.0, 50.0, 100.0, 125.0)).copy(
            profiles = listOf(
                settingsWithData(listOf(50.0, 50.0, 100.0, 125.0)).profiles.single().copy(
                    presetCycle = listOf(
                        PresetStage(label = "SNIPER + SHOTGUN", startIn = 5.0, endIn = 70.0, peak = 2.7)
                    )
                )
            )
        )
        val driverReadback = settingsWithData(listOf(50.0, 50.0, 100.0, 125.0))

        assertTrue(DriverStateComparator.equivalent(withCycle, driverReadback))
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
