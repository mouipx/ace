package rawaccel.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes user-triggered session operations so they cannot change driver state out of order. */
class SessionController {
    private val operationLock = Mutex()
    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status

    suspend fun <T> run(label: String, block: suspend () -> T): T = operationLock.withLock {
        _status.value = label
        try {
            block()
        } finally {
            _status.value = "idle"
        }
    }

    fun enqueue(scope: CoroutineScope, label: String, block: suspend () -> Unit): Job =
        scope.launch { run(label, block) }

    suspend fun <T> run(block: suspend () -> T): T = run("session", block)
}
