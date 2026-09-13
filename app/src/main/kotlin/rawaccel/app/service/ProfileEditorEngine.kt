package rawaccel.app.service

import rawaccel.app.model.AccelParams
import kotlin.math.abs

/** Pure curve editing/generation logic shared by the editor UI and tests. */
object ProfileEditorEngine {
    data class LutPoint(val input: Double, val output: Double) {
        val gain: Double get() = if (input == 0.0) 1.0 else output / input
    }

    /**
     * SMOOTH / LINEAR / SOFT_START / FAST_RAMP are accelerators: gain starts at
     * 1.0 and ramps up to [ProfileEditorEngine.generate]'s peak.
     *
     * TARGET_LOCK is the mirror shape: gain STARTS at a high floor (precise
     * micro-movements, e.g. placing the crosshair on a head) and decays to a
     * lower ceiling (fast movements stay controlled). This is the classic
     * sniper "target-lock" feel.
     */
    enum class CurveShape { SMOOTH, LINEAR, SOFT_START, FAST_RAMP, TARGET_LOCK }

    private fun smoothstep(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return c * c * (3.0 - 2.0 * c)
    }

    data class Validation(
        val errors: List<String>,
        val warnings: List<String>
    ) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    fun fromAccel(accel: AccelParams): List<LutPoint> = accel.data
        .chunked(2)
        .filter { it.size == 2 }
        .map { LutPoint(it[0], it[1]) }

    fun flatten(points: List<LutPoint>): List<Double> = points.flatMap { listOf(it.input, it.output) }

    /**
     * Removes gain dips with a minimal monotonic envelope. Input speeds remain
     * unchanged; only outputs below the highest prior gain are raised.
     */
    fun repairGainDips(points: List<LutPoint>): List<LutPoint> {
        var highestGain = 0.0
        return points.map { point ->
            val gain = if (point.input == 0.0) 1.0 else point.output / point.input
            highestGain = maxOf(highestGain, gain)
            if (point.input == 0.0) point.copy(output = 0.0)
            else point.copy(output = point.input * highestGain)
        }
    }

    /** Generates a 1x plateau, a shaped monotonic ramp, and a constant-gain tail. */
    fun generate(
        startSpeed: Double,
        endSpeed: Double,
        peakGain: Double,
        shape: CurveShape = CurveShape.SMOOTH,
        rampPoints: Int = 25,
        tailSpeed: Double = 2048.0
    ): List<LutPoint> {
        require(startSpeed > 0.0) { "Start speed must be greater than 0" }
        require(endSpeed > startSpeed) { "End speed must be greater than start speed" }
        require(peakGain >= 1.0) { "Peak gain must be at least 1.0x" }
        require(rampPoints in 3..200) { "Ramp points must be between 3 and 200" }

        // TARGET_LOCK is a decaying shape — route it to its dedicated
        // generator (peak becomes the floor, ceiling defaults to 1.0x).
        if (shape == CurveShape.TARGET_LOCK) {
            return generateTargetLock(startSpeed, endSpeed, peakGain, 1.0, rampPoints, tailSpeed)
        }

        val points = mutableListOf(LutPoint(0.0, 0.0))
        if (startSpeed > 1.0) points += LutPoint(startSpeed / 2.0, startSpeed / 2.0)
        points += LutPoint(startSpeed, startSpeed)

        for (index in 1 until rampPoints) {
            val t = index.toDouble() / (rampPoints - 1)
            val shaped = when (shape) {
                CurveShape.SMOOTH -> t * t * (3.0 - 2.0 * t)
                CurveShape.LINEAR -> t
                CurveShape.SOFT_START -> t * t
                CurveShape.FAST_RAMP -> 1.0 - (1.0 - t) * (1.0 - t)
                CurveShape.TARGET_LOCK -> error("unreachable: handled above")
            }
            val input = startSpeed + (endSpeed - startSpeed) * t
            val gain = 1.0 + (peakGain - 1.0) * shaped
            points += LutPoint(input, input * gain)
        }

        if (tailSpeed > endSpeed) points += LutPoint(tailSpeed, tailSpeed * peakGain)
        return points.distinctBy { it.input }
    }

