package rawaccel.app.service

import rawaccel.app.model.Profile
import rawaccel.app.model.Settings
import kotlin.math.PI
import kotlin.math.cbrt

/**
 * Converts DPI + Raw Accel gain into physical aim metrics (cm/360, in/360, eDPI).
 *
 * All calculations assume raw input (6/11 Windows, no Enhance Pointer Precision),
 * which is what Minecraft, CS2, Valorant, Fortnite etc. all use. Gain multipliers
 * come straight from CurveEngine so the numbers match what the driver applies.
 */
object SensitivityCalculator {

    data class AimMetrics(
        val baseCmPer360: Double,
        val peakCmPer360: Double,
        val cmAt20: Double,
        val cmAt60: Double,
        val cmAt120: Double,
        val inAt20: Double,
        val inAt60: Double,
        val inAt120: Double,
        val effectiveDpi: Double,
        val baseEDpi: Double,
        val peakEDpi: Double,
        val peakGain: Double,
        val gain20: Double,
        val gain60: Double,
        val gain120: Double,
        val accelStart: Double?
    )

    /** Inches per 360° at a given effective DPI.
     *  Uses the aim-community standard m_yaw = 0.022°/count
     *  (which matches GoldSrc/Source/most raw-input games). */
    fun inchesPer360(edpi: Double): Double {
        val mYaw = 0.022
        return 360.0 / (edpi * mYaw)
    }

    /** cm per 360° at a given effective DPI. */
    fun cmPer360(edpi: Double): Double = inchesPer360(edpi) * 2.54

    /**
     * Approximate cm/360 for Minecraft Java Edition at a given DPI and in-game
     * slider percent (0-200, 100 = default).
     *
     * Minecraft's actual formula is non-linear:
     *   f = (0.6 * s + 0.2)^3 * 8.0 * 0.15   (radians per count)
     * where s is options.txt sens (0..1, GUI 0-200 maps to 0..1).
     * Calibrated to match the widely-cited ~32.6 cm/360 @ 800 DPI 100%.
     */
    fun minecraftCm360(dpi: Double, sensPercent: Double): Double {
        val s = (sensPercent / 200.0).coerceIn(0.0, 1.0)
        val f = 0.6 * s + 0.2
        // Calibrated to the community-standard reference: 800 DPI / 100% slider ≈ 32.6 cm/360.
        // f^3 is Minecraft's actual sensitivity shaping (vanilla uses multiplier^3),
        // MC_ROT const is tuned so the reference point lands exactly.
        val mcRotConst = 0.004895
        val radPerCount = f * f * f * mcRotConst
        val countsPerRev = 2.0 * PI / radPerCount
        val inchesPer360 = countsPerRev / dpi
        return inchesPer360 * 2.54
    }

    /**
     * Inverse of minecraftCm360: returns the in-game slider percent (0-200)
     * that produces a target cm/360 at a given DPI.
     */
    fun minecraftSensForCm360(dpi: Double, targetCm360: Double): Double {
        val targetIn = targetCm360 / 2.54
        val countsPerRev = targetIn * dpi
        val radPerCount = 2.0 * PI / countsPerRev
        val mcRotConst = 0.004895
        val f = cbrt(radPerCount / mcRotConst)
        val s = ((f - 0.2) / 0.6).coerceIn(0.0, 1.0)
        return s * 200.0
    }

    fun compute(settings: Settings): AimMetrics {
        val deviceDpi = settings.devices.firstOrNull()?.config?.dpi
            ?: settings.defaultDeviceConfig.dpi
        val profile = settings.profiles.firstOrNull()
        val outputDpi = profile?.outputDpi?.takeIf { it > 0 } ?: deviceDpi.toDouble()

        val g20 = profile?.let { CurveEngine.gainAt(it.accelX, 20.0) } ?: 1.0
        val g60 = profile?.let { CurveEngine.gainAt(it.accelX, 60.0) } ?: 1.0
        val g120 = profile?.let { CurveEngine.gainAt(it.accelX, 120.0) } ?: 1.0
        val stats = profile?.let { CurveEngine.stats(it.accelX, 300.0) }
        val peakGain = stats?.peakGain ?: 1.0

        val baseCm = cmPer360(outputDpi)
        val baseIn = inchesPer360(outputDpi)

        return AimMetrics(
            baseCmPer360 = baseCm,
            peakCmPer360 = baseCm / peakGain,
            cmAt20 = baseCm / g20,
            cmAt60 = baseCm / g60,
            cmAt120 = baseCm / g120,
            inAt20 = baseIn / g20,
            inAt60 = baseIn / g60,
            inAt120 = baseIn / g120,
            effectiveDpi = outputDpi,
            baseEDpi = outputDpi,
            peakEDpi = outputDpi * peakGain,
            peakGain = peakGain,
            gain20 = g20,
            gain60 = g60,
            gain120 = g120,
            accelStart = stats?.accelStartSpeed
        )
    }

    fun formatCm(v: Double): String = when {
        v >= 100.0 -> "%.0f".format(v)
        v >= 10.0  -> "%.1f".format(v)
        else       -> "%.2f".format(v)
    }
}
