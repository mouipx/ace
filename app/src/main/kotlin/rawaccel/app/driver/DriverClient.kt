package rawaccel.app.driver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.sun.jna.ptr.IntByReference
import rawaccel.app.model.Settings
import rawaccel.app.service.DriverStateComparator
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Talks to the Raw Accel driver through this project's native bridge.
 *
 * Gradle builds rawaccel_bridge.dll from ../bridge and bundles it as a
 * win32-x86-64 JNA resource. Apply, query, reset, and watchdog all use this
 * owned backend directly.
 *
 * Encoding note: all string data between JVM and the bridge is UTF-8. We do NOT
 * rely on JNA's default String→const char* mapping (which on Windows uses the
 * system ANSI codepage and corrupts any non-ASCII bytes like `·` in preset
 * names). Instead we serialize Jackson's output straight to UTF-8 bytes and
 * pass a byte[]; the bridge's nlohmann::json parser expects UTF-8, so roundtrips
 * for names like "Dead End · Dual Axis (Advanced)" stay valid.
 */
class DriverClient {

    enum class Compatibility {
        UNAVAILABLE,
        SUPPORTED,
        OLDER_THAN_SUPPORTED,
        NEWER_THAN_BRIDGE
    }

    enum class ErrorCategory {
        DRIVER_MISSING,
        ACCESS_DENIED,
        VERSION_MISMATCH,
        INVALID_DATA,
        TIMEOUT,
        IO_ERROR
    }

    data class OperationDiagnostics(
        val transactionId: Long,
        val operation: String,
        val durationMs: Long,
        val succeeded: Boolean,
        val category: ErrorCategory? = null,
        val message: String? = null
    )

    data class Health(
        val bridgeLoaded: Boolean,
        val driverPresent: Boolean,
        val driverVersion: String?,
        val profileCount: Int?,
        val deviceCount: Int?,
        val compatibility: Compatibility,
        val error: String? = null
    )

    companion object {
        private const val BRIDGE_MAJOR = 1
        private const val BRIDGE_MINOR = 7
        private const val BRIDGE_PATCH = 0

        fun classifyCompatibility(version: String?): Compatibility {
            if (version == null) return Compatibility.UNAVAILABLE
            val parts = version.removePrefix("v").split('.')
            if (parts.size != 3) return Compatibility.UNAVAILABLE
            val numbers = parts.map { it.toIntOrNull() ?: return Compatibility.UNAVAILABLE }
            val installed = numbers[0] * 1_000_000 + numbers[1] * 1_000 + numbers[2]
            val bridge = BRIDGE_MAJOR * 1_000_000 + BRIDGE_MINOR * 1_000 + BRIDGE_PATCH
            return when {
                installed < bridge -> Compatibility.OLDER_THAN_SUPPORTED
                installed > bridge -> Compatibility.NEWER_THAN_BRIDGE
                else -> Compatibility.SUPPORTED
            }
        }
    }

    private val mapper = jacksonObjectMapper()
    private val operationLock = Any()
    private val transactionSequence = AtomicLong(0)
    private val lastOperation = AtomicReference<OperationDiagnostics?>(null)

    private val bridge: RawAccelBridge?
    private val bridgeLoadError: Throwable?

    init {
        var loadError: Throwable? = null
        val loadedBridge: RawAccelBridge? = try {
            RawAccelBridge.INSTANCE.also {
                println("driver: using bundled native bridge (rawaccel_bridge.dll)")
            }
        } catch (e: Throwable) {
            loadError = e
            println("driver: rawaccel_bridge.dll is required but could not be loaded (${e.message})")
            null
        }
        bridge = loadedBridge
        bridgeLoadError = loadError
    }

    fun isBridgeAvailable(): Boolean = bridge != null

    fun bridgeError(): String? = bridgeLoadError?.message

    fun lastOperationDiagnostics(): OperationDiagnostics? = lastOperation.get()

