package rawaccel.app.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import rawaccel.app.model.Settings
import kotlin.math.abs
import kotlin.math.max

/**
 * Compares requested settings with the state read back from the driver.
 *
 * LUT values are stored as 32-bit floats by Raw Accel, while profile JSON and
 * Kotlin use doubles. Comparing serialized JSON byte-for-byte therefore reports
 * false drift for ordinary values such as 55.04. This comparator preserves
 * strict structure/string/boolean checks while allowing native float rounding.
 */
object DriverStateComparator {
    private val mapper = jacksonObjectMapper()

    /**
     * App-only profile fields that are not part of the Raw Accel driver
     * schema. The driver read-back never contains them, so comparing them
     * would report permanent false drift and make the watchdog re-apply in a
     * loop. Skip them on both sides.
     */
    private val appOnlyFields = setOf("Preset cycle")

    data class Difference(
        val path: String,
        val expected: String,
        val actual: String
    ) {
        override fun toString(): String = "$path: expected $expected, got $actual"
    }

    fun firstDifference(expected: Settings, actual: Settings): Difference? {
        val expectedNode: JsonNode = mapper.valueToTree(expected)
        val actualNode: JsonNode = mapper.valueToTree(actual)
        return compareNodes(expectedNode, actualNode, "\$")
    }

    fun equivalent(expected: Settings, actual: Settings): Boolean =
        firstDifference(expected, actual) == null

    private fun compareNodes(expected: JsonNode, actual: JsonNode?, path: String): Difference? {
        if (actual == null || actual.isMissingNode) {
            return Difference(path, expected.toString(), "<missing>")
        }

        if (expected.isNumber && actual.isNumber) {
            val left = expected.asDouble()
            val right = actual.asDouble()
            if (!nearlyEqual(left, right)) return Difference(path, left.toString(), right.toString())
            return null
        }

        if (expected.nodeType != actual.nodeType) {
            return Difference(path, expected.toString(), actual.toString())
        }

        if (expected.isObject) {
            val expectedNames = expected.fieldNames().asSequence()
                .filter { it !in appOnlyFields }.toSet()
            val actualNames = actual.fieldNames().asSequence()
                .filter { it !in appOnlyFields }.toSet()
            if (expectedNames != actualNames) {
                return Difference(path, expectedNames.sorted().toString(), actualNames.sorted().toString())
            }
            for (name in expectedNames.sorted()) {
                compareNodes(expected.get(name), actual.get(name), "$path.$name")?.let { return it }
            }
            return null
        }

        if (expected.isArray) {
            if (expected.size() != actual.size()) {
                return Difference("$path.length", expected.size().toString(), actual.size().toString())
            }
            for (index in 0 until expected.size()) {
                compareNodes(expected[index], actual[index], "$path[$index]")?.let { return it }
            }
            return null
        }

        return if (expected == actual) null else Difference(path, expected.toString(), actual.toString())
    }

    /** Tolerance covers a double -> native float -> double round trip. */
    private fun nearlyEqual(left: Double, right: Double): Boolean {
        if (left == right) return true
        if (!left.isFinite() || !right.isFinite()) return false
        val tolerance = max(1e-6, max(abs(left), abs(right)) * 2e-6)
        return abs(left - right) <= tolerance
    }
}
