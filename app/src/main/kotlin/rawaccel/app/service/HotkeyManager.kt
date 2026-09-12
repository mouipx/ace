package rawaccel.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import rawaccel.app.driver.DriverClient
import rawaccel.app.model.AccelParams
import rawaccel.app.model.Profile
import rawaccel.app.model.Settings
import rawaccel.app.model.Vec2

internal data class HotkeyPresetDef(
    val label: String,
    val startIn: Double,
    val endIn: Double,
    val peak: Double,
    val yx: Double,
    val whole: Boolean? = null,
    val shape: ProfileEditorEngine.CurveShape = ProfileEditorEngine.CurveShape.SMOOTH,
    val verticalStartIn: Double = startIn,
    val verticalEndIn: Double = endIn,
    val verticalPeak: Double = peak,
    val verticalShape: ProfileEditorEngine.CurveShape = shape
)
internal val deadEndWeaponStages: List<String> = listOf(
    "PISTOL",
    "PISTOL + SHOTGUN",
    "SNIPER + SHOTGUN",
    "ZAPPER + ELDER GUN + GOLD DIGGER",
    "SNIPER FALLBACK (3-SLOT)"
)

internal val deadEndHotkeyPresetCycle: List<HotkeyPresetDef> = listOf(
    HotkeyPresetDef("PISTOL", 35.0, 125.0, 1.45, 0.95, whole = true, shape = ProfileEditorEngine.CurveShape.FAST_RAMP),
    HotkeyPresetDef("PISTOL + SHOTGUN", 55.0, 180.0, 1.35, 0.90, whole = false,
        shape = ProfileEditorEngine.CurveShape.SMOOTH,
        verticalStartIn = 85.0, verticalEndIn = 220.0, verticalPeak = 1.18,
        verticalShape = ProfileEditorEngine.CurveShape.SOFT_START),
    HotkeyPresetDef("SNIPER + SHOTGUN", 90.0, 240.0, 1.25, 0.92, whole = false,
        shape = ProfileEditorEngine.CurveShape.SMOOTH,
        verticalStartIn = 120.0, verticalEndIn = 300.0, verticalPeak = 1.10,
        verticalShape = ProfileEditorEngine.CurveShape.SOFT_START),
    HotkeyPresetDef(
        "ZAPPER + ELDER GUN + GOLD DIGGER",
        45.0,
        190.0,
        1.38,
        0.90,
        whole = false,
        shape = ProfileEditorEngine.CurveShape.FAST_RAMP,
        verticalStartIn = 75.0,
        verticalEndIn = 230.0,
        verticalPeak = 1.18,
        verticalShape = ProfileEditorEngine.CurveShape.SOFT_START
    ),
    HotkeyPresetDef(
        "SNIPER FALLBACK (3-SLOT)",
        110.0,
        300.0,
        1.12,
        1.00,
        whole = true,
        shape = ProfileEditorEngine.CurveShape.SMOOTH
    )
)

/**
 * Global hotkeys for in-game sens control — no alt-tab required mid-round.
 *
 * Because Raw Accel's driver is pure kernel-level input processing, the only way
 * to change behavior while a game is focused is to re-apply a modified settings
 * document through the driver IOCTL. This manager handles that.
 *
 * Safety notes:
 *  - Hotkeys use RegisterHotKey (global, OS-level), not low-level keyboard hooks.
 *    Anti-cheat has nothing to flag — only observable behaviour is a brief driver
 *    write through the signed rawaccel.sys.
 *  - All keys use MOD_NOREPEAT so holding a key doesn't spam.
 *  - If the bridge/driver isn't present, hotkeys stay unregistered.
 *  - JNA platform classes are only touched on Windows (loaded lazily via WinAccess).
 *  - Registration and message pumping happen on a dedicated daemon thread because
 *    WM_HOTKEY is thread-affine — it's delivered to the thread that registered it,
 *    and that thread must run a message loop.
 */
