package rawaccel.app.service

import rawaccel.app.model.Profile
import rawaccel.app.model.Settings

/**
 * Static config doctor — runs a battery of sanity checks on a Settings document
 * and returns machine-readable findings + one-click fix actions. This catches the
 * exact issues that made your Dead End curve "feel off": DPI mismatches, dead
 * LUTs, gain dips, bad caps, unused parameters, and more.
 *
 * No driver I/O here — everything runs on the JSON document the UI is editing.
 */
object ConfigDoctor {

    enum class Severity { OK, INFO, WARNING, CRITICAL }

    data class Finding(
        val code: String,
        val severity: Severity,
        val title: String,
        val detail: String,
        val fixable: Boolean = false
    ) {
        val label: String get() = when (severity) {
            Severity.OK -> "PASS"
            Severity.INFO -> "INFO"
            Severity.WARNING -> "WARN"
            Severity.CRITICAL -> "FAIL"
        }
    }

    data class Report(val findings: List<Finding>) {
        val criticals: List<Finding> get() = findings.filter { it.severity == Severity.CRITICAL }
        val warnings: List<Finding> get() = findings.filter { it.severity == Severity.WARNING }
        val infos: List<Finding> get() = findings.filter { it.severity == Severity.INFO }
        val passes: List<Finding> get() = findings.filter { it.severity == Severity.OK }
        val hasErrors: Boolean get() = criticals.isNotEmpty()
        val score: Int get() {
            var s = 100
            for (f in findings) {
                when (f.severity) {
                    Severity.CRITICAL -> s -= 25
                    Severity.WARNING -> s -= 10
                    Severity.INFO -> s -= 2
                    else -> {}
                }
            }
            return s.coerceIn(0, 100)
        }
    }