    /** Performs a read-only end-to-end bridge and driver health check. */
    fun health(): Health {
        if (bridge == null) {
            return Health(
                bridgeLoaded = false,
                driverPresent = false,
                driverVersion = null,
                profileCount = null,
                deviceCount = null,
                compatibility = Compatibility.UNAVAILABLE,
                error = bridgeError() ?: "native bridge is unavailable"
            )
        }

        val present = isPresent()
        val versionResult = version()
        if (versionResult.isFailure) {
            return Health(true, present, null, null, null, Compatibility.UNAVAILABLE,
                "version check failed: ${versionResult.exceptionOrNull()?.message}")
        }

        val settingsResult = read()
        if (settingsResult.isFailure) {
            val version = versionResult.getOrNull()
            return Health(true, present, version, null, null, classifyCompatibility(version),
                "driver read failed: ${settingsResult.exceptionOrNull()?.message}")
        }

        val settings = settingsResult.getOrThrow()
        val version = versionResult.getOrNull()
        val compatibility = classifyCompatibility(version)
        val compatibilityError = when (compatibility) {
            Compatibility.OLDER_THAN_SUPPORTED -> "driver $version is older than the supported bridge protocol"
            Compatibility.NEWER_THAN_BRIDGE -> "driver $version is newer than the bundled bridge protocol"
            Compatibility.UNAVAILABLE -> "driver version is unavailable or malformed"
            Compatibility.SUPPORTED -> null
        }
        return Health(true, present, version, settings.profiles.size, settings.devices.size,
            compatibility, compatibilityError)
    }

    fun isPresent(): Boolean = synchronized(operationLock) {
        bridge?.let { return@synchronized runCatching { it.ra_present() == 1 }.getOrDefault(false) }
        false
    }

    fun version(): Result<String> = synchronized(operationLock) {
        val native = bridge ?: return@synchronized bridgeUnavailable()
        val transactionId = transactionSequence.incrementAndGet()
        val started = System.nanoTime()
        val result = runCatching {
            val maj = IntByReference()
            val min = IntByReference()
            val pat = IntByReference()
            val err = ByteArray(512)
            if (native.ra_version(maj, min, pat, err, err.size) == 0) {
                Result.success("v${maj.value}.${min.value}.${pat.value}")
            } else {
                Result.failure(IllegalStateException(err.decode()))
            }
        }.getOrElse { Result.failure(it) }
        return@synchronized record("version", transactionId, started, result)
    }

    /**
     * Applies settings and then reads them back. A successful return therefore
     * means the driver accepted the complete profile, not merely that the write
     * IOCTL returned without an error.
     */
    fun apply(settings: Settings): Result<Unit> = synchronized(operationLock) {
        val native = bridge ?: return@synchronized bridgeUnavailable()
        val transactionId = transactionSequence.incrementAndGet()
        val started = System.nanoTime()
        val result = runCatching {
            val previous = read().getOrElse { failure ->
                throw IllegalStateException("Could not capture current driver state before Apply", failure)
            }

            // IMPORTANT: JNA does NOT NUL-terminate byte[] arguments the way it does
            // for String. The C++ bridge calls nlohmann::json::parse(const char*) which
            // scans until '\0' — without a terminator it reads straight into garbage
            // heap memory, producing "expected end of input" / "invalid literal" errors
            // with random bytes appended (e.g. '@', '<U+0018>'). Append one zero byte so
            // the C-side parser stops cleanly.
            val jsonBytes = mapper.writeValueAsBytes(settings)
            val jsonCString = jsonBytes.copyOf(jsonBytes.size + 1)   // trailing 0 = NUL
            val err = ByteArray(4096)
            try {
                if (native.ra_apply_json(jsonCString, err, err.size) != 0) {
                    throw IllegalStateException(err.decode().ifBlank { "Native bridge rejected the profile" })
                }

                val active = read().getOrThrow()
                DriverStateComparator.firstDifference(settings, active)?.let { difference ->
                    throw IllegalStateException("Driver verification failed at $difference")
                }
            } catch (failure: Throwable) {
                val rollbackFailure = runCatching {
                    val restoreJson = mapper.writeValueAsBytes(previous)
                    val restoreBytes = restoreJson.copyOf(restoreJson.size + 1)
                    val restoreErr = ByteArray(4096)
                    if (native.ra_apply_json(restoreBytes, restoreErr, restoreErr.size) != 0) {
                        throw IllegalStateException(restoreErr.decode().ifBlank { "native rollback rejected" })
                    }
                }.exceptionOrNull()
                if (rollbackFailure != null) {
                    throw IllegalStateException(
                        "Apply failed and rollback could not be completed: ${rollbackFailure.message}",
                        failure
                    )
                }
                throw failure
            }
            Unit
        }
        return@synchronized record("apply", transactionId, started, result)
    }

