package rawaccel.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rawaccel.app.driver.DriverClient
import rawaccel.app.model.DeviceSettings
import rawaccel.app.model.Profile
import rawaccel.app.model.Settings
import rawaccel.app.service.ConfigDoctor
import rawaccel.app.service.CurveEngine
import rawaccel.app.service.DeviceInspector
import rawaccel.app.service.ForegroundWatcher
import rawaccel.app.service.HotkeyManager
import rawaccel.app.service.PresetLibrary
import rawaccel.app.service.ProfileEditorEngine
import rawaccel.app.service.ProfileManager
import rawaccel.app.service.SensitivityCalculator
import rawaccel.app.service.SessionManager
import rawaccel.app.service.Watchdog
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// ═════════════════════════════════════════════════════════════════════════════
//  ACE TERMINAL  ·  BONE / CHARCOAL EDITORIAL THEME
//  Visual layer only — every driver / profile / session function is untouched.
// ═════════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
//  PALETTE  (same property names — retuned to bone-on-charcoal)
// ─────────────────────────────────────────────────────────────────────────────

private object Cyber {
    val abyss       = Color(0xFF0C0C0B)   // charcoal paper-black
    val deepVoid    = Color(0xFF090908)
    val bgGrad1     = Color(0xFF121110)
    val bgGrad2     = Color(0xFF0E0E0D)
    val panel       = Color(0xFF100F0E)
    val panelHi     = Color(0xFF1B1A18)

    val border      = Color(0xFF514D46)
    val borderSoft  = Color(0xFF2C2A26)

    val cyan        = Color(0xFFEDE7DA)   // primary bone
    val cyanDim     = Color(0xFF9A9488)
    val blue        = Color(0xFFD9D3C6)
    val violet      = Color(0xFFB8B1A4)
    val magenta     = Color(0xFFFFFFFF)   // hot highlight
    val green       = Color(0xFFB9CFB0)   // muted sage — OK states
    val amber       = Color(0xFFD9A441)   // dusty amber — warnings
    val red         = Color(0xFFCF5334)   // rust — errors

    val textPrimary = Color(0xFFEDE7DA)
    val textDim     = Color(0xFF9A9488)
    val textMuted   = Color(0xFF6A645B)
    val textFaint   = Color(0xFF3B3833)
}

/** Everything in this UI is terminal type. */
private val Mono = FontFamily.Monospace

// Soft-rounded 1px outlines, like the reference boards.
private val shapePanel  = RoundedCornerShape(12.dp)
private val shapeCard   = RoundedCornerShape(9.dp)
private val shapeButton = RoundedCornerShape(8.dp)

private const val CHEV = ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"

// ─────────────────────────────────────────────────────────────────────────────
//  NAV TABS  (enum + fields unchanged)
// ─────────────────────────────────────────────────────────────────────────────

private enum class NavTab(val label: String, val icon: String) {
    DASHBOARD("SUMMARY", "001"),
    CURVE    ("CURVE",   "002"),
    EDITOR   ("EDITOR",  "003"),
    PRESETS  ("PRESETS", "004"),
    DEVICES  ("DEVICES", "005"),
    SESSION  ("SESSION", "006"),
    DOCTOR   ("DOCTOR",  "007"),
    LOGS     ("LOGS",    "008")
}

