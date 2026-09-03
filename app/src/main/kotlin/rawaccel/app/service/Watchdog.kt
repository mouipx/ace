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
    private val scope: CoroutineScope
) {
    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status

    private var job: Job? = null

    fun start(current: Settings) {
        stop()
        if (!client.canRead()) {
            _status.value = "bridge unavailable"
            return
        }

        _status.value = "watching"
        job = scope.launch {
            var reapplyCount = 0
            while (isActive) {
                delay(15_000)

                runCatching {
                    val active = client.read().getOrThrow()
                    val difference = DriverStateComparator.firstDifference(current, active)
                    if (difference != null) {
                        reapplyCount++
                        _status.value = "drift #$reapplyCount - re-applying"
                        client.apply(current).getOrThrow()
                        _status.value = "watching (re-applied #$reapplyCount)"
                    } else {
                        _status.value = "watching (ok)"
                    }
                }.onFailure { _status.value = "watchdog error: ${it.message}" }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = "stopped"
    }

}