    fun diagnose(settings: Settings): Report {
        val findings = mutableListOf<Finding>()

        // 1. Empty / no profiles
        if (settings.profiles.isEmpty()) {
            findings.add(
                Finding(
                    "NO_PROFILES", Severity.CRITICAL,
                    "No profiles configured",
                    "The settings contain zero profiles — the driver will not apply any acceleration."
                )
            )
            return Report(findings)
        }

        val defaultCfg = settings.defaultDeviceConfig
        val deviceDpi = settings.devices.firstOrNull()?.config?.dpi ?: defaultCfg.dpi
        val devicePoll = settings.devices.firstOrNull()?.config?.pollingRate ?: defaultCfg.pollingRate

        // 2. Profile name uniqueness
        val names = settings.profiles.map { it.name }
        val duplicates = names.groupBy { it }.filter { it.value.size > 1 }.keys
        val dupStr = duplicates.joinToString()
        if (duplicates.isNotEmpty()) {
            findings.add(
                Finding(
                    "DUP_NAMES", Severity.CRITICAL,
                    "Duplicate profile names",
                    "The driver can't distinguish profiles with identical names: $dupStr."
                )
            )
        }

        val totalProfiles = settings.profiles.size
        for ((idx, profile) in settings.profiles.withIndex()) {
            diagnoseProfile(profile, idx, deviceDpi, totalProfiles, findings)
        }

        // 3. Device references
        if (settings.devices.isEmpty() && !defaultCfg.disable) {
            findings.add(
                Finding(
                    "DEFAULT_OPEN", Severity.INFO,
                    "Default device rule enabled",
                    "Every connected mouse will receive the first profile. Use DEVICES to target specific mice."
                )
            )
        }
        if (defaultCfg.disable && settings.devices.isNotEmpty()) {
            findings.add(
                Finding(
                    "DEVICE_LOCK", Severity.OK,
                    "Device targeting active",
                    "Only configured mice receive acceleration (default rule disabled)."
                )
            )
        }

        // 4. Device DPI / poll sanity
        if (deviceDpi <= 0) {
            findings.add(
                Finding(
                    "DPI_ZERO", Severity.CRITICAL,
                    "Device DPI is zero",
                    "DPI must be set to your mouse's actual CPI. 800/1600/3200 are common values.",
                    fixable = true
                )
            )
        }
        if (devicePoll > 0 && devicePoll !in listOf(125, 250, 500, 1000, 2000, 4000, 8000)) {
            findings.add(
                Finding(
                    "POLL_UNUSUAL", Severity.INFO,
                    "Unusual polling rate",
                    "$devicePoll Hz is not a standard mouse poll rate. Common values: 125/500/1000/2000/4000."
                )
            )
        }

        // 5. DPI mismatch heuristic between profile name and declared DPI
        for (p in settings.profiles) {
            val pName = p.name
            val mismatchDpi = when {
                "1200" in pName && deviceDpi != 1200 -> 1200
                "800" in pName && deviceDpi != 800 -> 800
                "1600" in pName && deviceDpi != 1600 -> 1600
                "3200" in pName && deviceDpi != 3200 -> 3200
                else -> null
            }
            if (mismatchDpi != null) {
                findings.add(
                    Finding(
                        "DPI_NAME_MISMATCH", Severity.WARNING,
                        "Possible DPI mismatch",
                        "Profile \"$pName\" suggests $mismatchDpi CPI but the device is configured for $deviceDpi DPI. " +
                                "The entire curve will shift if your physical mouse DPI doesn't match."
                    )
                )
            }
        }

        // 6. Smooth halflife sanity (too high = laggy)
        for (p in settings.profiles) {
            val maxSmooth = maxOf(
                p.speed.inputSmoothHalflife,
                p.speed.scaleSmoothHalflife,
                p.speed.outputSmoothHalflife
            )
            if (maxSmooth > 5.0) {
                findings.add(
                    Finding(
                        "SMOOTH_HIGH", Severity.WARNING,
                        "Smoothing halflife > 5 ms",
                        "A halflife of ${maxSmooth}ms adds noticeable input lag. Competitive Dead End play typically uses 0–2ms."
                    )
                )
            }
        }

        // 7. Angle snapping on
        val snapProfile = settings.profiles.firstOrNull { it.snap > 0 }
        if (snapProfile != null) {
            val snapName = snapProfile.name
            val snapDeg = snapProfile.snap
            findings.add(
                Finding(
                    "SNAP_ON", Severity.INFO,
                    "Angle snapping enabled",
                    "\"$snapName\" has ${snapDeg}° of angle snapping. " +
                            "This can help with shotgun tracking but hurts precision flicks."
                )
            )
        }

        return Report(findings)
    }

