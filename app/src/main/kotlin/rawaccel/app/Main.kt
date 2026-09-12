package rawaccel.app

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.Image
import rawaccel.app.driver.DriverClient
import rawaccel.app.service.*
import rawaccel.app.ui.App
import rawaccel.app.ui.AppTitleBar
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.io.File

fun main() {
    val lockFile = File(
        System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"),
        "Ace/ace.instance.lock"
    )
    val instanceGuard = InstanceGuard.acquire(lockFile)
    if (instanceGuard == null) {
        System.err.println("Ace is already running; refusing a second instance.")
        return
    }

    try {
    configureBridgePath()
    application {
        val aceIcon = remember { loadAceWindowIcon() }
        val client = DriverClient()
        val profilesDir = remember { ProfileManager.defaultDir() }
        val profileManager = remember { ProfileManager(profilesDir) }
        val sessionManager = remember { SessionManager() }
        val appScope = remember { kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val sessionController = remember { SessionController() }
        val watchdog = remember { Watchdog(client, appScope, sessionController) }
        val hotkeyManager = remember {
            HotkeyManager(
                client,
                appScope,
                onSettingsApplied = { settings ->
                    sessionController.enqueue(appScope, "hotkey state") { watchdog.updateDesired(settings) }
                },
                sessionController = sessionController
            )
        }
        val foregroundWatcher = remember {
            ForegroundWatcher(
                client,
                appScope,
                profilesDir,
                onSettingsApplied = { settings ->
                    sessionController.enqueue(appScope, "foreground state") { watchdog.updateDesired(settings) }
                },
                sessionController = sessionController
            )
        }

        fun closeApp() {
            hotkeyManager.releaseAll()
            hotkeyManager.stop()
            foregroundWatcher.stop()
            watchdog.stop()
            appScope.cancel()
            exitApplication()
        }

        Window(
            onCloseRequest = ::closeApp,
            title = "Ace",
            icon = aceIcon,
            state = rememberWindowState(width = 1000.dp, height = 720.dp),
            undecorated = true
        ) {
            Column(Modifier.fillMaxSize()) {
                AppTitleBar(
                    modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                        var dragStartPointer: Point? = null
                        var dragStartWindow: Point? = null

                        detectDragGestures(
                            onDragStart = {
                                dragStartPointer = MouseInfo.getPointerInfo()?.location
                                dragStartWindow = window.location
                            },
                            onDrag = { _, _ ->
                                val pointer = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                                val pointerStart = dragStartPointer ?: return@detectDragGestures
                                val windowStart = dragStartWindow ?: return@detectDragGestures
                                window.setLocation(
                                    windowStart.x + pointer.x - pointerStart.x,
                                    windowStart.y + pointer.y - pointerStart.y
                                )
                            }
                        )
                    },
                    onMinimize = { window.isMinimized = true },
                    onMaximize = {
                        window.extendedState = if ((window.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
                            Frame.NORMAL
                        } else {
                            Frame.MAXIMIZED_BOTH
                        }
                    },
                    onClose = ::closeApp
                )
                Box(Modifier.weight(1f)) {
                    App(client, profileManager, sessionManager, watchdog, hotkeyManager, foregroundWatcher, sessionController)
                }
            }
        }
    }
    } finally {
        instanceGuard.close()
    }
}

/** Loads the window/taskbar icon directly from the packaged Java classpath. */
private fun loadAceWindowIcon(): BitmapPainter? = runCatching {
    val loader = Thread.currentThread().contextClassLoader ?: DriverClient::class.java.classLoader
    val bytes = requireNotNull(loader.getResourceAsStream("icons/Ace.png")) {
        "Missing bundled icon: icons/Ace.png"
    }.use { it.readBytes() }
    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.onFailure { error ->
    System.err.println("window icon unavailable: ${error.message}")
}.getOrNull()

/**
 * Point JNA at the bridge DLL automatically. Looks (in order) at: an explicit
 * `rawaccel.bridge.path` system property, then Gradle's native build folders.
 * JNA also checks the bundled win32-x86-64 resource inside the app jar.
 */
private fun configureBridgePath() {
    val dll = "rawaccel_bridge.dll"
    val wd = File(System.getProperty("user.dir"))
    val candidates = listOfNotNull(
        System.getProperty("rawaccel.bridge.path"),
        wd.resolve("build/native/bridge").absolutePath,
        wd.resolve("build/generated/nativeResources/win32-x86-64").absolutePath,
        wd.resolve("src/main/resources/win32-x86-64").absolutePath,
        wd.parentFile?.resolve("app/build/native/bridge")?.absolutePath,
        wd.parentFile?.resolve("app/build/generated/nativeResources/win32-x86-64")?.absolutePath,
        wd.absolutePath,
        wd.parentFile?.resolve("bridge")?.absolutePath
    )
    val found = candidates.firstOrNull { File(it, dll).isFile }
    if (found != null) {
        System.setProperty("jna.library.path", found)
        println("bridge DLL found at: $found")
    } else {
        println("bridge DLL not found on disk; checking bundled JNA resources")
    }
}