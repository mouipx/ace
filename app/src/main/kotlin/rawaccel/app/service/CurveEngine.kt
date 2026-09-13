package rawaccel.app.service

import rawaccel.app.model.AccelParams
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Faithful Kotlin port of Raw Accel's curve math (see `common/accel-lookup.hpp`
 * and `common/rawaccel.hpp`). Used for the live preview and for the
 * monotonicity check that caught the "feels off" bug in the original profile.
 *
 * The sensitivity multiplier the driver applies is:
 *     scale = 1 + (gain - 1) * rangeWeight        (rangeWeight defaults to 1)
 * where, in "Gain / Velocity" mode, gain = output / input.
 */
object CurveEngine {
    data class Stats(
        val gain20: Double,
        val gain60: Double,
        val gain120: Double,
        val peakGain: Double,
        val peakSpeed: Double,
        val accelStartSpeed: Double?
    )

    /** Mirrors rawaccel::lerp. */
    fun lerp(a: Double, b: Double, t: Double): Double {
        val x = a + t * (b - a)
        return if ((t > 1) == (a < b)) max(x, b) else min(x, b)
    }

    /** Mirrors lookup::operator(). points is the flat [x0,y0,x1,y1,...] array. */
    fun lookupGain(points: List<Double>, velocity: Boolean, x: Double): Double {
        if (points.size < 4) return 1.0
        val n = points.size / 2
        if (x <= 0) return 0.0
        val capacity = 257
        var lo = 0
        var hi = n - 2
        if (hi < capacity - 1) {
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val px = points[mid * 2]
                when {
                    x < px -> hi = mid - 1
                    x > px -> lo = mid + 1
                    else -> {
                        val y = points[mid * 2 + 1]
                        return if (velocity) y / x else y
                    }
                }
            }
            if (lo > 0) {
                val ax = points[(lo - 1) * 2]
                val ay = points[(lo - 1) * 2 + 1]
                val bx = points[lo * 2]
                val by = points[lo * 2 + 1]
                val t = (x - ax) / (bx - ax)
                val y = lerp(ay, by, t)
                return if (velocity) y / x else y
            }
        }
        val y0 = points[1]
        return if (velocity) y0 / points[0] else y0
    }

    /** Sensitivity (gain) at input speed x (in/s) for one accel axis. */
    fun gainAt(accel: AccelParams, x: Double): Double = when (accel.mode) {
        "lut" -> lookupGain(accel.data, accel.gain, x)

        "noaccel" -> 1.0

        "classic" -> {
            // gain mode: 1 + accel_raised * (x - offset)^exp / x  (cap not shown in preview)
            val eff = max(x - accel.inputOffset, 0.0)
            val accelRaised = accel.acceleration.pow(accel.exponentClassic - 1)
            1.0 + accelRaised * eff.pow(accel.exponentClassic) / x.coerceAtLeast(1e-9)
        }

        // jump / natural / synchronous / power: previews coming in a later pass.
        else -> 1.0
    }

    fun stats(accel: AccelParams, maxIn: Double): Stats {
        var peakGain = 1.0
        var peakSpeed = 0.0
        var accelStartSpeed: Double? = null
        val samples = 600

        for (i in 0..samples) {
            val speed = maxIn * i / samples
            val gain = gainAt(accel, speed)
            if (gain > peakGain) {
                peakGain = gain
                peakSpeed = speed
            }
            if (accelStartSpeed == null && speed > 0.0 && gain > 1.01) {
                accelStartSpeed = speed
            }
        }

        return Stats(
            gain20 = gainAt(accel, 20.0.coerceAtMost(maxIn)),
            gain60 = gainAt(accel, 60.0.coerceAtMost(maxIn)),
            gain120 = gainAt(accel, 120.0.coerceAtMost(maxIn)),
            peakGain = peakGain,
            peakSpeed = peakSpeed,
            accelStartSpeed = accelStartSpeed
        )
    }

    /**
     * Scans the LUT and returns (inputSpeed, gain) at every dip in sensitivity.
     *
     * A curve that is monotonic in one direction is fine: an accelerator that
     * only rises, or a target-lock that only decays, is both an intended shape.
     * A "dip" only exists when the curve mixes directions (rises then falls, or
     * falls then rises) — that is the "feels off" bug. In the mixed case the
     * downward steps are reported.
     */
    fun monotonicityIssues(accel: AccelParams): List<Pair<Double, Double>> {
        if (!accel.isLut || accel.data.size < 4) return emptyList()
        val maxIn = accel.data[accel.data.size - 2]
        val samples = ArrayList<Pair<Double, Double>>()
        var v = 0.0
        val step = maxIn / 600.0
        while (v <= maxIn) {
            samples.add(v to gainAt(accel, v))
            v += step
        }

        // The v=0 sample is a boundary artifact (lookup gain is 0 at zero
        // input), not curve behaviour — direction analysis starts at v>0.
        val curveSamples = samples.filter { it.first > 0.0 }
        var sawIncrease = false
        var sawDecrease = false
        for (index in 1 until curveSamples.size) {
            val prevG = curveSamples[index - 1].second
            val g = curveSamples[index].second
            if (g > prevG + 1e-6) sawIncrease = true
            if (g < prevG - 1e-6) sawDecrease = true
        }
        val monotonic = !(sawIncrease && sawDecrease)
        if (monotonic) return emptyList()

        val issues = ArrayList<Pair<Double, Double>>()
        for (index in 1 until samples.size) {
            val (speed, g) = samples[index]
            val prevG = samples[index - 1].second
            if (g < prevG - 1e-6) issues.add(speed to g)
        }
        return issues
    }
}