class HotkeyManager(
    private val client: DriverClient,
    @Suppress("unused") private val scope: CoroutineScope,
    private val onSettingsApplied: (Settings) -> Unit = {},
    private val sessionController: SessionController? = null
) {
    enum class Action {
        CLUTCH_LOW,
        CLUTCH_HIGH,
        TOGGLE_ACCEL,
        CYCLE_PRESET
    }

    data class Binding(
        val action: Action,
        val modifiers: Int,
        val vkey: Int,
        val id: Int
    )

    data class HotkeyState(
        val clutchLowActive: Boolean = false,
        val clutchHighActive: Boolean = false,
        val accelEnabled: Boolean = true,
        val lastAction: String = "—",
        val error: String? = null
    )

    private val _state = MutableStateFlow(HotkeyState())
    val state: StateFlow<HotkeyState> = _state

    @Volatile private var pumpThread: Thread? = null
    private val registered: MutableSet<Int> = LinkedHashSet()
    private var currentSettings: Settings? = null
    private var currentProfileIndex: Int = 0

    // Sens multipliers — exposed so the UI can wire sliders later.
    var clutchLowMultiplier: Double = 0.75
    var clutchHighMultiplier: Double = 1.35

    private var baselineProfile: Profile? = null
    private var baselineSettings: Settings? = null

    /** Virtual-key & modifier constants (mirroring WinUser.h). */
    private object Win {
        const val MOD_CONTROL = 0x0002
        const val MOD_SHIFT = 0x0004
        const val MOD_NOREPEAT = 0x4000
        const val WM_HOTKEY = 0x0312
        const val VK_F1 = 0x70
        const val VK_F2 = 0x71
        const val VK_F3 = 0x72
        const val PM_REMOVE = 0x0001
    }

    /** Lazy JNA access — only touched on Windows so Linux/macOS loads cleanly. */
    private class WinAccess {
        val user32: com.sun.jna.platform.win32.User32 = com.sun.jna.platform.win32.User32.INSTANCE
    }
    @Volatile private var win: WinAccess? = null

    /** Default Dead End hotkey layout: Ctrl+F1/F2/F3, Ctrl+Shift+F1. */
    val defaultBindings: List<Binding> = listOf(
        Binding(Action.CLUTCH_LOW,   Win.MOD_CONTROL or Win.MOD_NOREPEAT, Win.VK_F1, 1),
        Binding(Action.CLUTCH_HIGH,  Win.MOD_CONTROL or Win.MOD_NOREPEAT, Win.VK_F2, 2),
        Binding(Action.TOGGLE_ACCEL, Win.MOD_CONTROL or Win.MOD_NOREPEAT, Win.VK_F3, 3),
        Binding(Action.CYCLE_PRESET, Win.MOD_CONTROL or Win.MOD_SHIFT or Win.MOD_NOREPEAT, Win.VK_F1, 4),
    )

    private val isWindows: Boolean =
        System.getProperty("os.name").contains("Windows", ignoreCase = true)

    fun start(settings: Settings, profileIndex: Int = 0) {
        stop()
        if (!isWindows) {
            _state.value = HotkeyState(error = "Hotkeys require Windows")
            return
        }
        if (!client.canRead() || !client.isPresent()) {
            _state.value = HotkeyState(error = "Driver not available — hotkeys inactive")
            return
        }

        currentSettings = settings
        currentProfileIndex = profileIndex
        baselineSettings = settings
        baselineProfile = settings.profiles.getOrNull(profileIndex)
        presetCycleIndex = -1

        val thread = Thread({ runMessagePump() }, "Ace-HotkeyPump")
        thread.isDaemon = true
        pumpThread = thread
        thread.start()
    }

    fun updateBaseline(settings: Settings, profileIndex: Int = 0) {
        val s = _state.value
        if (!s.clutchLowActive && !s.clutchHighActive && s.accelEnabled) {
            currentSettings = settings
            baselineSettings = settings
            baselineProfile = settings.profiles.getOrNull(profileIndex)
            currentProfileIndex = profileIndex
        }
    }

    /** Establishes a fresh session baseline and clears any transient hotkey mode. */
    fun resetForSession(settings: Settings, profileIndex: Int = 0) {
        currentSettings = settings
        baselineSettings = settings
        baselineProfile = settings.profiles.getOrNull(profileIndex)
        currentProfileIndex = profileIndex
        presetCycleIndex = -1
        _state.value = if (pumpThread?.isAlive == true) {
            HotkeyState(lastAction = "HOTKEYS ARMED")
        } else {
            HotkeyState()
        }
    }

    private fun runMessagePump() {
        val w = WinAccess()
        win = w

        var ok = true
        defaultBindings.forEach { b ->
            if (!w.user32.RegisterHotKey(null, b.id, b.modifiers, b.vkey)) {
                ok = false
                _state.value = _state.value.copy(
                    error = "Hotkey id=${b.id} (Ctrl+F${b.id}) already in use — close other apps grabbing F1-F3")
            } else {
                synchronized(registered) { registered.add(b.id) }
            }
        }
        if (ok) _state.value = HotkeyState(lastAction = "HOTKEYS ARMED")

        val msg = com.sun.jna.platform.win32.WinUser.MSG()
        try {
            while (!Thread.currentThread().isInterrupted) {
                while (w.user32.PeekMessage(msg, null, Win.WM_HOTKEY, Win.WM_HOTKEY, Win.PM_REMOVE)) {
                    val id = msg.wParam.toInt()
                    val binding = defaultBindings.firstOrNull { it.id == id } ?: continue
                    handleAction(binding.action)
                }
                try {
                    Thread.sleep(15)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } finally {
            synchronized(registered) {
                registered.forEach { id -> w.user32.UnregisterHotKey(null, id) }
                registered.clear()
            }
            win = null
        }
    }

    private fun handleAction(action: Action) {
        when (action) {
            Action.CLUTCH_LOW -> toggleClutch(low = true)
            Action.CLUTCH_HIGH -> toggleClutch(high = true)
            Action.TOGGLE_ACCEL -> toggleAccel()
            Action.CYCLE_PRESET -> cyclePreset()
        }
    }

    private fun toggleClutch(low: Boolean = false, high: Boolean = false) {
        val base = baselineSettings ?: return
        val baseProfile = baselineProfile ?: return
        val s = _state.value

        val wantActive: Boolean
        val multiplier: Double
        when {
            low -> {
                wantActive = !s.clutchLowActive
                multiplier = if (wantActive) clutchLowMultiplier else 1.0
            }
            high -> {
                wantActive = !s.clutchHighActive
                multiplier = if (wantActive) clutchHighMultiplier else 1.0
            }
            else -> { wantActive = false; multiplier = 1.0 }
        }

        val newProfile = baseProfile.copy(
            outputDpi = baseProfile.outputDpi * multiplier,
            accelX = scaleAccel(baseProfile.accelX, multiplier),
            accelY = scaleAccel(baseProfile.accelY, multiplier)
        )
        val newSettings = replaceProfile(base, newProfile, currentProfileIndex)
        applySettings("hotkey clutch", newSettings).onSuccess {
            onSettingsApplied(newSettings)
            _state.value = s.copy(
                clutchLowActive = low && wantActive,
                clutchHighActive = high && wantActive,
                accelEnabled = true,
                lastAction = when {
                    low && wantActive -> "CLUTCH LOW (${"%.0f".format(multiplier * 100)}%)"
                    high && wantActive -> "CLUTCH HIGH (${"%.0f".format(multiplier * 100)}%)"
                    else -> "CLUTCH RELEASED"
                },
                error = null
            )
        }.onFailure {
            _state.value = s.copy(error = "Clutch apply failed: ${it.message}")
        }
    }

    private fun toggleAccel() {
        val base = baselineSettings ?: return
        val baseProfile = baselineProfile ?: return
        val s = _state.value
        val wantOn = !s.accelEnabled
        val newProfile = if (wantOn) {
            baseProfile
        } else {
            baseProfile.copy(
                accelX = baseProfile.accelX.copy(mode = "noaccel", data = emptyList()),
                accelY = baseProfile.accelY.copy(mode = "noaccel", data = emptyList())
            )
        }
        val newSettings = replaceProfile(base, newProfile, currentProfileIndex)
        applySettings("hotkey toggle", newSettings).onSuccess {
            onSettingsApplied(newSettings)
            _state.value = s.copy(
                accelEnabled = wantOn,
                clutchLowActive = false,
                clutchHighActive = false,
                lastAction = if (wantOn) "ACCEL ON" else "ACCEL OFF (RAW 1:1)",
                error = null
            )
        }.onFailure {
            _state.value = s.copy(error = "Toggle failed: ${it.message}")
        }
    }

    private var presetCycleIndex = -1
    private fun cyclePreset() {
        val base = baselineSettings ?: return
        val baseProfile = baselineProfile ?: return
        val s = _state.value

        presetCycleIndex = (presetCycleIndex + 1) % deadEndHotkeyPresetCycle.size
        val def = deadEndHotkeyPresetCycle[presetCycleIndex]
        val wholeMode = def.whole ?: baseProfile.speed.whole
        val newProfile = baseProfile.copy(
            accelX = retuneLut(baseProfile.accelX, def),
            accelY = if (wholeMode) noaccel() else retuneVerticalLut(baseProfile.accelY, def),
            speed = baseProfile.speed.copy(whole = wholeMode),
            yxRatio = def.yx
        )
        val newSettings = replaceProfile(base, newProfile, currentProfileIndex)
        applySettings("hotkey preset", newSettings).onSuccess {
            onSettingsApplied(newSettings)
            baselineProfile = newProfile
            _state.value = s.copy(
                clutchLowActive = false,
                clutchHighActive = false,
                accelEnabled = true,
                lastAction = "PRESET → ${def.label}",
                error = null
            )
        }.onFailure {
            _state.value = s.copy(error = "Preset failed: ${it.message}")
        }
    }

    private fun scaleAccel(a: AccelParams, m: Double): AccelParams {
        if (a.mode != "lut" || a.data.isEmpty()) return a
        val scaled = a.data.mapIndexed { i, v -> if (i % 2 == 1) v * m else v }
        return a.copy(data = scaled, cap = Vec2(a.cap.x, a.cap.y * m))
    }

    private fun noaccel() = AccelParams(mode = "noaccel", gain = true)

    private fun retuneLut(a: AccelParams, def: HotkeyPresetDef): AccelParams {
        val points = ProfileEditorEngine.generate(def.startIn, def.endIn, def.peak, def.shape)
        return a.copy(mode = "lut", gain = true, data = ProfileEditorEngine.flatten(points))
    }

    private fun retuneVerticalLut(a: AccelParams, def: HotkeyPresetDef): AccelParams {
        val points = ProfileEditorEngine.generate(
            def.verticalStartIn,
            def.verticalEndIn,
            def.verticalPeak,
            def.verticalShape
        )
        return a.copy(mode = "lut", gain = true, data = ProfileEditorEngine.flatten(points))
    }

    private fun replaceProfile(s: Settings, newProfile: Profile, index: Int): Settings {
        val newProfiles = s.profiles.toMutableList()
        if (index in newProfiles.indices) newProfiles[index] = newProfile
        else newProfiles.add(newProfile)
        return s.copy(profiles = newProfiles)
    }

    /** Re-apply the stored baseline (called by the app on close/disarm). */
    fun releaseAll() {
        val base = baselineSettings ?: return
        applySettings("restore baseline", base).onSuccess { onSettingsApplied(base) }
        _state.value = HotkeyState(lastAction = "RESTORED BASELINE")
    }

    private fun applySettings(label: String, settings: Settings): Result<Unit> =
        if (sessionController == null) {
            client.apply(settings)
        } else {
            runBlocking { sessionController.run(label) { client.apply(settings) } }
        }

    fun stop() {
        val t = pumpThread
        if (t != null && t.isAlive) {
            t.interrupt()
            try { t.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        pumpThread = null
        win = null
        synchronized(registered) { registered.clear() }
    }
}