// ─────────────────────────────────────────────────────────────────────────────
//  ENTRY
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun App(
    client: DriverClient,
    profileManager: ProfileManager,
    sessionManager: SessionManager,
    watchdog: Watchdog,
    hotkeyManager: HotkeyManager,
    foregroundWatcher: ForegroundWatcher
) {
    MaterialTheme(
        colors = darkColors(
            primary        = Cyber.cyan,
            primaryVariant = Cyber.violet,
            secondary      = Cyber.magenta,
            background     = Cyber.abyss,
            surface        = Cyber.panel,
            onPrimary      = Cyber.abyss,
            onSecondary    = Cyber.abyss,
            onBackground   = Cyber.textPrimary,
            onSurface      = Cyber.textPrimary,
            error          = Cyber.red
        )
    ) {
        var driverPresent  by remember { mutableStateOf<Boolean?>(null) }
        var driverVersion  by remember { mutableStateOf("—") }
        var profiles       by remember { mutableStateOf(listOf<ProfileManager.Entry>()) }
        var selected       by remember { mutableStateOf<ProfileManager.Entry?>(null) }
        var deviceReport   by remember { mutableStateOf<DeviceInspector.Report?>(null) }
        var log            by remember { mutableStateOf(listOf<String>()) }
        var activeTab      by remember { mutableStateOf(NavTab.DASHBOARD) }
        var pendingDelete  by remember { mutableStateOf<ProfileManager.Entry?>(null) }
        var pendingRename  by remember { mutableStateOf<ProfileManager.Entry?>(null) }
        var renameText     by remember { mutableStateOf("") }
        val uiScope        = rememberCoroutineScope()
        val watchdogStatus by watchdog.status.collectAsState()
        val hotkeyState by hotkeyManager.state.collectAsState()
        val autoSwitchState by foregroundWatcher.state.collectAsState()

        fun logLine(msg: String) {
            log = (log + "[${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}] $msg")
                .takeLast(200)
        }

        LaunchedEffect(Unit) {
            logLine("ACE VERSION -> ${System.getProperty("jpackage.app-version", "dev")}")
            logLine(if (sessionManager.isAdministrator()) {
                "ADMIN CHECK -> elevated"
            } else {
                "ADMIN CHECK -> not elevated; driver setup/Session power changes may need Administrator"
            })
            driverPresent = withContext(Dispatchers.IO) { client.isPresent() }
            driverVersion = withContext(Dispatchers.IO) { client.version().getOrElse { "unavailable" } }
            if (driverVersion == "unavailable") {
                logLine("DRIVER CHECK -> unavailable; install/start the signed Raw Accel driver, then reboot if Windows requests it")
            } else {
                logLine("DRIVER CHECK -> $driverVersion; bridge minimum is v1.7.0")
            }
            val scan = withContext(Dispatchers.IO) { profileManager.scan() }
            val activeDriverSettings = withContext(Dispatchers.IO) { client.read().getOrNull() }
            val activeDriverEntry = ProfileManager.matchingActiveDriverEntry(scan.entries, activeDriverSettings)
            profiles = scan.entries
            selected = activeDriverEntry ?: scan.entries.firstOrNull()
            activeDriverEntry?.let {
                logLine("ACTIVE DRIVER MATCH -> selected ${it.displayName}")
            } ?: selected?.let {
                logLine("DEFAULT PROFILE SELECTED -> ${it.displayName}")
            }
            scan.errors.forEach { error ->
                logLine("✗ PROFILE SKIPPED → ${error.file.name}: ${error.message}")
            }
        }

        LaunchedEffect(selected) {
            val s = selected?.settings
            deviceReport = null
            if (s != null) deviceReport = withContext(Dispatchers.IO) { DeviceInspector.inspect(s) }
        }

        fun refresh() {
            uiScope.launch {
                driverPresent = withContext(Dispatchers.IO) { client.isPresent() }
                driverVersion = withContext(Dispatchers.IO) { client.version().getOrElse { "unavailable" } }
                val scan = withContext(Dispatchers.IO) { profileManager.scan() }
                profiles = scan.entries
                selected = selected?.let { current -> scan.entries.firstOrNull { it.file == current.file } }
                    ?: scan.entries.firstOrNull()
                scan.errors.forEach { error ->
                    logLine("✗ PROFILE SKIPPED → ${error.file.name}: ${error.message}")
                }
                logLine("↻ refreshed · ${scan.entries.size} valid · ${scan.errors.size} invalid")
            }
        }

        fun importProfile() {
            val source = chooseJsonFile(open = true) ?: return
            uiScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { profileManager.importProfile(source) } }
                result.fold(
                    { imported ->
                        profiles = (profiles + imported).sortedBy { it.displayName.lowercase() }
                        selected = imported
                        logLine("✓ PROFILE IMPORTED → ${imported.displayName}")
                    },
                    { error -> logLine("✗ IMPORT FAILED → ${error.message}") }
                )
            }
        }

        fun duplicateSelected() {
            val entry = selected ?: return
            uiScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { profileManager.duplicate(entry) } }
                result.fold(
                    { duplicate ->
                        profiles = (profiles + duplicate).sortedBy { it.displayName.lowercase() }
                        selected = duplicate
                        logLine("✓ PROFILE DUPLICATED → ${duplicate.displayName}")
                    },
                    { error -> logLine("✗ DUPLICATE FAILED → ${error.message}") }
                )
            }
        }

        fun exportSelected() {
            val entry = selected ?: return
            val destination = chooseJsonFile(open = false, suggestedName = entry.file.name) ?: return
            uiScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { profileManager.export(entry, destination) } }
                result.fold(
                    { exported -> logLine("✓ PROFILE EXPORTED → ${exported.absolutePath}") },
                    { error -> logLine("✗ EXPORT FAILED → ${error.message}") }
                )
            }
        }

        fun restoreSelected() {
            val entry = selected ?: return
            uiScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { profileManager.restoreBackup(entry) } }
                result.fold(
                    { restored ->
                        profiles = profiles.map { if (it.file == restored.file) restored else it }
                        selected = restored
                        logLine("✓ BACKUP RESTORED → ${restored.displayName}; click APPLY to activate it")
                    },
                    { error -> logLine("✗ RESTORE FAILED → ${error.message}") }
                )
            }
        }

        fun renameConfirmed(entry: ProfileManager.Entry) {
            val requestedName = renameText
            pendingRename = null
            uiScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { profileManager.rename(entry, requestedName) } }
                result.fold(
                    { renamed ->
                        profiles = profiles.map { if (it.file == entry.file) renamed else it }
                            .sortedBy { it.displayName.lowercase() }
                        selected = renamed
                        logLine("✓ PROFILE RENAMED → ${renamed.displayName}")
                    },
                    { error -> logLine("✗ RENAME FAILED → ${error.message}") }
                )
            }
        }

        fun deleteConfirmed(entry: ProfileManager.Entry) {
            pendingDelete = null
            uiScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { profileManager.delete(entry) } }
                result.fold(
                    { trashFile ->
                        if (selected?.file == entry.file) watchdog.stop()
                        val remaining = profiles.filterNot { it.file == entry.file }
                        profiles = remaining
                        selected = remaining.firstOrNull()
                        logLine("✓ PROFILE MOVED TO TRASH → ${trashFile.name}")
                    },
                    { error -> logLine("✗ DELETE FAILED → ${error.message}") }
                )
            }
        }

        fun applySelected() {
            val entry = selected ?: return
            val targets = deviceReport
            uiScope.launch {
                val r = withContext(Dispatchers.IO) { client.apply(entry.settings) }
                if (r.isSuccess) {
                    logLine("✓ APPLIED + VERIFIED → ${entry.displayName}")
                    if (targets?.defaultDevicesDisabled == true && targets.targets.none { it.connected }) {
                        logLine("⚠ NO CONFIGURED MOUSE IS CONNECTED → profile is loaded but acceleration is disabled; open DEVICES and select USE THIS MOUSE")
                    }
                    watchdog.start(entry.settings)
                    // Also update hotkey/auto-switch baseline so clutch toggles return to this profile
                    val profileIdx = 0
                    hotkeyManager.updateBaseline(entry.settings, profileIdx)
                    foregroundWatcher.setGameProfile(entry.settings)
                } else logLine("✗ APPLY FAILED → ${r.exceptionOrNull()?.message}")
            }
        }

        fun resetDriver() {
            uiScope.launch {
                val r = withContext(Dispatchers.IO) { client.reset() }
                if (r.isSuccess) logLine("✓ ACCEL CLEARED") else logLine("✗ RESET FAILED → ${r.exceptionOrNull()?.message}")
                watchdog.stop()
            }
        }

        fun armHotkeys() {
            val entry = selected ?: run { logLine("⚠ HOTKEYS need a profile selected"); return }
            // Make sure profile is applied first
            uiScope.launch {
                client.apply(entry.settings).onSuccess {
                    hotkeyManager.start(entry.settings)
                    logLine("HOTKEY CYCLE -> pistol / pistol+shotgun / sniper+shotgun / zapper+elder+gold / sniper fallback")
                    logLine("✓ HOTKEYS ARMED → Ctrl+F1 low-sens, Ctrl+F2 high-sens, Ctrl+F3 accel on/off, Ctrl+Shift+F1 preset cycle")
                }.onFailure {
                    logLine("✗ HOTKEYS FAILED → ${it.message}")
                }
            }
        }

        fun disarmHotkeys() {
            hotkeyManager.releaseAll()
            hotkeyManager.stop()
            logLine("✓ HOTKEYS DISARMED")
        }

        fun toggleAutoSwitch(enabled: Boolean) {
            val entry = selected
            if (enabled) {
                if (entry == null) { logLine("⚠ AUTO-SWITCH needs a game profile selected"); return }
                // Desktop profile: noaccel 1:1 at the same DPI
                val desktop = entry.settings.copy(
                    profiles = entry.settings.profiles.map {
                        it.copy(
                            accelX = it.accelX.copy(mode = "noaccel", data = emptyList()),
                            accelY = it.accelY.copy(mode = "noaccel", data = emptyList())
                        )
                    }
                )
                foregroundWatcher.start(desktop, entry.settings)
                logLine("✓ AUTO-SWITCH ENABLED → will apply ${entry.displayName} when Minecraft is focused, raw on desktop")
            } else {
                foregroundWatcher.stop()
                logLine("✓ AUTO-SWITCH STOPPED")
            }
        }

        fun loadPreset(preset: PresetLibrary.Preset) {
            // Find current device id for the selected mouse
            val current = selected?.settings
            val existingDevice = current?.devices?.firstOrNull()
            val newSettings = PresetLibrary.toSettings(
                preset,
                deviceName = existingDevice?.name ?: "Logitech USB Receiver",
                deviceId = existingDevice?.id ?: "HID\\VID_046D&PID_C539&MI_01&Col01"
            )
            // Presets land next to the other profiles (the profiles directory).
            val profilesDir = selected?.file?.parentFile ?: ProfileManager.defaultDir()
            uiScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val dest = File(profilesDir, "${preset.id}.json")
                        profileManager.save(newSettings, dest)
                        dest
                    }
                }
                result.fold(
                    { file ->
                        val imported = ProfileManager.Entry(file, newSettings)
                        profiles = (profiles + imported).sortedBy { it.displayName.lowercase() }
                        selected = imported
                        logLine("✓ PRESET LOADED → ${preset.name}")
                    },
                    { error -> logLine("✗ PRESET FAILED → ${error.message}") }
                )
            }
        }

        fun queryDriver() {
            if (!client.canRead()) { logLine("⚠ QUERY unavailable: ${client.bridgeError() ?: ""}".trim()); return }
            uiScope.launch {
                val r = withContext(Dispatchers.IO) { client.read() }
                r.fold(
                    { s ->
                        val mappings = s.devices.joinToString { "${it.name.ifBlank { it.id }} → ${it.profile}" }
                        logLine("⬢ DRIVER holds ${s.profiles.size} profile(s): ${s.profiles.joinToString { it.name }}")
                        logLine("⬢ DRIVER device mappings: ${mappings.ifBlank { "none (default device rule applies)" }}")
                    },
                    { e -> logLine("✗ QUERY FAILED → ${e.message}") }
                )
            }
        }

        fun runSession() {
            val entry = selected ?: run {
                logLine("⚠ SESSION unavailable: select a profile first")
                return
            }
            uiScope.launch {
                val applied = withContext(Dispatchers.IO) { client.apply(entry.settings) }
                if (applied.isFailure) {
                    logLine("✗ SESSION ABORTED → apply failed: ${applied.exceptionOrNull()?.message}")
                    return@launch
                }

                watchdog.start(entry.settings)
                logLine("✓ SESSION PROFILE → ${entry.displayName}")
                val r = withContext(Dispatchers.IO) { sessionManager.runSession() }
                logLine("⚡ SESSION → power=${r.powerPlan}")
            }
        }

        fun useMouseForSelectedProfile(mouse: DeviceInspector.ConnectedMouse, requestedProfileName: String) {
            val entry = selected ?: return
            val settings = entry.settings
            val profileName = settings.profiles.firstOrNull { it.name == requestedProfileName }?.name
            if (profileName == null) { logLine("✗ TARGET SAVE FAILED: selected internal profile no longer exists"); return }
            val targetId = DeviceInspector.rawAccelDeviceId(mouse.instanceId)
            val existingIndex = settings.devices.indexOfFirst { it.id.equals(targetId, ignoreCase = true) }
            val existing = settings.devices.getOrNull(existingIndex)
            val target = DeviceSettings(
                name    = DeviceInspector.targetName(mouse),
                profile = profileName,
                id      = targetId,
                config  = (existing?.config ?: settings.defaultDeviceConfig).copy(disable = false)
            )
            val updatedDevices = settings.devices.toMutableList().apply {
                if (existingIndex >= 0) set(existingIndex, target) else add(target)
            }
            val updated = settings.copy(
                defaultDeviceConfig = settings.defaultDeviceConfig.copy(disable = true),
                devices = updatedDevices
            )
            uiScope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { profileManager.save(updated, entry.file) } }
                if (r.isSuccess) {
                    val e = ProfileManager.Entry(entry.file, updated)
                    profiles = profiles.map { if (it.file == entry.file) e else it }
                    selected = e
                    logLine("✓ TARGET SAVED → ${target.name}")
                } else logLine("✗ TARGET SAVE FAILED → ${r.exceptionOrNull()?.message}")
            }
        }

        fun saveEditedSettings(updated: Settings) {
            val entry = selected ?: return
            uiScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { profileManager.save(updated, entry.file) } }
                result.fold(
                    {
                        val saved = ProfileManager.Entry(entry.file, updated)
                        profiles = profiles.map { if (it.file == entry.file) saved else it }
                        selected = saved
                        logLine("✓ EDITOR SAVED → ${entry.displayName}; use APPLY when ready")
                    },
                    { error -> logLine("✗ EDITOR SAVE FAILED → ${error.message}") }
                )
            }
        }

        val statusColor = when (driverPresent) {
            true  -> Cyber.green
            false -> Cyber.red
            null  -> Cyber.amber
        }
        val statusText = when (driverPresent) {
            true  -> "ONLINE"
            false -> if (client.isBridgeAvailable()) "OFFLINE" else "NO BRIDGE"
            null  -> "INIT"
        }

        pendingRename?.let { entry ->
            AlertDialog(
                onDismissRequest = { pendingRename = null },
                title = { Text("Rename profile bank") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("File name") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { renameConfirmed(entry) }, enabled = renameText.isNotBlank()) {
                        Text("RENAME", color = Cyber.cyan)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRename = null }) { Text("CANCEL") }
                }
            )
        }

        pendingDelete?.let { entry ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete ${entry.displayName}?") },
                text = { Text("The profile and its backup will be moved into the recoverable .trash folder.") },
                confirmButton = {
                    TextButton(onClick = { deleteConfirmed(entry) }) {
                        Text("MOVE TO TRASH", color = Cyber.red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("CANCEL") }
                }
            )
        }

        Column(
            Modifier.fillMaxSize()
                .background(Cyber.abyss)
                .background(
                    Brush.verticalGradient(
                        listOf(Cyber.abyss, Cyber.bgGrad1, Cyber.bgGrad2, Cyber.abyss)
                    )
                )
                .globalScanlines()
                .padding(10.dp)
        ) {
            BiosTitleBar(driverVersion, statusText, statusColor)
            Spacer(Modifier.height(8.dp))
            TabStrip(activeTab) { activeTab = it }
            Spacer(Modifier.height(8.dp))

            Column(Modifier.fillMaxWidth().weight(1f)) {
                TopBar(
                    selected      = selected,
                    driverVersion = driverVersion,
                    statusText    = statusText,
                    statusColor   = statusColor,
                    watchdog      = watchdogStatus,
                    onRefresh     = ::refresh
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (activeTab) {
                        NavTab.DASHBOARD -> DashboardView(
                            profiles       = profiles,
                            selected       = selected,
                            deviceReport   = deviceReport,
                            watchdog       = watchdogStatus,
                            onSelect       = { selected = it },
                            onApply        = ::applySelected,
                            onQuery        = ::queryDriver,
                            onReset        = ::resetDriver,
                            onSession      = ::runSession,
                            onImport       = ::importProfile,
                            onDuplicate    = ::duplicateSelected,
                            onExport       = ::exportSelected,
                            onRestore      = ::restoreSelected,
                            onRename       = {
                                selected?.let {
                                    renameText = it.displayName
                                    pendingRename = it
                                }
                            },
                            onDelete       = { selected?.let { pendingDelete = it } },
                            canRestore     = selected?.let(profileManager::hasBackup) == true
                        )
                        NavTab.CURVE     -> CurveView(selected)
                        NavTab.EDITOR    -> ProfileEditorView(selected, ::saveEditedSettings)
                        NavTab.PRESETS   -> PresetsView(selected, ::loadPreset)
                        NavTab.DEVICES   -> DevicesView(selected?.settings, deviceReport, ::useMouseForSelectedProfile)
                        NavTab.SESSION   -> SessionView(
                            onRun = ::runSession,
                            watchdog = watchdogStatus,
                            hotkeyState = hotkeyState,
                            autoSwitchState = autoSwitchState,
                            enabled = selected != null,
                            onArmHotkeys = ::armHotkeys,
                            onDisarmHotkeys = ::disarmHotkeys,
                            onToggleAutoSwitch = ::toggleAutoSwitch,
                            selectedProfile = selected
                        )
                        NavTab.DOCTOR    -> DoctorView(selected)
                        NavTab.LOGS      -> LogsView(log)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            BiosFooter(
                profileCount = profiles.size,
                miceCount    = deviceReport?.connectedMice?.size ?: 0,
                watchdog     = watchdogStatus,
                statusText   = statusText,
                statusColor  = statusColor,
                tab          = activeTab
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  FILE PICKERS
// ─────────────────────────────────────────────────────────────────────────────

private fun chooseJsonFile(open: Boolean, suggestedName: String? = null): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = if (open) "Import Raw Accel profile" else "Export Raw Accel profile"
        fileFilter = FileNameExtensionFilter("Raw Accel profiles (*.json)", "json")
        isAcceptAllFileFilterUsed = false
        if (!open && suggestedName != null) selectedFile = File(suggestedName)
    }
    val result = if (open) chooser.showOpenDialog(null) else chooser.showSaveDialog(null)
    return chooser.selectedFile.takeIf { result == JFileChooser.APPROVE_OPTION }
}

// ─────────────────────────────────────────────────────────────────────────────
//  BACKDROP  (very faint paper grain lines — static)
// ─────────────────────────────────────────────────────────────────────────────

private fun Modifier.globalScanlines(): Modifier = this.drawBehind {
    var y = 0f
    val spacing = 3.dp.toPx()
    while (y < size.height) {
        drawLine(Cyber.cyan.copy(alpha = 0.016f), Offset(0f, y), Offset(size.width, y), 1f)
        y += spacing
    }
}

/** 1px rounded outline, optional bracket ticks. */
private fun Modifier.biosFrame(
    color: Color = Cyber.border,
    tickColor: Color = Cyber.cyan,
    ticks: Boolean = false,
    radius: Dp = 12.dp
): Modifier = this.drawBehind {
    val r = radius.toPx()
    drawRoundRect(color, style = Stroke(width = 1f), cornerRadius = CornerRadius(r, r))
    if (ticks) {
        val t = 10.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(tickColor, Offset(r, 0f), Offset(r + t, 0f), 1.6f)
        drawLine(tickColor, Offset(w - r, 0f), Offset(w - r - t, 0f), 1.6f)
        drawLine(tickColor, Offset(r, h), Offset(r + t, h), 1.6f)
        drawLine(tickColor, Offset(w - r, h), Offset(w - r - t, h), 1.6f)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TEXT PRIMITIVES
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TermText(
    text: String,
    color: Color,
    size: Int = 11,
    weight: FontWeight = FontWeight.Normal,
    spacing: Double = 0.8,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    Text(
        text,
        color = color,
        fontFamily = Mono,
        fontSize = size.sp,
        fontWeight = weight,
        letterSpacing = spacing.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** Katakana / CJK chips use the system sans so glyphs resolve on Windows. */
@Composable
private fun CjkText(text: String, color: Color, size: Int = 11, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontFamily = FontFamily.SansSerif,
        fontSize = size.sp,
        letterSpacing = 2.sp,
        maxLines = 1,
        modifier = modifier
    )
}

/** > <line> — indented terminal line. */
@Composable
private fun EchoLine(text: String, color: Color = Cyber.textDim, size: Int = 10) {
    Row(Modifier.fillMaxWidth()) {
        TermText("> ", Cyber.textFaint, size)
        TermText(text, color, size, modifier = Modifier.weight(1f))
    }
}

/** >>>>>>>>>>>> chevron rule from the reference boards. */
@Composable
private fun ChevronRule(color: Color = Cyber.textFaint, size: Int = 9) {
    TermText(CHEV, color, size, spacing = 0.0, modifier = Modifier.fillMaxWidth())
}

/** LABEL......VALUE dot-leader row. */
@Composable
private fun DotRow(
    label: String,
    value: String,
    valueColor: Color = Cyber.cyan,
    labelColor: Color = Cyber.textDim,
    textSize: Int = 10
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TermText(label, labelColor, textSize, weight = FontWeight.Bold, spacing = 1.1)
        Box(
            Modifier.weight(1f).height(12.dp).padding(horizontal = 5.dp)
                .drawBehind {
                    val y = size.height * 0.72f
                    drawLine(
                        Cyber.textFaint, Offset(0f, y), Offset(size.width, y), 1.4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.4f, 3.4f))
                    )
                }
        )
        TermText(value, valueColor, textSize + 1, weight = FontWeight.Bold, spacing = 0.6)
    }
}

/** [████████·······] block meter. */
@Composable
private fun BlockMeter(pct: Float, color: Color, cells: Int = 18, size: Int = 10) {
    val filled = (pct.coerceIn(0f, 1f) * cells).toInt()
    Row {
        TermText("[", Cyber.textFaint, size)
        TermText("█".repeat(filled), color, size, spacing = 0.0)
        TermText("·".repeat(cells - filled), Cyber.textFaint, size, spacing = 0.0)
        TermText("]", Cyber.textFaint, size)
    }
}

/** Pixel checkerboard glyph (static). */
@Composable
private fun CheckerGlyph(modifier: Modifier = Modifier, color: Color = Cyber.textPrimary, cells: Int = 6) {
    Canvas(modifier.size(width = 30.dp, height = 14.dp)) {
        val cw = size.width / cells
        val ch = size.height / 3f
        for (r in 0 until 3) for (c in 0 until cells) {
            if ((r + c) % 2 == 0) drawRect(color, Offset(c * cw, r * ch), Size(cw, ch))
        }
    }
}

/** Static barcode block (replaces the old dancing bars). */
@Composable
private fun BarcodeGlyph(modifier: Modifier = Modifier, color: Color = Cyber.textPrimary, seed: Int = 7) {
    Canvas(modifier.size(width = 74.dp, height = 20.dp)) {
        var x = 0f
        var s = seed
        while (x < size.width) {
            s = (s * 1103515245 + 12345) and 0x7fffffff
            val w = 1f + (s % 3)
            val gap = 1f + ((s / 7) % 3)
            drawRect(color.copy(alpha = if ((s / 13) % 4 == 0) 0.45f else 1f), Offset(x, 0f), Size(w, size.height))
            x += w + gap
        }
    }
}

/** Concentric outline motif (the circles / squares on the reference board). */
@Composable
private fun ConcentricMotif(
    modifier: Modifier = Modifier,
    rings: Int = 7,
    square: Boolean = false,
    color: Color = Cyber.border
) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val rMax = minOf(size.width, size.height) / 2f - 2f
        for (i in 1..rings) {
            val r = rMax * i / rings
            if (square) {
                drawRect(color, Offset(cx - r, cy - r), Size(r * 2, r * 2), style = Stroke(1f))
            } else {
                drawCircle(color, radius = r, center = Offset(cx, cy), style = Stroke(1f))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TITLE BAR
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BiosTitleBar(version: String, statusText: String, statusColor: Color) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckerGlyph(color = Cyber.textPrimary)
        Spacer(Modifier.width(10.dp))
        TermText("ACE TERMINAL (TM)", Cyber.textPrimary, 12, weight = FontWeight.Bold, spacing = 1.8)
        Spacer(Modifier.weight(1f))
        TermText("ACE (C) 2026", Cyber.textDim, 11, spacing = 1.4)
        Spacer(Modifier.weight(1f))
        CjkText("ポータル", Cyber.textDim, 11)
        Spacer(Modifier.weight(1f))
        TermText("VER $version", Cyber.textDim, 11, spacing = 1.4)
        Spacer(Modifier.weight(1f))
        TermText("DRV......", Cyber.textMuted, 11, spacing = 1.4)
        TermText(statusText, statusColor, 11, weight = FontWeight.Bold, spacing = 1.4)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  WINDOW CHROME  — replaces the white Windows title bar.
//  Use with Window(undecorated = true). The host window owns drag behavior.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AppTitleBar(
    title: String = "ACE",
    subtitle: String = "MOUSE CONTROL TERMINAL",
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().height(34.dp)
            .background(Cyber.deepVoid)
            .drawBehind {
                drawLine(Cyber.borderSoft, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(12.dp))
        CheckerGlyph(color = Cyber.textPrimary, cells = 6)
        Spacer(Modifier.width(10.dp))
        TermText(title, Cyber.textPrimary, 11, weight = FontWeight.Bold, spacing = 2.4)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(1.dp).height(14.dp).background(Cyber.borderSoft))
        Spacer(Modifier.width(10.dp))
        TermText(subtitle, Cyber.textMuted, 10, spacing = 1.4)

        Spacer(Modifier.weight(1f))

        WindowButton("—", onMinimize)
        WindowButton("▢", onMaximize)
        WindowButton("✕", onClose, danger = true)
    }
}

@Composable
private fun WindowButton(glyph: String, onClick: () -> Unit, danger: Boolean = false) {
    val ints = remember { MutableInteractionSource() }
    val hovered by ints.collectIsHoveredAsState()
    val bg = when {
        hovered && danger -> Cyber.red
        hovered           -> Cyber.panelHi
        else              -> Color.Transparent
    }
    val fg = when {
        hovered && danger -> Cyber.abyss
        hovered           -> Cyber.textPrimary
        else              -> Cyber.textDim
    }
    Box(
        Modifier.width(46.dp).fillMaxHeight()
            .background(bg)
            .clickable(interactionSource = ints, indication = null, onClick = onClick)
            .hoverable(ints),
        contentAlignment = Alignment.Center
    ) {
        TermText(glyph, fg, 12, weight = FontWeight.Bold, spacing = 0.0)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TAB STRIP
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TabStrip(active: NavTab, onSelect: (NavTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(46.dp)
            .clip(shapePanel)
            .background(Cyber.abyss)
            .biosFrame(Cyber.border),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavTab.entries.forEachIndexed { i, tab ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Cyber.borderSoft))
            TabButton(tab, active == tab, Modifier.weight(1f)) { onSelect(tab) }
        }
    }
}

@Composable
private fun TabButton(tab: NavTab, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ints = remember { MutableInteractionSource() }
    val hovered by ints.collectIsHoveredAsState()
    val fg by animateColorAsState(
        when { active -> Cyber.abyss; hovered -> Cyber.magenta; else -> Cyber.textDim }
    )
    Box(
        modifier.fillMaxHeight()
            .background(if (active) Cyber.cyan else Color.Transparent)
            .clickable(interactionSource = ints, indication = null, onClick = onClick)
            .hoverable(ints),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TermText(tab.icon, if (active) Cyber.abyss.copy(alpha = 0.55f) else Cyber.textFaint, 10, spacing = 1.0)
            Spacer(Modifier.width(8.dp))
            TermText(tab.label, fg, 15, weight = FontWeight.Bold, spacing = 2.6)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TOP BAR
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    selected: ProfileManager.Entry?,
    driverVersion: String,
    statusText: String,
    statusColor: Color,
    watchdog: String,
    onRefresh: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(60.dp)
            .clip(shapePanel)
            .background(Cyber.panel)
            .biosFrame(Cyber.border)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TermText("C:\\>", Cyber.textMuted, 13, weight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                TermText(
                    selected?.displayName?.uppercase() ?: "NO PROFILE SELECTED",
                    Cyber.textPrimary, 14, weight = FontWeight.Bold, spacing = 2.2
                )
                Spacer(Modifier.width(4.dp))
                Caret()
            }
            Spacer(Modifier.height(2.dp))
            TermText(
                selected?.settings?.profiles?.firstOrNull()?.let { "ACTIVE PROFILE .. ${it.name.uppercase()}" }
                    ?: "DEAD END ZOMBIES .. MOUSE CONTROL",
                Cyber.textDim, 10, spacing = 1.3
            )
        }
        Spacer(Modifier.weight(1f))

        LiveBars()

        Spacer(Modifier.width(16.dp))
        MetricPill("WATCHDOG", watchdog.uppercase(), Cyber.violet)
        Spacer(Modifier.width(8.dp))
        MetricPill("DRIVER", "$statusText v$driverVersion", statusColor)
        Spacer(Modifier.width(10.dp))

        CyberBtn("SYNC", onRefresh, Cyber.cyan, Modifier.width(94.dp))
    }
}

@Composable
private fun Caret() {
    TermText("█", Cyber.cyan.copy(alpha = 0.72f), 13)
}

/** Static header meter: intentionally calm so it does not distract during play. */
@Composable
private fun LiveBars() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BarcodeGlyph(color = Cyber.textPrimary)
        Spacer(Modifier.width(8.dp))
        TermText("01.25.57", Cyber.textMuted, 10, spacing = 1.2)
    }
}