    private fun diagnoseProfile(
        p: Profile,
        idx: Int,
        deviceDpi: Int,
        totalProfiles: Int,
        findings: MutableList<Finding>
    ) {
        val prefix = if (totalProfiles > 1) "[$idx] " else ""
        val outputDpi = p.outputDpi.takeIf { it > 0 } ?: 1200.0
        val outDpiStr = "%.0f".format(outputDpi)
        val scaleStr = if (deviceDpi > 0) "%.2f".format(outputDpi / deviceDpi) else "1.00"
        val stats = CurveEngine.stats(p.accelX, 300.0)
        val start = stats.accelStartSpeed
        val startStr = if (start != null) "%.0f".format(start) else ""
        val peakStr = "%.2f".format(stats.peakGain)

        // LUT monotonicity
        for ((axisName, accel) in listOf("horizontal" to p.accelX, "vertical" to p.accelY)) {
            if (accel.mode != "lut" || accel.data.size < 4) continue
            val issues = CurveEngine.monotonicityIssues(accel)
            val issueCount = issues.size
            if (issues.isNotEmpty()) {
                findings.add(
                    Finding(
                        "LUT_DIP", Severity.WARNING,
                        "${prefix}Non-monotonic $axisName LUT",
                        "The $axisName curve dips at $issueCount point(s). A gain dip means faster hand movement " +
                                "gives LESS sensitivity — that 'feels off' bug. Use REPAIR GAIN DIPS in the editor."
                    )
                )
            } else {
                findings.add(
                    Finding(
                        "LUT_MONO", Severity.OK,
                        "$prefix$axisName LUT monotonic",
                        "The $axisName LUT passes the monotonicity check."
                    )
                )
            }
        }

        // Vertical LUT present but whole/combined mode is on
        if (p.speed.whole && p.accelY.mode != "noaccel" && p.accelY.data.isNotEmpty()) {
            findings.add(
                Finding(
                    "DEAD_VERT_LUT", Severity.WARNING,
                    "${prefix}Vertical LUT is dead code",
                    "Combined/whole mode uses only the horizontal LUT. The vertical LUT is loaded but never read. " +
                            "Either switch to 'by component' mode or clear the vertical LUT."
                )
            )
        }

        // Output DPI
        if (p.outputDpi <= 0) {
            findings.add(
                Finding(
                    "OUT_DPI_ZERO", Severity.CRITICAL,
                    "${prefix}Output DPI is zero",
                    "Output DPI must be positive."
                )
            )
        }
        if (deviceDpi > 0 && kotlin.math.abs(outputDpi - deviceDpi) / deviceDpi > 0.5) {
            findings.add(
                Finding(
                    "OUT_DPI_BIG", Severity.INFO,
                    "${prefix}Output DPI differs from device DPI",
                    "Device is $deviceDpi DPI but output is $outDpiStr DPI. This scales your base sensitivity " +
                            "$scaleStr× in addition to the curve. Make sure this is intentional."
                )
            )
        }

        // Directional ratios
        if (p.lrRatio != 1.0) {
            val lr = p.lrRatio
            findings.add(
                Finding(
                    "LR_RATIO", Severity.INFO,
                    "${prefix}Left/right ratio is $lr",
                    "Left and right sensitivity differ. Useful for mirroring issues or dominant-eye bias."
                )
            )
        }
        if (p.udRatio != 1.0) {
            val ud = p.udRatio
            findings.add(
                Finding(
                    "UD_RATIO", Severity.INFO,
                    "${prefix}Up/down ratio is $ud",
                    "Up and down sensitivity differ."
                )
            )
        }

        // Speed cap
        if (p.speedCap in 0.1..1.0) {
            val cap = p.speedCap
            findings.add(
                Finding(
                    "SPEEDCAP_TIGHT", Severity.WARNING,
                    "${prefix}Input speed cap is very low",
                    "Cap of $cap in/s will clip fast flicks. This is usually only used for jitter control."
                )
            )
        }

        // Zero start
        if (p.accelX.mode == "lut" && p.accelX.data.size >= 4 &&
            (p.accelX.data[0] != 0.0 || p.accelX.data[1] != 0.0)
        ) {
            findings.add(
                Finding(
                    "LUT_ORIGIN", Severity.WARNING,
                    "${prefix}LUT does not start at (0,0)",
                    "Gain/Velocity LUTs should start at (0,0) so small micro-movements aren't scaled incorrectly."
                )
            )
        }

        // Acceleration start vs Dead End expectations
        if (start != null) {
            if (start > 100) {
                findings.add(
                    Finding(
                        "START_HIGH", Severity.INFO,
                        "${prefix}Accel starts at ~${startStr} in/s",
                        "The curve is 1:1 up to ~${startStr} in/s — this is a wide dead-zone. Fine for slow aim; " +
                                "fast flicks below this speed won't get help."
                    )
                )
            } else if (start < 10) {
                findings.add(
                    Finding(
                        "START_VERY_LOW", Severity.INFO,
                        "${prefix}Accel starts very early (${startStr} in/s)",
                        "Almost every movement gets acceleration. Your micro-aim will feel 'floaty' if this is too low."
                    )
                )
            }
        }
        if (stats.peakGain > 3.0) {
            findings.add(
                Finding(
                    "PEAK_HIGH", Severity.WARNING,
                    "${prefix}Peak gain ${peakStr}x is very high",
                    "Peak sensitivity is ${peakStr}× base. Most players use 1.1–1.8× for competitive FPS."
                )
            )
        }
    }
}
