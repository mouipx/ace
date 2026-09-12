package rawaccel.app.service

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rawaccel.app.driver.DriverClient
import rawaccel.app.model.Settings
import java.io.File

/**
 * Watches the foreground window and auto-applies a matching profile when a
 * known game process (Minecraft / Lunar / Badlion / Feather) becomes active.
 *
 * On focus-loss (switching to a non-game window), the "desktop" profile
 * (typically raw 1:1 at the same DPI) is applied so desktop pointing feels normal.
 *
 * All JNA platform access is guarded behind an `isWindows` check so the class
 * can be loaded on Linux/macOS for development without failing at import time.
 */
class ForegroundWatcher(
    private val client: DriverClient,
    private val scope: CoroutineScope,
    @Suppress("unused") private val profilesDir: File,
    private val onSettingsApplied: (Settings) -> Unit = {},
    private val sessionController: SessionController? = null
) {
    data class GameRule(
        val exeName: String,
        val displayName: String
    )

    data class WatchState(
        val running: Boolean = false,
        val activeGame: String? = null,
        val activeExe: String? = null,
        val lastSwitchedTo: String? = null,
        val error: String? = null
    )

    /** Known Minecraft client exes that Hypixel players use. Case-insensitive. */
    val knownGames: List<GameRule> = listOf(
        GameRule("javaw.exe",              "Minecraft Java"),
        GameRule("java.exe",               "Minecraft Java"),
        GameRule("lunarclient.exe",        "Lunar Client"),
        GameRule("badlion client.exe",     "Badlion Client"),
        GameRule("feather.exe",            "Feather Client"),
        GameRule("feather client.exe",     "Feather Client"),
        GameRule("minecraft.exe",          "Minecraft (Bedrock)"),
        GameRule("minecraftlauncher.exe",  "Minecraft Launcher"),
        GameRule("labymod.exe",            "LabyMod"),
        GameRule("skyclient.exe",          "SkyClient"),
    )

    private val isWindows: Boolean =
        System.getProperty("os.name").contains("Windows", ignoreCase = true)

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state

    private var job: Job? = null
    private var baselineSettings: Settings? = null
    private var gameSettings: Settings? = null
    private var lastExe: String? = null

    fun start(desktopSettings: Settings, gameProfileSettings: Settings? = null) {
        stop()
        if (!isWindows) {
            _state.value = WatchState(error = "Auto-switch requires Windows")
            return
        }
        if (!client.canRead() || !client.isPresent()) {
            _state.value = WatchState(error = "Driver not available — auto-switch inactive")
            return
        }
        baselineSettings = desktopSettings
        gameSettings = gameProfileSettings
        _state.value = WatchState(running = true)
        job = scope.launch {
            while (isActive) {
                val exe = foregroundProcessName()
                if (exe != null && exe != lastExe) {
                    lastExe = exe
                    onForegroundChanged(exe)
                }
                delay(400)
            }
        }
    }

    fun setGameProfile(settings: Settings) {
        gameSettings = settings
    }

    private suspend fun onForegroundChanged(exe: String) {
        val rule = knownGames.firstOrNull { it.exeName.equals(exe, ignoreCase = true) }
        val current = _state.value
        if (rule != null && gameSettings != null) {
            applySettings("foreground game", gameSettings!!).onSuccess {
                onSettingsApplied(gameSettings!!)
                _state.value = current.copy(
                    running = true,
                    activeGame = rule.displayName,
                    activeExe = exe,
                    lastSwitchedTo = rule.displayName,
                    error = null
                )
            }.onFailure {
                _state.value = current.copy(activeExe = exe, error = "Switch failed: ${it.message}")
            }
        } else if (rule == null && baselineSettings != null) {
            applySettings("foreground desktop", baselineSettings!!).onSuccess {
                onSettingsApplied(baselineSettings!!)
                _state.value = current.copy(
                    running = true,
                    activeGame = null,
                    activeExe = exe,
                    lastSwitchedTo = "desktop",
                    error = null
                )
            }
        } else {
            _state.value = current.copy(activeExe = exe, activeGame = rule?.displayName)
        }
    }

    private suspend fun applySettings(label: String, settings: Settings): Result<Unit> =
        if (sessionController == null) {
            client.apply(settings)
        } else {
            sessionController.run(label) { client.apply(settings) }
        }

    /**
     * Read the .exe filename of the current foreground window's owning process.
     *
     * Access-right constants follow WinNT.h names but are defined as lowercase
     * `private const val`s at file scope to keep Kotlin's naming inspector happy
     * (UPPER_SNAKE_CASE locals trigger lint, even though they mirror Win32 macros).
     */
    private fun foregroundProcessName(): String? = runCatching {
        val user32 = User32.INSTANCE
        val hwnd: HWND = user32.GetForegroundWindow() ?: return null
        val pid = IntByReference()
        user32.GetWindowThreadProcessId(hwnd, pid)
        if (pid.value == 0) return null

        val kernel = Kernel32.INSTANCE
        var process = kernel.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid.value)
        if (process == null) {
            process = kernel.OpenProcess(PROCESS_QUERY_INFORMATION or PROCESS_VM_READ, false, pid.value)
        }
        process ?: return null
        try {
            val path = CharArray(1024)
            val psapi = com.sun.jna.platform.win32.Psapi.INSTANCE
            val len = psapi.GetModuleFileNameExW(process, null, path, path.size)
            if (len > 0) File(String(path, 0, len)).name.lowercase() else null
        } finally {
            kernel.CloseHandle(process)
        }
    }.getOrNull()

    fun stop() {
        job?.cancel()
        job = null
        _state.value = WatchState()
        lastExe = null
    }

    private companion object {
        // Win32 process access rights — numeric constants match WinNT.h.
        private const val PROCESS_QUERY_INFORMATION = 0x0400
        private const val PROCESS_VM_READ = 0x0010
        private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    }
}
