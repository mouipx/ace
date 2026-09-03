package rawaccel.app.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceInspectorTest {
    @Test
    fun rawAccelDeviceIdKeepsVidPidPrefixAndNormalizesCollectionCase() {
        val instanceId = "HID\\VID_046D&PID_C539&MI_01&COL02\\8&123456&0&0000"

        val id = DeviceInspector.rawAccelDeviceId(instanceId)

        assertEquals("HID\\VID_046D&PID_C539&MI_01&Col02", id)
    }

    @Test
    fun targetMatchingRejectsBlankIdsAndAcceptsNormalizedPrefix() {
        val instance = "HID\\VID_046D&PID_C539&MI_01&COL02\\8&123456&0&0000"

        assertFalse(DeviceInspector.matchesTarget("", instance))
        assertTrue(DeviceInspector.matchesTarget("hid/vid_046d&pid_c539&mi_01&col02", instance))
    }

    @Test
    fun targetNameUsesKnownVendorName() {
        val mouse = DeviceInspector.ConnectedMouse(
            name = "HID-compliant mouse",
            instanceId = "HID\\VID_046D&PID_C539&MI_01&COL02\\8&123456&0&0000"
        )

        assertEquals("Logitech USB Receiver", DeviceInspector.targetName(mouse))
    }
}
