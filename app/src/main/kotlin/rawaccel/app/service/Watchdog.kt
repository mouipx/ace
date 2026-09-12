package rawaccel.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rawaccel.app.driver.DriverClient
import rawaccel.app.model.Settings

/**
 * Auto-heal loop.
 *
 * Reads the actual driver state through rawaccel_bridge.dll and re-applies only
 * on true drift.
 */
class Watchdog(
    private val client: DriverClient,
    private val scope: CoroutineScope,
    private val sessionController: SessionController? = null
) {
    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status

    private var job: Job? = null
    @Volatile private var desiredSettings: Settings? = null

    fun start(current: Settings) {
        stop()
        if (!client.canRead()) {
            _status.value = "bridge unavailable"
            return
        }

    desiredSettings = current
        _status.value = "watching"
        job = scope.launch {
            var reapplyCount = 0
            while (isActive) {
                var disconnected = false
                try {
                    val desired = desiredSettings ?: continue
                    val check: suspend () -> Unit = {
                        val active = client.read().getOrThrow()
                        val difference = DriverStateComparator.firstDifference(desired, active)
                        if (difference != null) {
                            reapplyCount++
                            _status.value = "drift #$reapplyCount - re-applying"
                            client.apply(desired).getOrThrow()
                            _status.value = "watching (re-applied #$reapplyCount)"
                        } else {
                            _status.value = "watching (ok)"
                        }
                    }
                    if (sessionController != null) {
                        sessionController.run("watchdog check", check)
                    } else {
                        check()
                    }
                } catch (error: Throwable) {
                    val diagnostic = client.lastOperationDiagnostics()
                    if (diagnostic?.category == DriverClient.ErrorCategory.DRIVER_MISSING) {
                        _status.value = "stopped: driver disconnected"
                        disconnected = true
                    } else {
                        _status.value = "watchdog error: ${error.message}"
                    }
                }

                if (disconnected) break
                delay(15_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        desiredSettings = null
        _status.value = "stopped"
    }

    fun updateDesired(settings: Settings) {
        if (job?.isActive == true) desiredSettings = settings
    }

}
