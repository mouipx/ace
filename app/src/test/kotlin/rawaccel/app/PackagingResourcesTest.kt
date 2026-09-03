package rawaccel.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackagingResourcesTest {
    @Test
    fun aceIconsAreBundledAndIcoContainsMultipleSizes() {
        val loader = PackagingResourcesTest::class.java.classLoader
        assertNotNull(loader.getResource("icons/Ace.png"), "The source/fallback Ace.png must be bundled")

        val ico = assertNotNull(loader.getResourceAsStream("icons/Ace.ico"), "Ace.ico must be bundled for Windows packaging")
            .use { it.readBytes() }
        assertTrue(ico.size > 6, "Ace.ico is unexpectedly small")
        assertEquals(listOf(0, 0, 1, 0), ico.take(4).map { it.toInt() and 0xff }, "Invalid ICO header")
        val imageCount = (ico[4].toInt() and 0xff) or ((ico[5].toInt() and 0xff) shl 8)
        assertTrue(imageCount >= 7, "Ace.ico should contain the 7 required icon sizes")
    }

    @Test
    fun nativeBridgeAndDefaultProfilesAreBundled() {
        val loader = PackagingResourcesTest::class.java.classLoader
        val bridge = assertNotNull(
            loader.getResourceAsStream("win32-x86-64/rawaccel_bridge.dll"),
            "rawaccel_bridge.dll must be bundled as a JNA Windows x64 resource"
        ).use { it.readBytes() }
        assertTrue(bridge.size > 1024, "Bundled rawaccel_bridge.dll is unexpectedly small")

        val profileNames = assertNotNull(
            loader.getResourceAsStream("profiles/index.txt"),
            "profiles/index.txt must be bundled so packaged installs seed the same defaults as dev runs"
        ).bufferedReader().use { reader ->
            reader.readLines().map { it.trim() }.filter { it.endsWith(".json", ignoreCase = true) }
        }

        assertTrue("zombies_deadend.json" in profileNames, "zombies_deadend.json must be listed in bundled defaults")
        assertTrue("zombies_deadend_original.json" in profileNames, "zombies_deadend_original.json must be listed in bundled defaults")
        assertTrue(
            "dead-end-dual-track-flick.json" in profileNames,
            "dead-end-dual-track-flick.json must be listed in bundled defaults"
        )
        assertTrue(
            "dead-end-dual-track-flick-1600.json" in profileNames,
            "dead-end-dual-track-flick-1600.json must be listed in bundled defaults"
        )
        profileNames.forEach { name ->
            assertNotNull(loader.getResource("profiles/$name"), "Bundled profile listed in index is missing: $name")
        }
    }
}
