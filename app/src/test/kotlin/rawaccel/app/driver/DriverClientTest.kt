package rawaccel.app.driver

import kotlin.test.Test
import kotlin.test.assertTrue

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
}
