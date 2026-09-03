package rawaccel.app.driver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.sun.jna.ptr.IntByReference
import rawaccel.app.model.Settings
import rawaccel.app.service.DriverStateComparator

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

    private val mapper = jacksonObjectMapper()
    private val operationLock = Any()

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

    fun isPresent(): Boolean = synchronized(operationLock) {
        bridge?.let { return@synchronized runCatching { it.ra_present() == 1 }.getOrDefault(false) }
        false
    }

    fun version(): Result<String> = synchronized(operationLock) {
        val native = bridge ?: return@synchronized bridgeUnavailable()
        runCatching {
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
    }

    /**
     * Applies settings and then reads them back. A successful return therefore
     * means the driver accepted the complete profile, not merely that the write
     * IOCTL returned without an error.
     */
    fun apply(settings: Settings): Result<Unit> = synchronized(operationLock) {
        val native = bridge ?: return@synchronized bridgeUnavailable()
        runCatching {
            // IMPORTANT: JNA does NOT NUL-terminate byte[] arguments the way it does
            // for String. The C++ bridge calls nlohmann::json::parse(const char*) which
            // scans until '\0' — without a terminator it reads straight into garbage
            // heap memory, producing "expected end of input" / "invalid literal" errors
            // with random bytes appended (e.g. '@', '<U+0018>'). Append one zero byte so
            // the C-side parser stops cleanly.
            val jsonBytes = mapper.writeValueAsBytes(settings)
            val jsonCString = jsonBytes.copyOf(jsonBytes.size + 1)   // trailing 0 = NUL
            val err = ByteArray(4096)
            if (native.ra_apply_json(jsonCString, err, err.size) != 0) {
                throw IllegalStateException(err.decode().ifBlank { "Native bridge rejected the profile" })
            }

            val active = read().getOrThrow()
            DriverStateComparator.firstDifference(settings, active)?.let { difference ->
                throw IllegalStateException("Driver verification failed at $difference")
            }
            Unit
        }
    }

    fun read(): Result<Settings> = synchronized(operationLock) {
        val native = bridge ?: return@synchronized bridgeUnavailable()
        runCatching {
            // Large multi-profile banks can exceed 1 MiB when serialized as JSON.
            val out = ByteArray(8 shl 20)
            val err = ByteArray(1024)
            if (native.ra_read_json(out, out.size, err, err.size) == 0) {
                Result.success(mapper.readValue<Settings>(out.decode()))
            } else {
                Result.failure(IllegalStateException(err.decode()))
            }
        }.getOrElse { Result.failure(it) }
    }

    /** True if the bundled bridge is loaded and driver state can be read back. */
    fun canRead(): Boolean = bridge != null

    fun reset(): Result<Unit> = synchronized(operationLock) {
        val native = bridge ?: return@synchronized bridgeUnavailable()
        runCatching {
            val err = ByteArray(1024)
            if (native.ra_reset(err, err.size) == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(err.decode()))
            }
        }.getOrElse { Result.failure(it) }
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
