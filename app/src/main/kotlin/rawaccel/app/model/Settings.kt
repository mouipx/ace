package rawaccel.app.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Kotlin mirror of Raw Accel's `settings.json` schema. Field names match the
 * JSON keys exactly (including the human-readable ones), so the same files the
 * GUI / writer use load unchanged.
 */

data class Vec2(val x: Double = 1.0, val y: Double = 1.0)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccelParams(
    val mode: String = "noaccel",
    @param:JsonProperty("Gain / Velocity") @get:JsonProperty("Gain / Velocity") val gain: Boolean = true,
    val inputOffset: Double = 0.0,
    val outputOffset: Double = 0.0,
    val acceleration: Double = 0.005,
    val decayRate: Double = 0.1,
    val gamma: Double = 1.0,
    val motivity: Double = 1.5,
    val exponentClassic: Double = 2.0,
    val scale: Double = 1.0,
    val exponentPower: Double = 0.05,
    val limit: Double = 1.5,
    val syncSpeed: Double = 5.0,
    val smooth: Double = 0.5,
    @param:JsonProperty("Cap / Jump") @get:JsonProperty("Cap / Jump") val cap: Vec2 = Vec2(15.0, 1.5),
    @param:JsonProperty("Cap mode") @get:JsonProperty("Cap mode") val capMode: String = "output",
    val data: List<Double> = emptyList()
) {
    /** UI-only convenience property; not part of Raw Accel's JSON schema. */
    @get:JsonIgnore
    val isLut: Boolean get() = mode == "lut"
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpeedParams(
    @param:JsonProperty("Whole/combined accel (set false for 'by component' mode)") @get:JsonProperty("Whole/combined accel (set false for 'by component' mode)")
    val whole: Boolean = true,
    val lpNorm: Double = 2.0,
    @param:JsonProperty("Time in ms after which an input is weighted at half its original value.") @get:JsonProperty("Time in ms after which an input is weighted at half its original value.")
    val inputSmoothHalflife: Double = 0.0,
    @param:JsonProperty("Time in ms after which scale is weighted at half its original value.") @get:JsonProperty("Time in ms after which scale is weighted at half its original value.")
    val scaleSmoothHalflife: Double = 0.0,
    @param:JsonProperty("Time in ms after which an output is weighted at half its original value.") @get:JsonProperty("Time in ms after which an output is weighted at half its original value.")
    val outputSmoothHalflife: Double = 0.0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Profile(
    val name: String = "default",
    @param:JsonProperty("Stretches domain for horizontal vs vertical inputs") @get:JsonProperty("Stretches domain for horizontal vs vertical inputs") val domain: Vec2 = Vec2(),
    @param:JsonProperty("Stretches accel range for horizontal vs vertical inputs") @get:JsonProperty("Stretches accel range for horizontal vs vertical inputs") val range: Vec2 = Vec2(),
    @param:JsonProperty("Whole or horizontal accel parameters") @get:JsonProperty("Whole or horizontal accel parameters") val accelX: AccelParams = AccelParams(),
    @param:JsonProperty("Vertical accel parameters") @get:JsonProperty("Vertical accel parameters") val accelY: AccelParams = AccelParams(),
    @param:JsonProperty("Input speed calculation parameters") @get:JsonProperty("Input speed calculation parameters") val speed: SpeedParams = SpeedParams(),
    @param:JsonProperty("Output DPI") @get:JsonProperty("Output DPI") val outputDpi: Double = 1200.0,
    @param:JsonProperty("Y/X output DPI ratio (vertical sens multiplier)") @get:JsonProperty("Y/X output DPI ratio (vertical sens multiplier)") val yxRatio: Double = 1.0,
    @param:JsonProperty("L/R output DPI ratio (left sens multiplier)") @get:JsonProperty("L/R output DPI ratio (left sens multiplier)") val lrRatio: Double = 1.0,
    @param:JsonProperty("U/D output DPI ratio (up sens multiplier)") @get:JsonProperty("U/D output DPI ratio (up sens multiplier)") val udRatio: Double = 1.0,
    @param:JsonProperty("Degrees of rotation") @get:JsonProperty("Degrees of rotation") val rotation: Double = 0.0,
    @param:JsonProperty("Degrees of angle snapping") @get:JsonProperty("Degrees of angle snapping") val snap: Double = 0.0,
    @param:JsonProperty("Input Speed Cap") @get:JsonProperty("Input Speed Cap") val speedCap: Double = 0.0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceConfig(
    val disable: Boolean = false,
    val setExtraInfo: Boolean = false,
    @param:JsonProperty("Use constant time interval based on polling rate") @get:JsonProperty("Use constant time interval based on polling rate") val pollTimeLock: Boolean = false,
    @param:JsonProperty("DPI (normalizes input speed unit: counts/ms -> in/s)") @get:JsonProperty("DPI (normalizes input speed unit: counts/ms -> in/s)") val dpi: Int = 1200,
    @param:JsonProperty("Polling rate Hz (keep at 0 for automatic adjustment)") @get:JsonProperty("Polling rate Hz (keep at 0 for automatic adjustment)") val pollingRate: Int = 1000,
    val minimumTime: Double = 0.0625,
    val maximumTime: Double = 100.0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceSettings(
    val name: String = "",
    val profile: String = "",
    val id: String = "",
    val config: DeviceConfig = DeviceConfig()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Settings(
    val version: String = "1.7.0",
    val defaultDeviceConfig: DeviceConfig = DeviceConfig(),
    val profiles: List<Profile> = emptyList(),
    val devices: List<DeviceSettings> = emptyList()
)