    /**
     * Generates a target-lock (deceleration) curve: a constant [floorGain]
     * plateau up to [startSpeed], a smooth decay to [ceilGain] over
     * [startSpeed]..[endSpeed], and a constant [ceilGain] tail.
     *
     * [floorGain] is the low-speed gain (the precision lock), [ceilGain] the
     * high-speed ceiling.
     */
    fun generateTargetLock(
        startSpeed: Double,
        endSpeed: Double,
        floorGain: Double,
        ceilGain: Double = 1.0,
        rampPoints: Int = 25,
        tailSpeed: Double = 2048.0
    ): List<LutPoint> {
        require(startSpeed > 0.0) { "Start speed must be greater than 0" }
        require(endSpeed > startSpeed) { "End speed must be greater than start speed" }
        require(floorGain >= 1.0) { "Floor gain must be at least 1.0x" }
        require(ceilGain in 1.0..floorGain) { "Ceil gain must be between 1.0x and the floor gain" }
        require(rampPoints in 3..200) { "Ramp points must be between 3 and 200" }

        val points = mutableListOf(LutPoint(0.0, 0.0))
        if (startSpeed > 1.0) points += LutPoint(startSpeed / 2.0, startSpeed / 2.0 * floorGain)
        points += LutPoint(startSpeed, startSpeed * floorGain)

        for (index in 1 until rampPoints) {
            val t = index.toDouble() / (rampPoints - 1)
            val decay = smoothstep(t)
            val input = startSpeed + (endSpeed - startSpeed) * t
            val gain = ceilGain + (floorGain - ceilGain) * (1.0 - decay)
            points += LutPoint(input, input * gain)
        }

        if (tailSpeed > endSpeed) points += LutPoint(tailSpeed, tailSpeed * ceilGain)
        return points.distinctBy { it.input }
    }

    /**
     * Dispatches to the right generator for a [shape]: TARGET_LOCK builds a
     * decaying floor->ceil curve (where [peakGain] is the floor and
     * [ceilGain] the ceiling); every other shape builds the usual
     * 1.0 -> peak accelerator.
     */
    fun generateFor(
        startSpeed: Double,
        endSpeed: Double,
        peakGain: Double,
        shape: CurveShape = CurveShape.SMOOTH,
        ceilGain: Double = 1.0
    ): List<LutPoint> =
        if (shape == CurveShape.TARGET_LOCK) {
            generateTargetLock(startSpeed, endSpeed, peakGain, ceilGain)
        } else {
            generate(startSpeed, endSpeed, peakGain, shape)
        }

    fun validate(points: List<LutPoint>, velocityMode: Boolean = true): Validation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (points.size < 2) errors += "A LUT requires at least two points"
        if (points.size > 257) errors += "A LUT can contain at most 257 points"

        points.forEachIndexed { index, point ->
            if (!point.input.isFinite() || !point.output.isFinite()) {
                errors += "Point ${index + 1} contains a non-finite number"
            }
            if (point.input < 0.0) errors += "Point ${index + 1} has a negative input speed"
            if (point.output < 0.0) errors += "Point ${index + 1} has a negative output"
            if (index > 0 && point.input <= points[index - 1].input) {
                errors += "Point ${index + 1} input must be greater than the previous point"
            }
        }

        if (velocityMode && points.firstOrNull()?.input == 0.0 && points.firstOrNull()?.output != 0.0) {
            errors += "The output at zero input must also be zero"
        }

        // A pure target-lock curve (gain only ever decays) is an intended
        // shape, not a defect — only flag decreases when the curve mixes
        // directions (dips or bumps), which is what actually feels off.
        val gains = points.map { if (velocityMode) it.gain else it.output }
        // The (0,0) origin has no defined gain (the model reports 1.0); the
        // effective low-speed gain is the first segment's, i.e. point 1's.
        val effectiveGains = if (points.firstOrNull()?.input == 0.0 && points.size > 1) {
            listOf(gains[1]) + gains.drop(1)
        } else {
            gains
        }
        var sawIncrease = false
        var sawDecrease = false
        for (index in 1 until effectiveGains.size) {
            if (effectiveGains[index] > effectiveGains[index - 1] + 1e-6) sawIncrease = true
            if (effectiveGains[index] < effectiveGains[index - 1] - 1e-6) sawDecrease = true
        }
        val isTargetLock = sawDecrease && !sawIncrease

        var previousGain = 1.0
        points.forEachIndexed { index, point ->
            val gain = if (velocityMode) point.gain else point.output
            if (!isTargetLock && index > 0 && gain < previousGain - 1e-6) {
                warnings += "Gain decreases at point ${index + 1} (${format(gain)}x after ${format(previousGain)}x)"
            }
            if (gain > 10.0) warnings += "Point ${index + 1} exceeds 10x gain"
            if (index > 0 && abs(gain - previousGain) > 0.35) {
                warnings += "Large gain jump near point ${index + 1}: ${format(previousGain)}x to ${format(gain)}x"
            }
            previousGain = gain
        }

        return Validation(errors.distinct(), warnings.distinct())
    }

    fun inferGuidedValues(points: List<LutPoint>): Triple<Double, Double, Double> {
        if (points.isEmpty()) return Triple(50.0, 250.0, 1.4)
        val peak = points.maxOf { it.gain }
        val start = points.firstOrNull { it.input > 0.0 && it.gain > 1.001 }?.input
            ?: points.firstOrNull { it.input > 0.0 }?.input
            ?: 50.0
        val end = points.firstOrNull { it.input >= start && abs(it.gain - peak) < 0.002 }?.input
            ?: points.last().input
        return Triple(start, end.coerceAtLeast(start + 1.0), peak.coerceAtLeast(1.0))
    }

    private fun format(value: Double): String = "%.3f".format(value)
}
