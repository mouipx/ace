@file:Suppress("FunctionName")

package rawaccel.app.driver

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import java.nio.charset.Charset

/**
 * JNA binding to `rawaccel_bridge.dll` (see bridge/). The bridge does all the
 * hard struct marshalling in C++ against Raw Accel's own headers; here we only
 * pass byte buffers and ints, which is inherently safe.
 *
 * Encoding: the C++ bridge uses nlohmann::json which parses incoming text as
 * strict UTF-8. We pass UTF-8 bytes via `byte[]` instead of JNA's `String`
 * because JNA's default String→const char* mapping on Windows uses the system
 * ANSI codepage (Windows-1252) and mangles non-ASCII characters like the
 * middle dot `·` in preset names. Using `byte[]` bypasses JNA's codepage
 * conversion entirely — the bridge receives Jackson's exact UTF-8 output.
 *
 * Function names match the native exports exactly (snake_case); the file-level
 * `@Suppress("FunctionName")` keeps Kotlin's style linter happy about that.
 */
interface RawAccelBridge : Library {

    /** Apply a UTF-8 encoded settings.json document. 0 = ok. */
    fun ra_apply_json(json: ByteArray, err: ByteArray, errCap: Int): Int

    /** Validate settings without opening or writing the driver. 0 = ok. */
    fun ra_validate_json(json: ByteArray, err: ByteArray, errCap: Int): Int

    /** Read the active driver config back into a UTF-8 buffer. 0 = ok. */
    fun ra_read_json(out: ByteArray, outCap: Int, err: ByteArray, errCap: Int): Int

    /** Clear all accel. 0 = ok. */
    fun ra_reset(err: ByteArray, errCap: Int): Int

    /** Driver version. 0 = ok. */
    fun ra_version(maj: IntByReference, min: IntByReference, patch: IntByReference,
                   err: ByteArray, errCap: Int): Int

    /** 1 if the rawaccel device is present, else 0. */
    fun ra_present(): Int

    companion object {
        /** Force UTF-8 encoding for any String params (belt-and-suspenders; we use byte[] for the JSON payload). */
        private val OPTIONS: Map<String, Any> = mapOf(
            Library.OPTION_STRING_ENCODING to "UTF-8"
        )

        /** Loads the bundled rawaccel_bridge.dll from JNA resources or jna.library.path. */
        val INSTANCE: RawAccelBridge by lazy {
            Native.load("rawaccel_bridge", RawAccelBridge::class.java, OPTIONS)
        }

        /** Cached UTF-8 charset so hot paths don't pay Charset.forName lookups. */
        val UTF8: Charset = Charset.forName("UTF-8")
    }
}
