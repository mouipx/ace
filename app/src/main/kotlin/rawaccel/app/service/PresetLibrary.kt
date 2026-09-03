package rawaccel.app.service

import rawaccel.app.model.*

/**
 * Hand-tuned preset curve library for common Hypixel Zombies: Dead End scenarios.
 *
 * Each preset is generated programmatically via ProfileEditorEngine so they're
 * always monotonic, start at (0,0), and respect the driver's LUT capacity.
 * These are starting points — tune to your hand in the Editor.
 */
object PresetLibrary {

    data class Preset(
        val id: String,
        val category: String,
        val name: String,
        val description: String,
        val dpi: Int,
        val outputDpi: Double,
        val yxRatio: Double,
        val accelX: AccelParams,
        val accelY: AccelParams? = null,
        val whole: Boolean = true,
        val speedCap: Double = 0.0
    )

    private fun lutPreset(
        start: Double, end: Double, peak: Double,
        shape: ProfileEditorEngine.CurveShape = ProfileEditorEngine.CurveShape.SMOOTH,
        cap: Vec2 = Vec2(15.0, 1.5)
    ): AccelParams {
        val points = ProfileEditorEngine.generate(start, end, peak, shape)
        return AccelParams(
            mode = "lut",
            gain = true,
            inputOffset = 0.0,
            outputOffset = 0.0,
            // NOTE: these scalars are unused in LUT mode but the driver's validator
            // still enforces positivity / range on them (see rawaccel-validate.hpp).
            // Use Raw Accel's classic defaults so apply() never rejects a preset.
            acceleration = 0.005,
            decayRate = 0.1,
            gamma = 1.0,
            motivity = 1.5,
            exponentClassic = 2.0,
            scale = 1.0,
            exponentPower = 0.05,
            limit = peak,
            syncSpeed = end,
            smooth = 0.5,
            cap = cap,
            capMode = "output",
            data = ProfileEditorEngine.flatten(points)
        )
    }

    private fun noaccel() = AccelParams(mode = "noaccel", gain = true)

