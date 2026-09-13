package rawaccel.app.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun profileDefinedPresetCycleRoundTrips() {
        val mapper = jacksonObjectMapper()
        val settings = Settings(
            profiles = listOf(
                Profile(
                    name = "with-cycle",
                    presetCycle = listOf(
                        PresetStage(
                            label = "SNIPER + SHOTGUN",
                            startIn = 5.0,
                            endIn = 70.0,
                            peak = 2.7,
                            yx = 1.0,
                            whole = false,
                            shape = "SMOOTH",
                            verticalStartIn = 3.0,
                            verticalEndIn = 45.0,
                            verticalPeak = 2.2,
                            verticalShape = "TARGET_LOCK",
                            verticalCeil = 1.5
                        )
                    )
                )
            )
        )

        val json = mapper.writeValueAsString(settings)
        val restored = mapper.readValue<Settings>(json).profiles.single()

        assertEquals(1, restored.presetCycle.size)
        val stage = restored.presetCycle.single()
        assertEquals("SNIPER + SHOTGUN", stage.label)
        assertEquals(2.2, stage.verticalPeak)
        assertEquals("TARGET_LOCK", stage.verticalShape)
        assertEquals(1.5, stage.verticalCeil)
        assertEquals(false, stage.whole)
    }

    @Test
    fun presetCycleIsOptionalAndDefaultsToEmpty() {
        val mapper = jacksonObjectMapper()
        val json = """
            {
              "version": "1.7.0",
              "profiles": [ { "name": "plain" } ],
              "devices": []
            }
        """.trimIndent()

        val restored = mapper.readValue<Settings>(json)
        assertTrue(restored.profiles.single().presetCycle.isEmpty())
    }
}