    /** Validates a profile through the native marshaller without touching the driver. */
    fun validate(settings: Settings): Result<Unit> {
        val transactionId = transactionSequence.incrementAndGet()
        val started = System.nanoTime()
        val result = runCatching {
            val native = bridge ?: throw IllegalStateException(bridgeError() ?: "native bridge is unavailable")
            val serialized = mapper.writeValueAsBytes(settings)
            val jsonBytes = serialized.copyOf(serialized.size + 1)
            val err = ByteArray(4096)
            if (native.ra_validate_json(jsonBytes, err, err.size) != 0) {
                throw IllegalArgumentException(err.decode().ifBlank { "Native bridge rejected the profile" })
            }
        }
        return record("validate", transactionId, started, result)
    }

    /** Applies only when the live driver differs, avoiding needless state resets. */
    fun applyIfChanged(settings: Settings): Result<Boolean> = synchronized(operationLock) {
        val native = bridge ?: return@synchronized bridgeUnavailable()
        runCatching<Boolean> {
            val active = read().getOrThrow()
            if (DriverStateComparator.equivalent(settings, active)) {
                false
            } else {
                apply(settings).getOrThrow()
                true
            }
        }
    }

    fun read(): Result<Settings> = synchronized(operationLock) {
        val native = bridge ?: return@synchronized bridgeUnavailable()
        val transactionId = transactionSequence.incrementAndGet()
        val started = System.nanoTime()
        val result = runCatching {
            // Large multi-profile banks can exceed 1 MiB when serialized as JSON.
            val out = ByteArray(8 shl 20)
            val err = ByteArray(1024)
            if (native.ra_read_json(out, out.size, err, err.size) == 0) {
                Result.success(mapper.readValue<Settings>(out.decode()))
            } else {
                Result.failure(IllegalStateException(err.decode()))
            }
        }.getOrElse { Result.failure(it) }
        return@synchronized record("read", transactionId, started, result)
    }

    /** True if the bundled bridge is loaded and driver state can be read back. */
    fun canRead(): Boolean = bridge != null

    fun reset(): Result<Unit> = synchronized(operationLock) {
        val native = bridge ?: return@synchronized bridgeUnavailable()
        val transactionId = transactionSequence.incrementAndGet()
        val started = System.nanoTime()
        val result = runCatching {
            val err = ByteArray(1024)
            if (native.ra_reset(err, err.size) == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(err.decode()))
            }
        }.getOrElse { Result.failure(it) }
        return@synchronized record("reset", transactionId, started, result)
    }

    private fun <T> record(
        operation: String,
        transactionId: Long,
        started: Long,
        result: Result<T>
    ): Result<T> {
        val failure = result.exceptionOrNull()
        val message = failure?.message?.ifBlank { failure::class.simpleName ?: "operation failed" }
        val category = message?.let(::classifyError)
        val durationMs = if (started == 0L) 0L else (System.nanoTime() - started) / 1_000_000L
        lastOperation.set(OperationDiagnostics(transactionId, operation, durationMs, result.isSuccess, category, message))
        return result
    }

    private fun classifyError(message: String): ErrorCategory {
        val normalized = message.lowercase()
        return when {
            "timed out" in normalized || "timeout" in normalized -> ErrorCategory.TIMEOUT
            "access is denied" in normalized || "access denied" in normalized -> ErrorCategory.ACCESS_DENIED
            "not installed" in normalized || "could not be opened" in normalized || "device not found" in normalized -> ErrorCategory.DRIVER_MISSING
            "version" in normalized || "protocol" in normalized || "reinstallation required" in normalized -> ErrorCategory.VERSION_MISMATCH
            "invalid" in normalized || "rejected" in normalized || "malformed" in normalized -> ErrorCategory.INVALID_DATA
            else -> ErrorCategory.IO_ERROR
        }
    }

    /** Decode a null-terminated C byte buffer into a Kotlin String as UTF-8. */
    private fun ByteArray.decode(): String {
        val terminator = indexOf(0)
        val length = if (terminator >= 0) terminator else size
        return String(this, 0, length, RawAccelBridge.UTF8)
    }

    private fun <T> bridgeUnavailable(): Result<T> =
        Result.failure(
            IllegalStateException(
                "rawaccel_bridge.dll is required but was not loaded. " +
                        "Run the Gradle build so the project builds and bundles its own native bridge." +
                        (bridgeLoadError?.message?.let { " Details: $it" } ?: "")
            )
        )
}
