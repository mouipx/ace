package rawaccel.app.driver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rawaccel.app.model.AccelParams
import rawaccel.app.model.DeviceSettings
import rawaccel.app.model.Profile
import rawaccel.app.model.Settings

class DriverClientTest {
    @Test
    fun loadsBundledNativeBridgeOnWindows() {
        val os = System.getProperty("os.name")
        if (!os.contains("Windows", ignoreCase = true)) return

        val client = DriverClient()

        assertTrue(
            actual = client.isBridgeAvailable(),
            message = "Expected bundled rawaccel_bridge.dll to load. Error: ${client.bridgeError()}"
        )
    }

    @Test
    fun classifiesDriverVersionsAgainstBridgeLayout() {
        assertEquals(DriverClient.Compatibility.OLDER_THAN_SUPPORTED,
            DriverClient.classifyCompatibility("v1.6.9"))
        assertEquals(DriverClient.Compatibility.SUPPORTED,
            DriverClient.classifyCompatibility("v1.7.0"))
        assertEquals(DriverClient.Compatibility.NEWER_THAN_BRIDGE,
            DriverClient.classifyCompatibility("v1.7.1"))
        assertEquals(DriverClient.Compatibility.UNAVAILABLE,
            DriverClient.classifyCompatibility("unknown"))
        assertEquals(DriverClient.Compatibility.UNAVAILABLE,
            DriverClient.classifyCompatibility("v1.7"))
        assertEquals(DriverClient.Compatibility.UNAVAILABLE,
            DriverClient.classifyCompatibility("v1.x.0"))
    }

    @Test
    fun validatesProfilesWithoutRequiringTheDriver() {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return

        val client = DriverClient()
        val valid = Settings(
            profiles = listOf(
                Profile(
                    name = "offline-validation",
                    accelX = AccelParams(mode = "lut", data = listOf(0.0, 0.0, 10.0, 11.0))
                )
            )
        )

        assertTrue(client.validate(valid).isSuccess)
    }

    @Test
    fun rejectsMalformedProfilesWithoutTouchingTheDriver() {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return

        val client = DriverClient()
        val invalid = Settings(
            profiles = listOf(
                Profile(
                    name = "invalid",
                    accelX = AccelParams(mode = "lut", data = listOf(0.0, 0.0, 10.0, 11.0, 5.0, 6.0))
                )
            )
        )

        assertTrue(client.validate(invalid).isFailure)
    }

    @Test
    fun rejectsNonFiniteValuesOffline() {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return

        val client = DriverClient()
        val invalid = Settings(
            profiles = listOf(
                Profile(
                    name = "non-finite",
                    accelX = AccelParams(mode = "lut", data = listOf(0.0, 0.0, Double.NaN, 1.0))
                )
            )
        )

        assertTrue(client.validate(invalid).isFailure)
    }

    @Test
    fun rejectsDuplicateDeviceMappingsOffline() {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return

        val client = DriverClient()
        val profile = Profile(
            name = "duplicate-device",
            accelX = AccelParams(mode = "lut", data = listOf(0.0, 0.0, 10.0, 11.0))
        )
        val invalid = Settings(
            profiles = listOf(profile),
            devices = listOf(
                DeviceSettings(name = "mouse-a", profile = profile.name, id = "HID\\A"),
                DeviceSettings(name = "mouse-b", profile = profile.name, id = "HID\\A")
            )
        )

        assertTrue(client.validate(invalid).isFailure)
    }
}