@Composable
private fun MetricPill(label: String, value: String, color: Color) {
    Row(
        Modifier.clip(shapeButton)
            .background(Cyber.abyss)
            .biosFrame(color.copy(alpha = 0.45f), color, ticks = false, radius = 8.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TermText(label, Cyber.textMuted, 9, weight = FontWeight.Bold, spacing = 1.2)
        TermText("..", Cyber.textFaint, 9)
        Spacer(Modifier.width(4.dp))
        TermText(value, color, 10, weight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  FOOTER  (chip strip like the reference board)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BiosFooter(
    profileCount: Int,
    miceCount: Int,
    watchdog: String,
    statusText: String,
    statusColor: Color,
    tab: NavTab
) {
    Row(
        Modifier.fillMaxWidth().height(34.dp)
            .clip(shapeButton)
            .background(Cyber.abyss)
            .biosFrame(Cyber.borderSoft, Cyber.border, radius = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FooterCell { CheckerGlyph(color = Cyber.textPrimary, cells = 8) }
        FooterDiv()
        FooterCell { CjkText("ポータル", Cyber.textDim, 11) }
        FooterDiv()
        FooterCell { TermText("PROFILES...${"%02d".format(profileCount)}", Cyber.textDim, 10, spacing = 1.1) }
        FooterDiv()
        FooterCell { TermText("MICE...${"%02d".format(miceCount)}", Cyber.textDim, 10, spacing = 1.1) }
        FooterDiv()
        FooterCell { TermText("WDOG...${watchdog.uppercase()}", Cyber.textDim, 10, spacing = 1.1) }
        FooterDiv()
        FooterCell { TermText("✛", Cyber.textDim, 12) }
        Spacer(Modifier.weight(1f))
        FooterDiv()
        FooterCell { TermText("${tab.icon} // ${tab.label}", Cyber.textPrimary, 10, weight = FontWeight.Bold, spacing = 1.4) }
        FooterDiv()
        FooterCell { TermText(statusText, statusColor, 10, weight = FontWeight.Bold, spacing = 1.2) }
    }
}

@Composable
private fun FooterCell(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun FooterDiv() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Cyber.borderSoft))
}

// ─────────────────────────────────────────────────────────────────────────────
//  DASHBOARD VIEW
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DashboardView(
    profiles: List<ProfileManager.Entry>,
    selected: ProfileManager.Entry?,
    deviceReport: DeviceInspector.Report?,
    watchdog: String,
    onSelect: (ProfileManager.Entry) -> Unit,
    onApply: () -> Unit,
    onQuery: () -> Unit,
    onReset: () -> Unit,
    onSession: () -> Unit,
    onImport: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    canRestore: Boolean
) {
    val profile = selected?.settings?.profiles?.firstOrNull()
    val cfg     = selected?.settings?.devices?.firstOrNull()?.config
        ?: selected?.settings?.defaultDeviceConfig
    val dpi     = cfg?.dpi ?: 0
    val poll    = cfg?.pollingRate ?: 0
    val gain60: Double  = profile?.let { CurveEngine.gainAt(it.accelX, 60.0) } ?: 1.0
    val peak: Double    = profile?.let { CurveEngine.stats(it.accelX, 300.0).peakGain } ?: 1.0

    Row(Modifier.fillMaxSize()) {

        // LEFT: PROFILES
        Column(Modifier.width(285.dp).fillMaxHeight()) {
            SectionPanel("PROFILE BANK", "001", Cyber.textPrimary, Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (profiles.isEmpty()) {
                        EchoLine("NO PROFILES FOUND.", Cyber.textMuted)
                        EchoLine("DROP SETTINGS.JSON INTO CONFIG DIR.", Cyber.textMuted)
                    }
                    profiles.forEachIndexed { i, entry ->
                        ProfileCard(entry, selected == entry, i) { onSelect(entry) }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            SectionPanel("QUICK ACTIONS", "002", Cyber.textPrimary, Modifier.fillMaxWidth()) {
                CyberBtn("APPLY PROFILE", onApply, Cyber.cyan, Modifier.fillMaxWidth(), enabled = selected != null, big = true)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    CyberBtn("QUERY", onQuery, Cyber.blue, Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    CyberBtn("RESET", onReset, Cyber.red, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    CyberBtn("IMPORT", onImport, Cyber.blue, Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    CyberBtn("CLONE", onDuplicate, Cyber.violet, Modifier.weight(1f), enabled = selected != null)
                    Spacer(Modifier.width(6.dp))
                    CyberBtn("EXPORT", onExport, Cyber.green, Modifier.weight(1f), enabled = selected != null)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    CyberBtn("RESTORE", onRestore, Cyber.amber, Modifier.weight(1f), enabled = canRestore)
                    Spacer(Modifier.width(6.dp))
                    CyberBtn("RENAME", onRename, Cyber.violet, Modifier.weight(1f), enabled = selected != null)
                    Spacer(Modifier.width(6.dp))
                    CyberBtn("DELETE", onDelete, Cyber.red, Modifier.weight(1f), enabled = selected != null)
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TermText("SESSION PREP", Cyber.textMuted, 9, weight = FontWeight.Bold, spacing = 1.6)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(Cyber.borderSoft))
                }
                Spacer(Modifier.height(6.dp))
                EchoLine("PROFILE · BITSUM HIGHEST PERFORMANCE", Cyber.textMuted, 9)
                Spacer(Modifier.height(6.dp))
                CyberBtn("PREPARE SESSION", onSession, Cyber.cyan, Modifier.fillMaxWidth(), enabled = selected != null, big = true)
                Spacer(Modifier.height(6.dp))
                DotRow("WATCHDOG", watchdog.uppercase(), Cyber.textPrimary, Cyber.textMuted, 9)
            }
        }

        Spacer(Modifier.width(8.dp))

        // CENTER: HERO
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(shapePanel)
                    .background(Cyber.abyss)
                    .biosFrame(Cyber.border)
            ) {
                // stacked ghost wordmark (BREAKING NEWS treatment)
                Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 6.dp)) {
                    TermText("ACE", Cyber.textFaint.copy(alpha = 0.45f), 34, weight = FontWeight.Black, spacing = 6.0)
                    TermText("ACE", Cyber.textFaint.copy(alpha = 0.30f), 34, weight = FontWeight.Black, spacing = 6.0)
                    TermText("ACE", Cyber.textFaint.copy(alpha = 0.16f), 34, weight = FontWeight.Black, spacing = 6.0)
                }

                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    PanelHeaderRow("ACTIVE DEVICE", "003", Cyber.textPrimary)
                    Spacer(Modifier.height(6.dp))

                    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            MouseHero(peak = peak)
                        }
                        Column(Modifier.width(232.dp)) {
                            BigReadout("DPI",       dpi.toString(),         Cyber.cyan)
                            Spacer(Modifier.height(8.dp))
                            BigReadout("POLL RATE", "$poll HZ",             Cyber.cyan)
                            Spacer(Modifier.height(8.dp))
                            BigReadout("GAIN @60",  "%.2fX".format(gain60), Cyber.cyan)
                            Spacer(Modifier.height(8.dp))
                            BigReadout("PEAK",      "%.2fX".format(peak),   Cyber.green)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                Modifier.fillMaxWidth().height(150.dp)
                    .clip(shapePanel)
                    .background(Cyber.panel)
                    .biosFrame(Cyber.border)
                    .padding(12.dp)
            ) {
                Column(Modifier.fillMaxSize()) {
                    PanelHeaderRow("CURVE PREVIEW", "004", Cyber.textPrimary)
                    Spacer(Modifier.height(6.dp))
                    if (profile != null) MiniCurve(profile)
                    else EchoLine("NO CURVE DATA", Cyber.textMuted)
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // RIGHT: TELEMETRY & TARGETS
        Column(Modifier.width(285.dp).fillMaxHeight()) {
            SectionPanel("TELEMETRY", "005", Cyber.textPrimary, Modifier.fillMaxWidth()) {
                TelemetryList(profile, cfg?.dpi ?: 0, cfg?.pollingRate ?: 0)
            }
            Spacer(Modifier.height(8.dp))
            SectionPanel("CONNECTED", "006", Cyber.textPrimary, Modifier.fillMaxWidth().weight(1f)) {
                val mice = deviceReport?.connectedMice ?: emptyList()
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (mice.isEmpty()) {
                        EchoLine("NO MICE DETECTED.", Cyber.textMuted)
                    }
                    mice.forEachIndexed { i, m ->
                        Box(
                            Modifier.fillMaxWidth()
                                .clip(shapeCard)
                                .background(Cyber.abyss)
                                .biosFrame(Cyber.borderSoft, Cyber.border, radius = 9.dp)
                                .padding(9.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TermText("[%02d]".format(i + 1), Cyber.textMuted, 9)
                                    Spacer(Modifier.width(6.dp))
                                    Box(Modifier.size(6.dp).background(Cyber.green, CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    TermText(
                                        DeviceInspector.targetName(m).uppercase(),
                                        Cyber.textPrimary, 10, weight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                TermText(
                                    DeviceInspector.rawAccelDeviceId(m.instanceId),
                                    Cyber.textMuted, 9, spacing = 0.0
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeaderRow(title: String, index: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TermText(index, Cyber.textMuted, 11, weight = FontWeight.Bold, spacing = 1.4)
        Spacer(Modifier.width(6.dp))
        TermText("//", Cyber.textFaint, 11)
        Spacer(Modifier.width(6.dp))
        TermText(title, accent, 11, weight = FontWeight.Bold, spacing = 2.2)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.weight(1f).height(8.dp).drawBehind {
                drawLine(Cyber.borderSoft, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1f)
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  MOUSE HERO  —  dithered block matrix, now essentially still
// ─────────────────────────────────────────────────────────────────────────────

/** Silhouette density 0..1 for a normalised cell (u,v). */
private fun mouseCellDensity(u: Float, v: Float): Float {
    val dx = (u - 0.5f) / 0.36f
    val dy = (v - 0.55f) / 0.44f
    val e = abs(dx).toDouble().pow(2.6) + abs(dy).toDouble().pow(2.2)
    if (e > 1.0) return 0f

    var d = 0.34f
    val edge = (1.0 - e).toFloat()
    if (edge < 0.13f) d = 1f                                   // outline ring

    if (u > 0.462f && u < 0.538f && v > 0.13f && v < 0.26f) d = 1f          // wheel
    if (abs(u - 0.5f) < 0.014f && v < 0.44f) d = 0.92f                      // button split
    if (u > 0.10f && u < 0.19f && v > 0.40f && v < 0.49f) d = 0.78f         // side buttons

    val sx = (u - 0.5f) / 0.105f
    val sy = (v - 0.73f) / 0.09f
    val sd = sqrt(sx * sx + sy * sy)
    if (sd < 1f) d = max(d, 0.95f - 0.35f * sd)                             // sensor
    return d
}

@Composable
private fun MouseHero(peak: Double) {
    // Intentionally static: continuous animation caused unnecessary CPU usage
    // in the packaged application while mouse processing was active.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(250.dp).height(272.dp)
                .clip(shapePanel)
                .background(Cyber.abyss)
                .biosFrame(Cyber.borderSoft, Cyber.border),
            contentAlignment = Alignment.Center
        ) {
            ConcentricMotif(
                Modifier.size(246.dp),
                rings = 6,
                color = Cyber.borderSoft.copy(alpha = 0.8f)
            )

            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val cols = 30
                val rows = 34
                val cw = size.width / cols
                val chh = size.height / rows
                val cell = minOf(cw, chh) * 0.80f

                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val u = (c + 0.5f) / cols
                        val v = (r + 0.5f) / rows
                        val d = mouseCellDensity(u, v)
                        if (d <= 0.02f) continue

                        val dither = (((r * 7 + c * 13) % 5) / 5f)
                        val a = d * (0.55f + 0.45f * (1f - dither))

                        val col = if (d > 0.85f) Cyber.cyan else Cyber.cyanDim
                        drawRect(
                            col.copy(alpha = a),
                            topLeft = Offset(c * cw + (cw - cell) / 2f, r * chh + (chh - cell) / 2f),
                            size = Size(cell, cell)
                        )
                    }
                }
            }

            TermText(
                "MODE: COLS=30 LINES=34",
                Cyber.textFaint, 8,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp)
            )
            CjkText("ポータル", Cyber.textFaint, 10, Modifier.align(Alignment.BottomEnd).padding(10.dp))
        }

        Spacer(Modifier.height(8.dp))
        TermText("PEAK GAIN", Cyber.textDim, 9, weight = FontWeight.Bold, spacing = 2.2)
        Spacer(Modifier.height(3.dp))
        BlockMeter(((peak - 1.0) / 1.0).toFloat(), Cyber.textPrimary, 20, 11)
        Spacer(Modifier.height(3.dp))
        TermText("%.2fX".format(peak), Cyber.textPrimary, 20, weight = FontWeight.Black, spacing = 1.5)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  BIG READOUT
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BigReadout(label: String, value: String, color: Color) {
    Column(
        Modifier.fillMaxWidth()
            .clip(shapeCard)
            .background(Cyber.abyss)
            .biosFrame(Cyber.borderSoft, color, radius = 9.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TermText(label, Cyber.textDim, 9, weight = FontWeight.Bold, spacing = 1.8)
            Box(
                Modifier.weight(1f).height(10.dp).padding(horizontal = 5.dp).drawBehind {
                    drawLine(
                        Cyber.textFaint, Offset(0f, size.height * 0.7f), Offset(size.width, size.height * 0.7f), 1.4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.4f, 3.4f))
                    )
                }
            )
        }
        Spacer(Modifier.height(1.dp))
        TermText(value, color, 24, weight = FontWeight.Black, spacing = 1.0)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TELEMETRY LIST
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TelemetryList(profile: Profile?, dpi: Int, poll: Int) {
    Column {
        val g20: Double  = profile?.let { CurveEngine.gainAt(it.accelX, 20.0) }  ?: 1.0
        val g60: Double  = profile?.let { CurveEngine.gainAt(it.accelX, 60.0) }  ?: 1.0
        val g120: Double = profile?.let { CurveEngine.gainAt(it.accelX, 120.0) } ?: 1.0
        val stats = profile?.let { CurveEngine.stats(it.accelX, 300.0) }
        val mode = profile?.accelX?.mode?.uppercase() ?: "—"

        TelemetryRow("MODE",       mode,                              Cyber.textPrimary)
        TelemetryRow("GAIN @20",   "%.2fX".format(g20),               Cyber.textPrimary)
        TelemetryRow("GAIN @60",   "%.2fX".format(g60),               Cyber.textPrimary)
        TelemetryRow("GAIN @120",  "%.2fX".format(g120),              Cyber.textPrimary)
        TelemetryRow("ACCEL START", stats?.accelStartSpeed?.let { "%.1f IN/S".format(it) } ?: "—", Cyber.amber)
        TelemetryRow("PEAK SPEED",  stats?.peakSpeed?.let { "%.1f IN/S".format(it) } ?: "—", Cyber.green)
        TelemetryRow("DPI",        dpi.toString(),                    Cyber.textPrimary)
        TelemetryRow("POLL",       "$poll HZ",                        Cyber.textPrimary)
    }
}

@Composable
private fun TelemetryRow(label: String, value: String, color: Color) {
    DotRow(label, value, color)
}

// ─────────────────────────────────────────────────────────────────────────────
//  MINI CURVE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MiniCurve(profile: Profile) {
    val accel = profile.accelX
    Canvas(Modifier.fillMaxSize()) {
        val pad = 10f
        val w = size.width
        val h = size.height
        val lastX: Double = if (accel.isLut && accel.data.size >= 2) accel.data[accel.data.size - 2] else 300.0
        val maxIn: Double = if (lastX < 1.0) 1.0 else lastX

        for (i in 0..4) {
            val y = pad + (h - pad * 2) * i / 4
            drawLine(Cyber.borderSoft, Offset(pad, y), Offset(w - pad, y), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f, 3.5f)))
        }
        for (i in 0..12) {
            val x = pad + (w - pad * 2) * i / 12
            drawLine(Cyber.borderSoft.copy(alpha = 0.6f), Offset(x, pad), Offset(x, h - pad), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f, 3.5f)))
        }

        var maxG = 1.0
        val pts = ArrayList<Pair<Double, Double>>()
        for (i in 0..200) {
            val x: Double = maxIn * i.toDouble() / 200.0
            val g: Double = CurveEngine.gainAt(accel, x)
            if (g > maxG) maxG = g
            pts.add(Pair(x, g))
        }
        val yMax = maxG * 1.1
        fun px(x: Double) = pad + (x / maxIn * (w - pad * 2)).toFloat()
        fun py(g: Double) = h - pad - (g / yMax * (h - pad * 2)).toFloat()

        val path = Path()
        pts.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(px(p.first), py(p.second))
            else path.lineTo(px(p.first), py(p.second))
        }

        val fill = Path().apply {
            addPath(path)
            lineTo(px(pts.last().first), h - pad)
            lineTo(px(pts.first().first), h - pad)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(Cyber.cyan.copy(alpha = 0.10f), Color.Transparent)))
        drawPath(path, Cyber.textPrimary, style = Stroke(width = 1.6f, cap = StrokeCap.Round))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  PROFILE CARD
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileCard(entry: ProfileManager.Entry, active: Boolean, index: Int, onClick: () -> Unit) {
    val ints = remember { MutableInteractionSource() }
    val hovered by ints.collectIsHoveredAsState()

    val borderColor by animateColorAsState(
        when { active -> Cyber.textPrimary; hovered -> Cyber.cyanDim; else -> Cyber.borderSoft }
    )

    Box(
        Modifier.fillMaxWidth()
            .clip(shapeCard)
            .background(if (active) Cyber.panelHi else Cyber.abyss)
            .biosFrame(borderColor, Cyber.textPrimary, ticks = active, radius = 9.dp)
            .clickable(interactionSource = ints, indication = null, onClick = onClick)
            .hoverable(ints)
            .padding(horizontal = 11.dp, vertical = 10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TermText(if (active) "▸" else " ", Cyber.textPrimary, 11, weight = FontWeight.Bold)
                Spacer(Modifier.width(5.dp))
                TermText("%03d //".format(index + 1), Cyber.textMuted, 10)
                Spacer(Modifier.width(6.dp))
                TermText(
                    entry.displayName.uppercase(),
                    if (active) Cyber.textPrimary else Cyber.textDim,
                    11, weight = FontWeight.Bold, spacing = 1.1,
                    modifier = Modifier.weight(1f)
                )
                if (active) TermText("SEL", Cyber.abyss, 9, weight = FontWeight.Bold,
                    modifier = Modifier.background(Cyber.textPrimary, shapeButton).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            Spacer(Modifier.height(3.dp))
            TermText(
                "    " + entry.profileNames.joinToString(" · ").uppercase(),
                Cyber.textMuted, 9
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  SECTION PANEL
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionPanel(
    title: String,
    icon: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(shapePanel)
            .background(Cyber.panel)
            .biosFrame(Cyber.border, accent)
            .padding(13.dp)
    ) {
        PanelHeaderRow(title, icon, accent)
        Spacer(Modifier.height(4.dp))
        ChevronRule()
        Spacer(Modifier.height(8.dp))
        content()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  BUTTON  — fills bone on hover, like the CLICK TO MINT chip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CyberBtn(
    label: String,
    onClick: () -> Unit,
    accent: Color = Cyber.cyan,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    big: Boolean = false
) {
    val ints = remember { MutableInteractionSource() }
    val hovered by ints.collectIsHoveredAsState()

    val bg = if (enabled && hovered) accent else Color.Transparent
    val fg = when {
        !enabled -> Cyber.textFaint
        hovered  -> Cyber.abyss
        else     -> accent
    }

    Box(
        modifier.height(if (big) 44.dp else 34.dp)
            .clip(shapeButton)
            .background(bg)
            .biosFrame(
                if (!enabled) Cyber.borderSoft else accent.copy(alpha = if (hovered) 1f else 0.55f),
                accent,
                ticks = false,
                radius = 8.dp
            )
            .clickable(enabled = enabled, interactionSource = ints, indication = null, onClick = onClick)
            .hoverable(ints)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        TermText(label, fg, if (big) 12 else 10, weight = FontWeight.Bold, spacing = 2.0)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  PROFILE EDITOR
// ─────────────────────────────────────────────────────────────────────────────

private data class EditableLutPoint(val input: String, val output: String)
private enum class EditorAxis { HORIZONTAL, VERTICAL }

private fun Double.editorText(): String = if (this % 1.0 == 0.0) "%.0f".format(this) else "%.4f".format(this).trimEnd('0')

@Composable
private fun ProfileEditorView(
    selected: ProfileManager.Entry?,
    onSave: (Settings) -> Unit
) {
    val settings = selected?.settings
    if (selected == null || settings == null || settings.profiles.isEmpty()) {
        SectionPanel("PROFILE EDITOR", "003", Cyber.textPrimary, Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EchoLine("SELECT A PROFILE BANK TO BEGIN EDITING.", Cyber.textMuted)
            }
        }
        return
    }

    val selectedInternalIndex = remember(selected.file) { mutableStateOf(0) }
    val profileIndex = selectedInternalIndex.value.coerceIn(settings.profiles.indices)
    val sourceProfile = settings.profiles[profileIndex]
    val editorAxisState = remember(selected.file, profileIndex) { mutableStateOf(EditorAxis.HORIZONTAL) }
    val editorAxis = editorAxisState.value
    val sourceAccel = if (editorAxis == EditorAxis.HORIZONTAL) sourceProfile.accelX else sourceProfile.accelY
    var revision by remember(selected.file, profileIndex, editorAxis) { mutableStateOf(0) }

    var outputDpi by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(sourceProfile.outputDpi.editorText()) }
    var deviceDpi by remember(selected.settings, profileIndex, editorAxis, revision) {
        mutableStateOf((settings.devices.firstOrNull()?.config?.dpi ?: settings.defaultDeviceConfig.dpi).toString())
    }
    var pollingRate by remember(selected.settings, profileIndex, editorAxis, revision) {
        mutableStateOf((settings.devices.firstOrNull()?.config?.pollingRate ?: settings.defaultDeviceConfig.pollingRate).toString())
    }
    var verticalRatio by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(sourceProfile.yxRatio.editorText()) }
    var leftRatio by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(sourceProfile.lrRatio.editorText()) }
    var upRatio by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(sourceProfile.udRatio.editorText()) }
    var inputSmooth by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(sourceProfile.speed.inputSmoothHalflife.editorText()) }
    var scaleSmooth by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(sourceProfile.speed.scaleSmoothHalflife.editorText()) }
    var outputSmooth by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(sourceProfile.speed.outputSmoothHalflife.editorText()) }
    var rotation by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(sourceProfile.rotation.editorText()) }
    var snap by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(sourceProfile.snap.editorText()) }
    var speedCap by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(sourceProfile.speedCap.editorText()) }
    var points by remember(selected.settings, profileIndex, editorAxis, revision) {
        mutableStateOf(
            ProfileEditorEngine.fromAccel(sourceAccel).map {
                EditableLutPoint(it.input.editorText(), it.output.editorText())
            }
        )
    }
    val inferred = remember(selected.settings, profileIndex, editorAxis, revision) {
        ProfileEditorEngine.inferGuidedValues(ProfileEditorEngine.fromAccel(sourceAccel))
    }
    var startSpeed by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(inferred.first.editorText()) }
    var endSpeed by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(inferred.second.editorText()) }
    var peakGain by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(inferred.third.editorText()) }
    var shape by remember(selected.file, profileIndex, editorAxis) { mutableStateOf(ProfileEditorEngine.CurveShape.SMOOTH) }
    var dirty by remember(selected.settings, profileIndex, editorAxis, revision) { mutableStateOf(false) }

    val parsedPoints = points.mapNotNull { row ->
        val x = row.input.toDoubleOrNull()
        val y = row.output.toDoubleOrNull()
        if (x == null || y == null) null else ProfileEditorEngine.LutPoint(x, y)
    }
    val numericError = points.isNotEmpty() && parsedPoints.size != points.size
    val curveValidation = if (numericError) {
        ProfileEditorEngine.Validation(listOf("Every LUT cell must contain a valid number"), emptyList())
    } else {
        ProfileEditorEngine.validate(parsedPoints)
    }
    val scalarErrors = buildList {
        if ((outputDpi.toDoubleOrNull() ?: 0.0) <= 0.0) add("Output DPI must be greater than 0")
        if ((deviceDpi.toIntOrNull() ?: -1) < 0) add("Device DPI must be 0 or greater")
        if ((pollingRate.toIntOrNull() ?: -1) < 0) add("Polling rate must be 0 or greater")
        if ((verticalRatio.toDoubleOrNull() ?: 0.0) <= 0.0) add("Vertical ratio must be greater than 0")
        if ((leftRatio.toDoubleOrNull() ?: 0.0) <= 0.0) add("Left/right ratio must be greater than 0")
        if ((upRatio.toDoubleOrNull() ?: 0.0) <= 0.0) add("Up/down ratio must be greater than 0")
        if ((inputSmooth.toDoubleOrNull() ?: -1.0) < 0.0) add("Input smoothing can not be negative")
        if ((scaleSmooth.toDoubleOrNull() ?: -1.0) < 0.0) add("Scale smoothing can not be negative")
        if ((outputSmooth.toDoubleOrNull() ?: -1.0) < 0.0) add("Output smoothing can not be negative")
        val snapValue = snap.toDoubleOrNull()
        if (snapValue == null || snapValue !in 0.0..45.0) add("Angle snapping must be between 0 and 45 degrees")
        if ((speedCap.toDoubleOrNull() ?: -1.0) < 0.0) add("Speed cap can not be negative")
        if (rotation.toDoubleOrNull() == null) add("Rotation must be a valid number")
    }
    val allErrors = scalarErrors + curveValidation.errors
    val canSave = allErrors.isEmpty() && dirty

    val previewProfile = if (curveValidation.isValid && parsedPoints.size >= 2) {
        val editedAccel = sourceAccel.copy(
            mode = "lut",
            gain = true,
            data = ProfileEditorEngine.flatten(parsedPoints)
        )
        val editedProfile = sourceProfile.copy(
            outputDpi = outputDpi.toDoubleOrNull() ?: sourceProfile.outputDpi,
            yxRatio = verticalRatio.toDoubleOrNull() ?: sourceProfile.yxRatio,
            lrRatio = leftRatio.toDoubleOrNull() ?: sourceProfile.lrRatio,
            udRatio = upRatio.toDoubleOrNull() ?: sourceProfile.udRatio,
            speed = sourceProfile.speed.copy(
                inputSmoothHalflife = inputSmooth.toDoubleOrNull() ?: sourceProfile.speed.inputSmoothHalflife,
                scaleSmoothHalflife = scaleSmooth.toDoubleOrNull() ?: sourceProfile.speed.scaleSmoothHalflife,
                outputSmoothHalflife = outputSmooth.toDoubleOrNull() ?: sourceProfile.speed.outputSmoothHalflife
            ),
            rotation = rotation.toDoubleOrNull() ?: sourceProfile.rotation,
            snap = snap.toDoubleOrNull() ?: sourceProfile.snap,
            speedCap = speedCap.toDoubleOrNull() ?: sourceProfile.speedCap
        )
        if (editorAxis == EditorAxis.HORIZONTAL) editedProfile.copy(accelX = editedAccel)
        else editedProfile.copy(accelY = editedAccel)
    } else sourceProfile

    fun generateCurve() {
        val start = startSpeed.toDoubleOrNull()
        val end = endSpeed.toDoubleOrNull()
        val peak = peakGain.toDoubleOrNull()
        val generated = runCatching {
            ProfileEditorEngine.generate(
                start ?: error("Start speed is invalid"),
                end ?: error("End speed is invalid"),
                peak ?: error("Peak gain is invalid"),
                shape
            )
        }.getOrNull() ?: return
        points = generated.map { EditableLutPoint(it.input.editorText(), it.output.editorText()) }
        dirty = true
    }

    fun repairCurve() {
        if (parsedPoints.size != points.size || parsedPoints.size < 2) return
        points = ProfileEditorEngine.repairGainDips(parsedPoints).map {
            EditableLutPoint(it.input.editorText(), it.output.editorText())
        }
        dirty = true
    }

    fun saveDraft() {
        if (!canSave) return
        val profiles = settings.profiles.toMutableList().apply { set(profileIndex, previewProfile) }
        val dpi = deviceDpi.toInt()
        val poll = pollingRate.toInt()
        val updated = settings.copy(
            defaultDeviceConfig = settings.defaultDeviceConfig.copy(dpi = dpi, pollingRate = poll),
            profiles = profiles,
            devices = settings.devices.map { device ->
                device.copy(config = device.config.copy(dpi = dpi, pollingRate = poll))
            }
        )
        onSave(updated)
        // The parent replaces `selected.settings` only after the atomic save succeeds;
        // that successful replacement resets this draft's dirty state.
    }

    Row(Modifier.fillMaxSize()) {
        SectionPanel("GUIDED EDITOR", "003", Cyber.textPrimary, Modifier.width(290.dp).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (settings.profiles.size > 1) {
                    EchoLine("INTERNAL PROFILE", Cyber.textMuted, 9)
                    settings.profiles.forEachIndexed { index, profile ->
                        CyberBtn(
                            profile.name.uppercase(),
                            { if (!dirty) selectedInternalIndex.value = index },
                            if (index == profileIndex) Cyber.green else Cyber.violet,
                            Modifier.fillMaxWidth(),
                            enabled = !dirty || index == profileIndex
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }

                EchoLine("CURVE AXIS", Cyber.textMuted, 9)
                Row(Modifier.fillMaxWidth()) {
                    CyberBtn(
                        "HORIZONTAL",
                        { if (!dirty) editorAxisState.value = EditorAxis.HORIZONTAL },
                        if (editorAxis == EditorAxis.HORIZONTAL) Cyber.green else Cyber.violet,
                        Modifier.weight(1f),
                        enabled = !dirty || editorAxis == EditorAxis.HORIZONTAL
                    )
                    Spacer(Modifier.width(6.dp))
                    CyberBtn(
                        "VERTICAL",
                        { if (!dirty) editorAxisState.value = EditorAxis.VERTICAL },
                        if (editorAxis == EditorAxis.VERTICAL) Cyber.green else Cyber.violet,
                        Modifier.weight(1f),
                        enabled = !dirty || editorAxis == EditorAxis.VERTICAL
                    )
                }
                if (sourceProfile.speed.whole && editorAxis == EditorAxis.VERTICAL) {
                    Spacer(Modifier.height(5.dp))
                    EchoLine("⚠ VERTICAL LUT IS INACTIVE WHILE COMBINED MODE IS ON", Cyber.amber, 9)
                }
                Spacer(Modifier.height(8.dp))

                EditorField("OUTPUT DPI", outputDpi) { outputDpi = it; dirty = true }
                EditorField("DEVICE DPI · ALL TARGETS", deviceDpi) { deviceDpi = it; dirty = true }
                EditorField("POLLING HZ · 0 = AUTO", pollingRate) { pollingRate = it; dirty = true }
                EditorField("VERTICAL Y/X RATIO", verticalRatio) { verticalRatio = it; dirty = true }
                EditorField("LEFT/RIGHT RATIO", leftRatio) { leftRatio = it; dirty = true }
                EditorField("UP/DOWN RATIO", upRatio) { upRatio = it; dirty = true }
                EditorField("INPUT SMOOTH · MS", inputSmooth) { inputSmooth = it; dirty = true }
                EditorField("SCALE SMOOTH · MS", scaleSmooth) { scaleSmooth = it; dirty = true }
                EditorField("OUTPUT SMOOTH · MS", outputSmooth) { outputSmooth = it; dirty = true }
                EditorField("ROTATION DEGREES", rotation) { rotation = it; dirty = true }
                EditorField("ANGLE SNAP · 0–45", snap) { snap = it; dirty = true }
                EditorField("INPUT SPEED CAP · 0 = OFF", speedCap) { speedCap = it; dirty = true }

                Spacer(Modifier.height(8.dp))
                PanelHeaderRow("CURVE GENERATOR", "A", Cyber.textPrimary)
                Spacer(Modifier.height(6.dp))
                EditorField("ACCEL START · IN/S", startSpeed) { startSpeed = it }
                EditorField("FULL GAIN AT · IN/S", endSpeed) { endSpeed = it }
                EditorField("PEAK GAIN", peakGain) { peakGain = it }
                ProfileEditorEngine.CurveShape.entries.forEach { option ->
                    CyberBtn(
                        option.name.replace('_', ' '),
                        { shape = option },
                        if (shape == option) Cyber.green else Cyber.textDim,
                        Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                }
                CyberBtn("GENERATE SAFE LUT", ::generateCurve, Cyber.cyan, Modifier.fillMaxWidth(), big = true)
                Spacer(Modifier.height(6.dp))
                CyberBtn(
                    "REPAIR GAIN DIPS",
                    ::repairCurve,
                    Cyber.amber,
                    Modifier.fillMaxWidth(),
                    enabled = parsedPoints.size == points.size && parsedPoints.size >= 2 &&
                            curveValidation.warnings.any { "Gain decreases" in it }
                )

                Spacer(Modifier.height(10.dp))
                if (dirty) Badge("UNSAVED DRAFT", Cyber.amber) else Badge("SAVED", Cyber.green)
                Spacer(Modifier.height(6.dp))
                CyberBtn("SAVE PROFILE", ::saveDraft, Cyber.green, Modifier.fillMaxWidth(), enabled = canSave, big = true)
                Spacer(Modifier.height(6.dp))
                CyberBtn("REVERT DRAFT", { revision++ }, Cyber.amber, Modifier.fillMaxWidth(), enabled = dirty)
                Spacer(Modifier.height(6.dp))
                EchoLine("SAVE DOES NOT APPLY TO THE DRIVER.", Cyber.textMuted, 9)
            }
        }

        Spacer(Modifier.width(8.dp))
        SectionPanel("LIVE DRAFT PREVIEW", "004", Cyber.textPrimary, Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                if (allErrors.isNotEmpty()) {
                    allErrors.take(4).forEach { EchoLine("✗ $it", Cyber.red, 9) }
                    Spacer(Modifier.height(5.dp))
                } else {
                    EchoLine("✓ DRAFT VALID · ${parsedPoints.size} LUT POINTS", Cyber.green, 9)
                    curveValidation.warnings.take(2).forEach { EchoLine("⚠ $it", Cyber.amber, 9) }
                    Spacer(Modifier.height(5.dp))
                }
                Box(Modifier.fillMaxWidth().weight(1f)) { FullCurveChart(previewProfile, editorAxis) }
            }
        }

        Spacer(Modifier.width(8.dp))
        SectionPanel("ADVANCED LUT", "005", Cyber.textPrimary, Modifier.width(380.dp).fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TermText("#", Cyber.textMuted, 9, modifier = Modifier.width(28.dp))
                    TermText("INPUT", Cyber.textMuted, 9, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(5.dp))
                    TermText("OUTPUT", Cyber.textMuted, 9, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(5.dp))
                    TermText("GAIN", Cyber.textMuted, 9, modifier = Modifier.width(54.dp))
                    Spacer(Modifier.width(34.dp))
                }
                Spacer(Modifier.height(4.dp))
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    points.forEachIndexed { index, row ->
                        val gain = row.input.toDoubleOrNull()?.let { x ->
                            row.output.toDoubleOrNull()?.let { y -> if (x == 0.0) 1.0 else y / x }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TermText("%02d".format(index + 1), Cyber.textMuted, 9, modifier = Modifier.width(28.dp))
                            EditorCell(row.input, Modifier.weight(1f)) { value ->
                                points = points.toMutableList().also { it[index] = row.copy(input = value) }
                                dirty = true
                            }
                            Spacer(Modifier.width(5.dp))
                            EditorCell(row.output, Modifier.weight(1f)) { value ->
                                points = points.toMutableList().also { it[index] = row.copy(output = value) }
                                dirty = true
                            }
                            Spacer(Modifier.width(5.dp))
                            TermText(gain?.let { "%.3fx".format(it) } ?: "ERR", if (gain == null) Cyber.red else Cyber.textDim, 9,
                                modifier = Modifier.width(54.dp))
                            CyberBtn("×", {
                                points = points.toMutableList().also { it.removeAt(index) }
                                dirty = true
                            }, Cyber.red, Modifier.width(34.dp), enabled = points.size > 2)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    CyberBtn("ADD POINT", {
                        val last = parsedPoints.lastOrNull() ?: ProfileEditorEngine.LutPoint(0.0, 0.0)
                        val x = last.input + 10.0
                        points = points + EditableLutPoint(x.editorText(), (x * last.gain).editorText())
                        dirty = true
                    }, Cyber.cyan, Modifier.weight(1f), enabled = points.size < 257)
                    Spacer(Modifier.width(6.dp))
                    CyberBtn("SORT", {
                        points = points.sortedBy { it.input.toDoubleOrNull() ?: Double.MAX_VALUE }
                        dirty = true
                    }, Cyber.violet, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EditorField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 9.sp) },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(
            color = Cyber.textPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        ),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            textColor = Cyber.textPrimary,
            cursorColor = Cyber.cyan,
            focusedBorderColor = Cyber.cyan,
            unfocusedBorderColor = Cyber.border,
            focusedLabelColor = Cyber.cyan,
            unfocusedLabelColor = Cyber.textDim,
            backgroundColor = Cyber.abyss
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)
    )
    Spacer(Modifier.height(5.dp))
}

/** Compact themed numeric cell; BasicTextField avoids Material's 56dp minimum and clipping. */
@Composable
private fun EditorCell(value: String, modifier: Modifier, onValueChange: (String) -> Unit) {
    Box(
        modifier.height(42.dp)
            .clip(shapeButton)
            .background(Cyber.abyss)
            .border(1.dp, Cyber.border, shapeButton)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                color = Cyber.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            cursorBrush = SolidColor(Cyber.cyan),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  CURVE VIEW
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CurveView(selected: ProfileManager.Entry?) {
    val available = selected?.settings?.profiles.orEmpty()
    val profileName = remember(selected) { mutableStateOf(available.firstOrNull()?.name.orEmpty()) }
    val profile = available.firstOrNull { it.name == profileName.value } ?: available.firstOrNull()
    val curveAxis = remember(selected, profile?.name) { mutableStateOf(EditorAxis.HORIZONTAL) }

    SectionPanel("CURVE TELEMETRY · ${profile?.name?.uppercase() ?: "N/A"}", "002", Cyber.textPrimary, Modifier.fillMaxSize()) {
        if (profile == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TermText("SELECT A PROFILE TO VISUALIZE", Cyber.textMuted, 11, spacing = 2.2)
                    Caret()
                }
            }
            return@SectionPanel
        }

        Column(Modifier.fillMaxSize()) {
            if (available.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    available.forEach { candidate ->
                        CyberBtn(
                            candidate.name.uppercase(),
                            { profileName.value = candidate.name },
                            if (candidate.name == profile.name) Cyber.green else Cyber.violet
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth()) {
                CyberBtn(
                    "HORIZONTAL CURVE",
                    { curveAxis.value = EditorAxis.HORIZONTAL },
                    if (curveAxis.value == EditorAxis.HORIZONTAL) Cyber.green else Cyber.violet,
                    Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                CyberBtn(
                    "VERTICAL CURVE",
                    { curveAxis.value = EditorAxis.VERTICAL },
                    if (curveAxis.value == EditorAxis.VERTICAL) Cyber.green else Cyber.violet,
                    Modifier.weight(1f)
                )
            }
            if (profile.speed.whole && curveAxis.value == EditorAxis.VERTICAL) {
                Spacer(Modifier.height(5.dp))
                EchoLine("⚠ VERTICAL LUT IS PRESENT BUT INACTIVE IN COMBINED MODE", Cyber.amber, 9)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) { FullCurveChart(profile, curveAxis.value) }
        }
    }
}

@Composable
private fun FullCurveChart(profile: Profile, axis: EditorAxis = EditorAxis.HORIZONTAL) {
    val accel = if (axis == EditorAxis.HORIZONTAL) profile.accelX else profile.accelY
    val issues = remember(accel) { CurveEngine.monotonicityIssues(accel) }
    val maxIn: Double = remember(accel) {
        val lastX: Double = if (accel.isLut && accel.data.size >= 2) accel.data[accel.data.size - 2] else 300.0
        if (lastX < 1.0) 1.0 else lastX
    }
    val stats = remember(accel, maxIn) { CurveEngine.stats(accel, maxIn) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(if (issues.isEmpty()) "MONOTONIC" else "NON-MONOTONIC (${issues.size})",
                if (issues.isEmpty()) Cyber.green else Cyber.amber)
            Spacer(Modifier.width(6.dp))
            Badge("PEAK %.2fX".format(stats.peakGain), Cyber.textPrimary)
        }
        Spacer(Modifier.height(6.dp))
        EchoLine(
            "20→%.2fx  60→%.2fx  120→%.2fx  start ${stats.accelStartSpeed?.let { "%.1f".format(it) } ?: "—"} in/s  peak@${"%.1f".format(stats.peakSpeed)}"
                .format(stats.gain20, stats.gain60, stats.gain120)
                .uppercase(),
            Cyber.textDim, 10
        )
        Spacer(Modifier.height(8.dp))

        Canvas(
            Modifier.fillMaxWidth().weight(1f)
                .clip(shapeCard)
                .background(Cyber.abyss)
                .biosFrame(Cyber.borderSoft, Cyber.border, radius = 9.dp)
                .padding(6.dp)
        ) {
            val pad = 40f
            val w = size.width
            val h = size.height
            val chartW = w - pad * 2
            val chartH = h - pad * 2

            for (i in 0..8) {
                val y = pad + chartH * i / 8
                drawLine(Cyber.borderSoft, Offset(pad, y), Offset(w - pad, y), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f, 3.5f)))
            }
            for (i in 0..10) {
                val x = pad + chartW * i / 10
                drawLine(Cyber.borderSoft, Offset(x, pad), Offset(x, h - pad), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f, 3.5f)))
            }
            drawLine(Cyber.border, Offset(pad, h - pad), Offset(w - pad, h - pad), 1.5f)
            drawLine(Cyber.border, Offset(pad, pad), Offset(pad, h - pad), 1.5f)

            for (i in 0..10) {
                val x = pad + chartW * i / 10
                drawLine(Cyber.border, Offset(x, h - pad), Offset(x, h - pad + 5f), 1.2f)
            }
            for (i in 0..8) {
                val y = pad + chartH * i / 8
                drawLine(Cyber.border, Offset(pad - 5f, y), Offset(pad, y), 1.2f)
            }

            var maxG = 1.0
            val pts = ArrayList<Pair<Double, Double>>()
            for (i in 0..500) {
                val x: Double = maxIn * i.toDouble() / 500.0
                val g: Double = CurveEngine.gainAt(accel, x)
                if (g > maxG) maxG = g
                pts.add(Pair(x, g))
            }
            val yMax = maxG * 1.15

            fun px(x: Double) = pad + (x / maxIn * chartW).toFloat()
            fun py(g: Double) = h - pad - (g / yMax * chartH).toFloat()

            val refY = py(1.0)
            drawLine(Cyber.cyan.copy(alpha = 0.22f), Offset(pad, refY), Offset(w - pad, refY), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))

            val fill = Path().apply {
                moveTo(px(pts.first().first), h - pad)
                pts.forEach { p -> lineTo(px(p.first), py(p.second)) }
                lineTo(px(pts.last().first), h - pad); close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(Cyber.cyan.copy(alpha = 0.09f), Color.Transparent), pad, h - pad))

            val curve = Path()
            pts.forEachIndexed { i, p ->
                if (i == 0) curve.moveTo(px(p.first), py(p.second))
                else curve.lineTo(px(p.first), py(p.second))
            }
            drawPath(curve, Cyber.textPrimary, style = Stroke(width = 2.0f, cap = StrokeCap.Round))

            issues.forEach { pair ->
                val xVal = pair.first
                val cx = px(xVal); val cy = py(CurveEngine.gainAt(accel, xVal))
                drawCircle(Cyber.amber, radius = 7f, center = Offset(cx, cy), style = Stroke(1.2f))
                drawCircle(Cyber.amber, radius = 2.5f, center = Offset(cx, cy))
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            for (i in 0..5) {
                val v = maxIn * i / 5.0
                TermText("%.0f".format(v), Cyber.textMuted, 9, modifier = Modifier.weight(1f))
            }
            TermText("IN/S", Cyber.textFaint, 9)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier.clip(shapeButton)
            .background(Cyber.abyss)
            .biosFrame(color.copy(alpha = 0.5f), color, radius = 8.dp)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        TermText(text, color, 9, weight = FontWeight.Bold, spacing = 1.4)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  DEVICES VIEW
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DevicesView(
    settings: Settings?,
    report: DeviceInspector.Report?,
    onUseMouse: (DeviceInspector.ConnectedMouse, String) -> Unit
) {
    var targetProfileName by remember(settings) {
        mutableStateOf(settings?.profiles?.firstOrNull()?.name.orEmpty())
    }

    Row(Modifier.fillMaxSize()) {
        SectionPanel("PROFILE TARGETS", "003", Cyber.textPrimary, Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    settings == null -> EchoLine("SELECT A PROFILE.", Cyber.textMuted)
                    report == null   -> EchoLine("SCANNING BUS ...", Cyber.amber)
                    else -> {
                        EchoLine(
                            if (report.defaultDevicesDisabled)
                                "DEFAULT DEVICES DISABLED — ONLY LISTED DEVICES RECEIVE ACCEL."
                            else
                                "DEFAULT DEVICES ENABLED — UNLISTED MICE MAY ALSO RECEIVE ACCEL.",
                            Cyber.textDim
                        )
                        Spacer(Modifier.height(8.dp))
                        report.targets.forEachIndexed { i, target ->
                            Box(
                                Modifier.fillMaxWidth()
                                    .clip(shapeCard)
                                    .background(Cyber.abyss)
                                    .biosFrame(
                                        if (target.connected) Cyber.green.copy(alpha = 0.45f) else Cyber.amber.copy(alpha = 0.45f),
                                        if (target.connected) Cyber.green else Cyber.amber,
                                        radius = 9.dp
                                    )
                                    .padding(11.dp)
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TermText("%03d //".format(i + 1), Cyber.textMuted, 9)
                                        Spacer(Modifier.width(6.dp))
                                        Box(Modifier.size(7.dp).background(if (target.connected) Cyber.green else Cyber.amber, CircleShape))
                                        Spacer(Modifier.width(6.dp))
                                        TermText(
                                            if (target.connected) "CONNECTED" else "NOT FOUND",
                                            if (target.connected) Cyber.green else Cyber.amber,
                                            9, weight = FontWeight.Bold, spacing = 1.4
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        TermText(target.device.name.uppercase(), Cyber.textPrimary, 11,
                                            weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    val c = target.device.config
                                    TermText(
                                        "  ${target.device.profile.uppercase()} · ${c.dpi} DPI · ${c.pollingRate} HZ",
                                        Cyber.textDim, 10
                                    )
                                    TermText("  ${target.device.id}", Cyber.textMuted, 9, spacing = 0.0)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        SectionPanel("AVAILABLE MICE", "004", Cyber.textPrimary, Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val internalProfiles = settings?.profiles.orEmpty()
                if (internalProfiles.size > 1) {
                    EchoLine("MAP NEW DEVICE TO:", Cyber.textMuted, 9)
                    Spacer(Modifier.height(5.dp))
                    internalProfiles.forEach { profile ->
                        CyberBtn(
                            profile.name.uppercase(),
                            { targetProfileName = profile.name },
                            if (targetProfileName == profile.name) Cyber.green else Cyber.violet,
                            Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(5.dp))
                }
                val mice = report?.connectedMice ?: emptyList()
                if (mice.isEmpty()) {
                    EchoLine("NO MICE DETECTED.", Cyber.textMuted)
                }
                mice.forEachIndexed { i, m ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(shapeCard)
                            .background(Cyber.abyss)
                            .biosFrame(Cyber.borderSoft, Cyber.border, radius = 9.dp)
                            .padding(11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TermText("%03d //".format(i + 1), Cyber.textMuted, 9)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            TermText(DeviceInspector.targetName(m).uppercase(), Cyber.textPrimary, 11, weight = FontWeight.Bold)
                            TermText(DeviceInspector.rawAccelDeviceId(m.instanceId), Cyber.textMuted, 9, spacing = 0.0)
                        }
                        Spacer(Modifier.width(8.dp))
                        CyberBtn(
                            "USE",
                            { onUseMouse(m, targetProfileName) },
                            Cyber.textPrimary,
                            Modifier.width(74.dp),
                            enabled = targetProfileName.isNotBlank()
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  SESSION VIEW
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SessionView(
    onRun: () -> Unit,
    watchdog: String,
    hotkeyState: HotkeyManager.HotkeyState,
    autoSwitchState: ForegroundWatcher.WatchState,
    enabled: Boolean,
    onArmHotkeys: () -> Unit,
    onDisarmHotkeys: () -> Unit,
    onToggleAutoSwitch: (Boolean) -> Unit,
    selectedProfile: ProfileManager.Entry?
) {
    var autoSwitchOn by remember { mutableStateOf(false) }
    val hotkeysArmed = hotkeyState.lastAction.contains("HOTKEYS ARMED") ||
            hotkeyState.lastAction.startsWith("CLUTCH") ||
            hotkeyState.lastAction.startsWith("PRESET") ||
            hotkeyState.lastAction == "ACCEL ON"

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SectionPanel("SESSION PREPARATION", "004", Cyber.textPrimary, Modifier.fillMaxWidth()) {
                EchoLine("RUNS THE FULL PRE-GAME SEQUENCE IN ONE KEY:", Cyber.textDim)
                EchoLine("  PHASE ONE   .. APPLIES SELECTED PROFILE", Cyber.textDim)
                EchoLine("  PHASE TWO   .. BITSUM HIGHEST PERFORMANCE PLAN", Cyber.textDim)
                Spacer(Modifier.height(12.dp))
                CyberBtn("PREPARE SESSION", onRun, Cyber.cyan, Modifier.fillMaxWidth(), enabled = enabled, big = true)
            }
            Spacer(Modifier.height(8.dp))
            SectionPanel("IN-GAME HOTKEYS", "005", Cyber.textPrimary, Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EchoLine("GLOBAL HOTKEYS LET YOU ADJUST SENS WITHOUT ALT-TABBING", Cyber.textDim, 9)
                    EchoLine("  CTRL+F1  .. CLUTCH LOW (75% SENS)", Cyber.textDim, 9)
                    EchoLine("  CTRL+F2  .. CLUTCH HIGH (135% SENS)", Cyber.textDim, 9)
                    EchoLine("  CTRL+F3  .. TOGGLE ACCEL ON/OFF", Cyber.textDim, 9)
                    EchoLine("  CTRL+SHIFT+F1 .. PISTOL / SHOTGUN / SNIPER / SNIPER+SHOTGUN", Cyber.textDim, 9)
                    Spacer(Modifier.height(8.dp))

                    if (hotkeyState.error != null) {
                        EchoLine("⚠ ${hotkeyState.error.uppercase()}", Cyber.amber, 9)
                        Spacer(Modifier.height(6.dp))
                    }

                    Row(Modifier.fillMaxWidth()) {
                        CyberBtn(
                            if (hotkeysArmed) "DISARM HOTKEYS" else "ARM HOTKEYS",
                            { if (hotkeysArmed) onDisarmHotkeys() else onArmHotkeys() },
                            if (hotkeysArmed) Cyber.green else Cyber.cyan,
                            Modifier.weight(1f),
                            enabled = enabled,
                            big = true
                        )
                    }
                    Spacer(Modifier.height(6.dp))

                    DotRow("STATUS",
                        if (hotkeysArmed) "ARMED" else "DISARMED",
                        if (hotkeysArmed) Cyber.green else Cyber.textDim,
                        Cyber.textMuted, 9)
                    DotRow("LAST ACTION", hotkeyState.lastAction.uppercase(), Cyber.textPrimary, Cyber.textMuted, 9)
                    if (hotkeyState.clutchLowActive)
                        EchoLine("● CLUTCH LOW ACTIVE", Cyber.amber, 9)
                    if (hotkeyState.clutchHighActive)
                        EchoLine("● CLUTCH HIGH ACTIVE", Cyber.amber, 9)
                    if (!hotkeyState.accelEnabled && hotkeysArmed)
                        EchoLine("● ACCEL OFF (RAW 1:1)", Cyber.red, 9)

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TermText("AUTO-SWITCH", Cyber.textMuted, 9, weight = FontWeight.Bold, spacing = 1.6)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f).height(1.dp).background(Cyber.borderSoft))
                    }
                    Spacer(Modifier.height(6.dp))
                    EchoLine("AUTO-APPLIES YOUR GAME PROFILE WHEN MC IS FOCUSED,", Cyber.textDim, 9)
                    EchoLine("SWITCHES TO RAW 1:1 WHEN YOU TAB OUT.", Cyber.textDim, 9)
                    Spacer(Modifier.height(6.dp))

                    Row(Modifier.fillMaxWidth()) {
                        CyberBtn(
                            if (autoSwitchOn) "STOP AUTO-SWITCH" else "AUTO-SWITCH ON",
                            {
                                autoSwitchOn = !autoSwitchOn
                                onToggleAutoSwitch(autoSwitchOn)
                            },
                            if (autoSwitchOn) Cyber.green else Cyber.violet,
                            Modifier.weight(1f),
                            enabled = enabled
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    if (autoSwitchState.activeGame != null) {
                        DotRow("ACTIVE GAME", autoSwitchState.activeGame.uppercase(), Cyber.green, Cyber.textMuted, 9)
                    }
                    if (autoSwitchState.error != null) {
                        EchoLine("⚠ ${autoSwitchState.error.uppercase()}", Cyber.amber, 9)
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SectionPanel("WATCHDOG", "006", Cyber.textPrimary, Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TermText("DRIVER HEALTH", Cyber.textDim, 10, spacing = 2.6)
                    Spacer(Modifier.height(6.dp))
                    TermText(watchdog.uppercase(), Cyber.textPrimary, 20, weight = FontWeight.Black, spacing = 2.0)
                }
            }
            Spacer(Modifier.height(8.dp))
            SectionPanel("SENSITIVITY METRICS", "007", Cyber.textPrimary, Modifier.fillMaxWidth().weight(1f)) {
                selectedProfile?.settings?.let { settings ->
                    val m = remember(settings) { SensitivityCalculator.compute(settings) }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        DotRow("BASE CM/360", SensitivityCalculator.formatCm(m.baseCmPer360), Cyber.textPrimary, Cyber.textMuted, 10)
                        DotRow("PEAK CM/360", SensitivityCalculator.formatCm(m.peakCmPer360), Cyber.green, Cyber.textMuted, 10)
                        DotRow("CM/360 @ 20 IN/S", SensitivityCalculator.formatCm(m.cmAt20), Cyber.textDim, Cyber.textMuted, 10)
                        DotRow("CM/360 @ 60 IN/S", SensitivityCalculator.formatCm(m.cmAt60), Cyber.cyan, Cyber.textMuted, 10)
                        DotRow("CM/360 @ 120 IN/S", SensitivityCalculator.formatCm(m.cmAt120), Cyber.textPrimary, Cyber.textMuted, 10)
                        DotRow("BASE EFFECTIVE DPI", "%.0f".format(m.baseEDpi), Cyber.textPrimary, Cyber.textMuted, 10)
                        DotRow("PEAK EFFECTIVE DPI", "%.0f".format(m.peakEDpi), Cyber.green, Cyber.textMuted, 10)
                        DotRow("PEAK GAIN", "%.2fx".format(m.peakGain), Cyber.cyan, Cyber.textMuted, 10)
                        m.accelStart?.let {
                            DotRow("ACCEL KICKS IN", "%.1f IN/S".format(it), Cyber.amber, Cyber.textMuted, 10)
                        }
                        Spacer(Modifier.height(6.dp))
                        EchoLine("VALUES ASSUME RAW INPUT, 6/11 WINDOWS, NO ENHANCE.", Cyber.textFaint, 8)
                        EchoLine("MINECRAFT DEFAULT SENS (~100%%) ≈ %.0f CM/360 AT %d DPI".format(
                            SensitivityCalculator.minecraftCm360(m.effectiveDpi, 100.0), m.effectiveDpi.toInt()), Cyber.textFaint, 8)
                    }
                } ?: EchoLine("SELECT A PROFILE TO SEE AIM METRICS", Cyber.textMuted)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  PRESETS VIEW
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PresetsView(selected: ProfileManager.Entry?, onLoadPreset: (PresetLibrary.Preset) -> Unit) {
    val grouped = remember { PresetLibrary.byCategory() }
    val activeName = selected?.displayName?.uppercase()

    SectionPanel("CURVE PRESET LIBRARY", "004", Cyber.textPrimary, Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            EchoLine("ONE-CLICK TUNED STARTING POINTS. LOAD A PRESET, THEN TWEAK IN EDITOR.", Cyber.textDim, 9)
            if (activeName != null) {
                EchoLine("ACTIVE .. $activeName", Cyber.green, 9)
            }
            Spacer(Modifier.height(8.dp))

            grouped.forEach { (category, presets) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TermText(category.uppercase(), Cyber.textPrimary, 11, weight = FontWeight.Bold, spacing = 2.0)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(Cyber.borderSoft))
                }
                Spacer(Modifier.height(6.dp))

                presets.forEach { preset ->
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(shapeCard)
                            .background(Cyber.abyss)
                            .biosFrame(Cyber.borderSoft, Cyber.border, radius = 9.dp)
                            .padding(10.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                TermText(preset.name.uppercase(), Cyber.textPrimary, 11, weight = FontWeight.Bold, spacing = 1.4)
                                Spacer(Modifier.height(3.dp))
                                TermText(preset.description, Cyber.textDim, 9, spacing = 0.8)
                                Spacer(Modifier.height(4.dp))
                                Row {
                                    Badge("${preset.dpi} DPI", Cyber.cyan)
                                    Spacer(Modifier.width(5.dp))
                                    if (preset.accelX.mode == "lut") {
                                        val stats = CurveEngine.stats(preset.accelX, 300.0)
                                        Badge("PEAK %.2fx".format(stats.peakGain), Cyber.green)
                                        Spacer(Modifier.width(5.dp))
                                        Badge("Y/X %.2f".format(preset.yxRatio), Cyber.violet)
                                    } else {
                                        Badge("NO ACCEL", Cyber.textDim)
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            CyberBtn(
                                "LOAD",
                                { onLoadPreset(preset) },
                                Cyber.cyan,
                                Modifier.width(78.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  CONFIG DOCTOR VIEW
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DoctorView(selected: ProfileManager.Entry?) {
    val settings = selected?.settings

    SectionPanel("CONFIG DOCTOR", "007", Cyber.textPrimary, Modifier.fillMaxSize()) {
        if (settings == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EchoLine("SELECT A PROFILE TO RUN DIAGNOSTICS.", Cyber.textMuted)
            }
            return@SectionPanel
        }

        val report = remember(settings) { ConfigDoctor.diagnose(settings) }
        val scoreColor = when {
            report.score >= 90 -> Cyber.green
            report.score >= 70 -> Cyber.amber
            else -> Cyber.red
        }

        Column(Modifier.fillMaxSize()) {
            // Score hero
            Box(
                Modifier.fillMaxWidth()
                    .clip(shapeCard)
                    .background(Cyber.abyss)
                    .biosFrame(Cyber.borderSoft, scoreColor, radius = 9.dp)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        TermText("PROFILE HEALTH", Cyber.textDim, 10, spacing = 1.6)
                        TermText(
                            if (report.hasErrors) "NEEDS ATTENTION"
                            else if (report.warnings.isNotEmpty()) "MINOR ISSUES"
                            else "CLEAN",
                            scoreColor, 18, weight = FontWeight.Black, spacing = 2.0
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TermText("${report.score}", scoreColor, 48, weight = FontWeight.Black, spacing = 0.0)
                    TermText("/100", Cyber.textMuted, 16, weight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(10.dp))

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (report.criticals.isEmpty() && report.warnings.isEmpty() && report.infos.isEmpty()) {
                    EchoLine("✓ NO ISSUES FOUND. YOUR CONFIG IS CLEAN.", Cyber.green, 10)
                }

                report.criticals.forEach { finding ->
                    FindingRow(finding, Cyber.red)
                    Spacer(Modifier.height(4.dp))
                }
                report.warnings.forEach { finding ->
                    FindingRow(finding, Cyber.amber)
                    Spacer(Modifier.height(4.dp))
                }
                report.infos.forEach { finding ->
                    FindingRow(finding, Cyber.cyanDim)
                    Spacer(Modifier.height(4.dp))
                }
                report.passes.forEach { finding ->
                    FindingRow(finding, Cyber.green)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun FindingRow(finding: ConfigDoctor.Finding, color: Color) {
    Box(
        Modifier.fillMaxWidth()
            .clip(shapeCard)
            .background(Cyber.abyss)
            .biosFrame(color.copy(alpha = 0.4f), color, radius = 9.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Badge(finding.label, color)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                TermText(finding.title.uppercase(), Cyber.textPrimary, 10, weight = FontWeight.Bold, spacing = 1.2)
                Spacer(Modifier.height(2.dp))
                TermText(finding.detail, Cyber.textDim, 9, spacing = 0.6)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  LOGS VIEW
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LogsView(log: List<String>) {
    SectionPanel("EVENT LOG", "005", Cyber.textPrimary, Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .clip(shapeCard)
                .background(Cyber.abyss)
                .biosFrame(Cyber.borderSoft, Cyber.border, radius = 9.dp)
                .padding(11.dp)
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                TermText("LOG BUFFER .. LAST 200 EVENTS", Cyber.textFaint, 10, spacing = 1.2)
                TermText("MODE  COLS=120 LINES=200", Cyber.textFaint, 10, spacing = 1.2)
                Spacer(Modifier.height(6.dp))
                if (log.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TermText("> ", Cyber.textFaint, 11)
                        TermText("AWAITING COMMANDS", Cyber.textMuted, 11)
                        Caret()
                    }
                }
                log.asReversed().forEach { line ->
                    val c = when {
                        "✓" in line -> Cyber.green
                        "✗" in line -> Cyber.red
                        "⚠" in line -> Cyber.amber
                        "⬢" in line || "⚡" in line -> Cyber.textPrimary
                        else -> Cyber.textDim
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TermText("> ", Cyber.textFaint, 11)
                        TermText(line, c, 11, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
