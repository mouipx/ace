package rawaccel.app.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresetLibraryTest {

    @Test
    fun `presets are grouped into categories`() {
        val grouped = PresetLibrary.byCategory()
        assertTrue(grouped.isNotEmpty(), "should have at least one category")
        assertTrue(grouped.containsKey("DEAD END"), "should include Dead End category")
    }

    @Test
    fun `dead end classic preset produces monotonic lut`() {
        val preset = PresetLibrary.presets.first { it.id == "dead-end-classic" }
        assertTrue(preset.accelX.data.isNotEmpty(), "should have LUT data")
        val issues = CurveEngine.monotonicityIssues(preset.accelX)
        assertTrue(issues.isEmpty(), "preset should be monotonic, got ${issues.size} dips")
    }

    @Test
    fun `all lut presets are monotonic`() {
        PresetLibrary.presets.filter { it.accelX.mode == "lut" }.forEach { preset ->
            val issues = CurveEngine.monotonicityIssues(preset.accelX)
            assertTrue(issues.isEmpty(),
                "Preset ${preset.id} has ${issues.size} monotonicity dips")
        }
    }

    @Test
    fun `dead end includes sniper shotgun loadout preset`() {
        val preset = PresetLibrary.presets.single { it.id == "dead-end-sniper-shotgun" }

        assertEquals("DEAD END", preset.category)
        assertEquals(false, preset.whole)
        assertEquals(1200, preset.dpi)
        assertEquals(0.92, preset.yxRatio)
        assertTrue(preset.accelY != null, "combo loadout should use a calmer vertical curve")
    }

    @Test
    fun `dual track flick is a separate stronger sibling of dead end dual`() {
        val original = PresetLibrary.presets.single { it.id == "dead-end-dual" }
        val tuned = PresetLibrary.presets.single { it.id == "dead-end-dual-track-flick" }

        assertEquals(1.40, original.accelX.limit)
        assertEquals(240.0, original.accelX.syncSpeed)
        assertEquals(1.30, original.accelY?.limit)
        assertEquals(200.0, original.accelY?.syncSpeed)
        assertEquals(false, original.whole)

        assertEquals("DUAL-AXIS", tuned.category)
        assertEquals(false, tuned.whole)
        assertEquals(1200, tuned.dpi)
        assertEquals(0.92, tuned.yxRatio)

        val originalHorizontal = ProfileEditorEngine.inferGuidedValues(ProfileEditorEngine.fromAccel(original.accelX))
        val tunedHorizontal = ProfileEditorEngine.inferGuidedValues(ProfileEditorEngine.fromAccel(tuned.accelX))
        assertTrue(tunedHorizontal.first < originalHorizontal.first, "horizontal accel should start sooner for flick pickup")
        assertTrue(tunedHorizontal.third > originalHorizontal.third, "horizontal peak should be stronger for fast turns")

        val originalVertical = ProfileEditorEngine.inferGuidedValues(ProfileEditorEngine.fromAccel(original.accelY!!))
        val tunedVertical = ProfileEditorEngine.inferGuidedValues(ProfileEditorEngine.fromAccel(tuned.accelY!!))
        assertTrue(tunedVertical.first > originalVertical.first, "vertical accel should start later for head-level tracking")
        assertTrue(tunedVertical.third < originalVertical.third, "vertical peak should be calmer than dead-end-dual")
    }

    @Test
    fun `dual track flick 1600 is a separate high dpi comparison profile`() {
        val tuned1200 = PresetLibrary.presets.single { it.id == "dead-end-dual-track-flick" }
        val tuned1600 = PresetLibrary.presets.single { it.id == "dead-end-dual-track-flick-1600" }

        assertEquals("DUAL-AXIS", tuned1600.category)
        assertEquals(false, tuned1600.whole)
        assertEquals(1600, tuned1600.dpi)
        assertEquals(1600.0, tuned1600.outputDpi)
        assertEquals(0.90, tuned1600.yxRatio)

        val horizontal1200 = ProfileEditorEngine.inferGuidedValues(ProfileEditorEngine.fromAccel(tuned1200.accelX))
        val horizontal1600 = ProfileEditorEngine.inferGuidedValues(ProfileEditorEngine.fromAccel(tuned1600.accelX))
        assertTrue(horizontal1600.first > horizontal1200.first, "1600 version should keep low-speed tracking calmer")
        assertTrue(horizontal1600.third > horizontal1200.third, "1600 version should still have a stronger flick tail")

        val vertical1200 = ProfileEditorEngine.inferGuidedValues(ProfileEditorEngine.fromAccel(tuned1200.accelY!!))
        val vertical1600 = ProfileEditorEngine.inferGuidedValues(ProfileEditorEngine.fromAccel(tuned1600.accelY!!))
        assertTrue(vertical1600.first > vertical1200.first, "1600 vertical accel should start later for smoother head tracking")
        assertTrue(vertical1600.third < vertical1200.third, "1600 vertical peak should be calmer than the 1200 comparison profile")
    }

    @Test
    fun `toSettings produces valid document`() {
        val preset = PresetLibrary.presets.first { it.id == "dead-end-classic" }
        val s = PresetLibrary.toSettings(preset, deviceName = "Test", deviceId = "HID\\VID_0000&PID_0000")
        assertEquals(1, s.profiles.size)
        assertEquals(1, s.devices.size)
        assertEquals("lut", s.profiles.single().accelX.mode)
        val report = ConfigDoctor.diagnose(s)
        assertTrue(report.criticals.isEmpty(),
            "preset settings should have no critical issues, got ${report.criticals}")
    }

    @Test
    fun `preset luts start at zero`() {
        PresetLibrary.presets.filter { it.accelX.mode == "lut" }.forEach { preset ->
            val d = preset.accelX.data
            assertEquals(0.0, d[0], "Preset ${preset.id} should start at x=0")
            assertEquals(0.0, d[1], "Preset ${preset.id} should start at y=0")
        }
    }

    @Test
    fun `preset peaks match advertised gain`() {
        PresetLibrary.presets.filter { it.accelX.mode == "lut" }.forEach { preset ->
            val stats = CurveEngine.stats(preset.accelX, 300.0)
            assertTrue(kotlin.math.abs(stats.peakGain - preset.accelX.limit) < 0.05,
                "Preset ${preset.id} peak ${stats.peakGain} vs expected ${preset.accelX.limit}")
        }
    }
}