    val presets: List<Preset> = listOf(
        // ── DEAD END CORE ──────────────────────────────────────────────
        Preset(
            id = "dead-end-classic",
            category = "DEAD END",
            name = "Dead End · Classic",
            description = "Balanced generalist. 1:1 up to ~50 in/s, smooth rise to 1.4×, flat tail. The v2 corrected curve.",
            dpi = 1200,
            outputDpi = 1200.0,
            yxRatio = 0.9,
            accelX = lutPreset(50.0, 250.0, 1.40, ProfileEditorEngine.CurveShape.SMOOTH)
        ),
        Preset(
            id = "dead-end-shotgun",
            category = "DEAD END",
            name = "Dead End · Shotgun",
            description = "Conservative curve — 1:1 up to ~80 in/s, peak only 1.25×. For tight shotgun tracking at close range.",
            dpi = 1200,
            outputDpi = 1200.0,
            yxRatio = 0.88,
            accelX = lutPreset(80.0, 200.0, 1.25, ProfileEditorEngine.CurveShape.SMOOTH)
        ),
        Preset(
            id = "dead-end-rifle",
            category = "DEAD END",
            name = "Dead End · Rifle",
            description = "Mid-range tracking. Accel starts earlier (~45 in/s) and peaks 1.35× for tracking zombie hordes at medium range.",
            dpi = 1200,
            outputDpi = 1200.0,
            yxRatio = 0.90,
            accelX = lutPreset(45.0, 160.0, 1.35, ProfileEditorEngine.CurveShape.FAST_RAMP)
        ),
        Preset(
            id = "dead-end-pistol",
            category = "DEAD END",
            name = "Dead End · Pistol",
            description = "Aggressive flick curve. Early start (~35 in/s), high peak 1.5× for quick 180s when cornered on pistol rounds.",
            dpi = 1200,
            outputDpi = 1200.0,
            yxRatio = 0.95,
            accelX = lutPreset(35.0, 120.0, 1.50, ProfileEditorEngine.CurveShape.FAST_RAMP)
        ),
        Preset(
            id = "dead-end-sniper",
            category = "DEAD END",
            name = "Dead End · Sniper / Precision",
            description = "Minimal accel. Almost raw up to ~100 in/s, very late small rise to 1.15×. Clean micro-aim, no surprises.",
            dpi = 1200,
            outputDpi = 1200.0,
            yxRatio = 1.0,
            accelX = lutPreset(100.0, 280.0, 1.15, ProfileEditorEngine.CurveShape.SMOOTH)
        ),
        Preset(
            id = "dead-end-sniper-shotgun",
            category = "DEAD END",
            name = "Dead End - Sniper + Shotgun",
            description = "Main two-weapon loadout. Keeps sniper headshots stable while still allowing faster shotgun turns when crowded.",
            dpi = 1200,
            outputDpi = 1200.0,
            yxRatio = 0.92,
            accelX = lutPreset(90.0, 240.0, 1.25, ProfileEditorEngine.CurveShape.SMOOTH),
            accelY = lutPreset(120.0, 300.0, 1.10, ProfileEditorEngine.CurveShape.SOFT_START),
            whole = false
        ),

        // ── GENERAL FPS ────────────────────────────────────────────────
        Preset(
            id = "generic-tracking",
            category = "GENERIC FPS",
            name = "Tracking · Smooth",
            description = "General-purpose tracking curve. ~40 in/s start, smooth S-curve to 1.3×. Works for most FPS.",
            dpi = 800,
            outputDpi = 800.0,
            yxRatio = 1.0,
            accelX = lutPreset(40.0, 200.0, 1.30, ProfileEditorEngine.CurveShape.SMOOTH)
        ),
        Preset(
            id = "generic-flick",
            category = "GENERIC FPS",
            name = "Flick · Fast Ramp",
            description = "Fast ramps for quick 180/90 flicks. Start ~30, sharp ramp, 1.55× peak. Aim trainers / arena FPS.",
            dpi = 800,
            outputDpi = 800.0,
            yxRatio = 1.0,
            accelX = lutPreset(30.0, 140.0, 1.55, ProfileEditorEngine.CurveShape.FAST_RAMP)
        ),

        // ── RAW / NO ACCEL ─────────────────────────────────────────────
        Preset(
            id = "raw-1to1",
            category = "BASELINE",
            name = "Raw 1:1 (no accel)",
            description = "No acceleration. Pure linear sensitivity. Good baseline for comparison / desktop.",
            dpi = 800,
            outputDpi = 800.0,
            yxRatio = 1.0,
            accelX = noaccel()
        ),
        Preset(
            id = "raw-highdpi",
            category = "BASELINE",
            name = "Raw 1:1 · 1600 DPI",
            description = "1:1 at 1600 DPI for high-sens players.",
            dpi = 1600,
            outputDpi = 1600.0,
            yxRatio = 1.0,
            accelX = noaccel()
        ),

        // ── DUAL-AXIS / BY COMPONENT ──────────────────────────────────
        Preset(
            id = "dead-end-dual",
            category = "DUAL-AXIS",
            name = "Dead End · Dual Axis (Advanced)",
            description = "Separate H/V curves. Horizontal rises normally, vertical starts later and peaks lower for clean recoil control.",
            dpi = 1200,
            outputDpi = 1200.0,
            yxRatio = 0.9,
            accelX = lutPreset(50.0, 240.0, 1.40, ProfileEditorEngine.CurveShape.SMOOTH),
            accelY = lutPreset(60.0, 200.0, 1.30, ProfileEditorEngine.CurveShape.SOFT_START),
            whole = false
        ),
        Preset(
            id = "dead-end-dual-track-flick",
            category = "DUAL-AXIS",
            name = "Dead End - Dual Track Flick",
            description = "Dead-end-dual sibling with stronger horizontal pickup for flicks and calmer vertical accel for head-level tracking.",
            dpi = 1200,
            outputDpi = 1200.0,
            yxRatio = 0.92,
            accelX = lutPreset(45.0, 220.0, 1.45, ProfileEditorEngine.CurveShape.SMOOTH),
            accelY = lutPreset(70.0, 260.0, 1.22, ProfileEditorEngine.CurveShape.SOFT_START),
            whole = false
        ),
        Preset(
            id = "dead-end-dual-track-flick-1600",
            category = "DUAL-AXIS",
            name = "Dead End - Dual Track Flick 1600",
            description = "1600 DPI comparison profile: smoother micro tracking, controlled vertical aim, and a stronger high-speed horizontal flick tail.",
            dpi = 1600,
            outputDpi = 1600.0,
            yxRatio = 0.90,
            accelX = lutPreset(50.0, 240.0, 1.48, ProfileEditorEngine.CurveShape.SMOOTH),
            accelY = lutPreset(90.0, 310.0, 1.16, ProfileEditorEngine.CurveShape.SOFT_START),
            whole = false
        )
    )

    fun byCategory(): Map<String, List<Preset>> = presets.groupBy { it.category }

    fun toSettings(preset: Preset, deviceName: String = "Default Mouse", deviceId: String = ""): Settings {
        val profileName = preset.name
        val accelY = preset.accelY ?: if (preset.whole) noaccel() else preset.accelX
        val profile = Profile(
            name = profileName,
            domain = Vec2(1.0, 1.0),
            range = Vec2(1.0, 1.0),
            accelX = preset.accelX,
            accelY = accelY,
            speed = SpeedParams(whole = preset.whole, lpNorm = 2.0),
            outputDpi = preset.outputDpi,
            yxRatio = preset.yxRatio,
            lrRatio = 1.0,
            udRatio = 1.0,
            rotation = 0.0,
            snap = 0.0,
            speedCap = preset.speedCap
        )
        val device = if (deviceId.isNotBlank()) listOf(
            DeviceSettings(
                name = deviceName,
                profile = profileName,
                id = deviceId,
                config = DeviceConfig(disable = false, dpi = preset.dpi, pollingRate = 1000)
            )
        ) else emptyList()
        return Settings(
            version = "1.7.0",
            defaultDeviceConfig = DeviceConfig(disable = deviceId.isNotBlank(), dpi = preset.dpi, pollingRate = 1000),
            profiles = listOf(profile),
            devices = device
        )
    }
}
