package rawaccel.app.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsSerializationTest {
    @Test
    fun bothAxisLutsRoundTripWithoutUiOnlyFields() {
        val mapper = jacksonObjectMapper()
        val horizontal = AccelParams(mode = "lut", data = listOf(0.0, 0.0, 50.0, 60.0))
        val vertical = AccelParams(mode = "lut", data = listOf(0.0, 0.0, 50.0, 55.0))
        val settings = Settings(
            profiles = listOf(
                Profile(
                    name = "dual-axis",
                    accelX = horizontal,
                    accelY = vertical,
                    speed = SpeedParams(whole = false)
                )
            )
        )

        val json = mapper.writeValueAsString(settings)
        val restored = mapper.readValue<Settings>(json).profiles.single()

        assertFalse("isLut" in json)
        assertFalse(restored.speed.whole)
        assertEquals(horizontal.data, restored.accelX.data)
        assertEquals(vertical.data, restored.accelY.data)
    }
}
