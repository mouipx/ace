package rawaccel.app.service

import rawaccel.app.model.DeviceConfig
import rawaccel.app.model.DeviceSettings
import rawaccel.app.model.Profile
import rawaccel.app.model.Settings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileManagerTest {
    @Test
    fun saveAndListRoundTripsSettingsFilesInSortedOrder() {
        val dir = Files.createTempDirectory("rawaccel-profiles").toFile()
        val manager = ProfileManager(dir)

        manager.save(Settings(profiles = listOf(Profile(name = "zeta"))), dir.resolve("zeta.json"))
        manager.save(Settings(profiles = listOf(Profile(name = "alpha"))), dir.resolve("alpha.json"))
        dir.resolve("bad.json").writeText("{ not valid json")

        val entries = manager.list()

        assertEquals(listOf("alpha", "zeta"), entries.map { it.displayName })
        assertEquals(listOf("alpha"), entries.first().profileNames)
    }

    @Test
    fun startupSelectionPrefersProfileMatchingActiveDriverOverAlphabeticalFirst() {
        val dir = Files.createTempDirectory("ace-startup-profile-match").toFile()
        val highDpi = Settings(
            defaultDeviceConfig = DeviceConfig(disable = true, dpi = 6400),
            profiles = listOf(Profile(name = "Dead End | Original Dual-Axis | 1200 DPI", outputDpi = 6400.0)),
            devices = listOf(
                DeviceSettings(
                    name = "Logitech USB Receiver",
                    profile = "Dead End | Original Dual-Axis | 1200 DPI",
                    id = "HID\\VID_046D&PID_C539&MI_01&Col01",
                    config = DeviceConfig(dpi = 6400)
                )
            )
        )
        val active = Settings(
            defaultDeviceConfig = DeviceConfig(disable = true, dpi = 1200),
            profiles = listOf(Profile(name = "Dead End - Dual Axis (Advanced)", outputDpi = 1200.0)),
            devices = listOf(
                DeviceSettings(
                    name = "Logitech USB Receiver",
                    profile = "Dead End - Dual Axis (Advanced)",
                    id = "HID\\VID_046D&PID_C539&MI_01&Col01",
                    config = DeviceConfig(dpi = 1200)
                )
            )
        )
        val entries = listOf(
            ProfileManager.Entry(dir.resolve("dead_end_dual_axis_realz.json"), highDpi),
            ProfileManager.Entry(dir.resolve("dead-end-dual.json"), active)
        )

        val selected = ProfileManager.matchingActiveDriverEntry(entries, active)

        assertEquals("dead-end-dual", selected?.displayName)
    }

    @Test
    fun startupSelectionPrefersDeadEndZombiesWhenDriverStateIsUnavailable() {
        val dir = Files.createTempDirectory("ace-dead-end-startup").toFile()
        val desktop = ProfileManager.Entry(
            dir.resolve("generic-tracking.json"),
            Settings(profiles = listOf(Profile(name = "desktop")))
        )
        val zombies = ProfileManager.Entry(
            dir.resolve("zombies_deadend.json"),
            Settings(profiles = listOf(Profile(name = "Dead End Zombies")))
        )

        val selected = ProfileManager.preferredStartupEntry(listOf(desktop, zombies), null)

        assertEquals("zombies_deadend", selected?.displayName)
    }

    @Test
    fun scanReportsMalformedProfilesInsteadOfSilentlyDroppingThem() {
        val dir = Files.createTempDirectory("rawaccel-profile-errors").toFile()
        val manager = ProfileManager(dir)
        manager.save(Settings(profiles = listOf(Profile(name = "valid"))), dir.resolve("valid.json"))
        dir.resolve("broken.json").writeText("{ definitely not json")

        val scan = manager.scan()

        assertEquals(listOf("valid"), scan.entries.map { it.displayName })
        assertEquals(listOf("broken.json"), scan.errors.map { it.file.name })
    }

    @Test
    fun saveKeepsVerifiedBackupOfPreviousProfile() {
        val dir = Files.createTempDirectory("rawaccel-profile-backup").toFile()
        val manager = ProfileManager(dir)
        val file = dir.resolve("active.json")
        manager.save(Settings(profiles = listOf(Profile(name = "first"))), file)
        manager.save(Settings(profiles = listOf(Profile(name = "second"))), file)

        assertEquals("second", manager.load(file).profiles.single().name)
        assertEquals("first", manager.load(dir.resolve("active.json.bak")).profiles.single().name)
        assertTrue(dir.listFiles().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun duplicateImportExportRestoreAndDeleteAreRecoverable() {
        val dir = Files.createTempDirectory("rawaccel-profile-operations").toFile()
        val manager = ProfileManager(dir)
        val originalFile = dir.resolve("active.json")
        val first = Settings(profiles = listOf(Profile(name = "first")))
        val second = Settings(profiles = listOf(Profile(name = "second")))
        manager.save(first, originalFile)
        val original = ProfileManager.Entry(originalFile, first)

        val duplicate = manager.duplicate(original)
        assertTrue(duplicate.file.isFile)
        assertEquals("first", duplicate.settings.profiles.single().name)

        val importSource = dir.resolve("outside-source.json")
        manager.save(second, importSource)
        val imported = manager.importProfile(importSource)
        assertTrue(imported.file.isFile)
        assertTrue(imported.file != importSource)

        val exportTarget = dir.resolve("exports/profile-without-extension")
        manager.export(original, exportTarget)
        assertTrue(dir.resolve("exports/profile-without-extension.json").isFile)

        manager.save(second, originalFile)
        val current = ProfileManager.Entry(originalFile, second)
        assertTrue(manager.hasBackup(current))
        val restored = manager.restoreBackup(current)
        assertEquals("first", restored.settings.profiles.single().name)
        assertEquals("second", manager.load(dir.resolve("active.json.bak")).profiles.single().name)

        val renamed = manager.rename(restored, "renamed bank")
        assertEquals("renamed-bank", renamed.displayName)
        assertFalse(originalFile.exists())
        assertTrue(renamed.file.isFile)

        val trashed = manager.delete(renamed)
        assertFalse(renamed.file.exists())
        assertTrue(trashed.isFile)
    }

    @Test
    fun legacyOriginalProfileRemainsUsableWithDistinctDriverName() {
        val dir = Files.createTempDirectory("ace-original-profile").toFile()
        val manager = ProfileManager(dir)
        val oldName = "Dead End | Unified v2 | 1200 DPI"
        val settings = Settings(
            profiles = listOf(Profile(name = oldName)),
            devices = listOf(rawaccel.app.model.DeviceSettings(profile = oldName, id = "HID\\TEST"))
        )
        manager.save(settings, dir.resolve("zombies_deadend_original.json"))

        val loaded = manager.scan().entries.single().settings

        assertEquals("Dead End | Original Dual-Axis | 1200 DPI", loaded.profiles.single().name)
        assertFalse(loaded.profiles.single().speed.whole)
        assertEquals(loaded.profiles.single().name, loaded.devices.single().profile)
    }

    @Test
    fun bundledProfilesLoad() {
        val dir = Files.createTempDirectory("rawaccel-default-profiles").toFile()
        val previous = System.getProperty("rawaccel.profiles.dir")

        try {
            System.setProperty("rawaccel.profiles.dir", dir.absolutePath)

            val profiles = ProfileManager(ProfileManager.defaultDir()).list()

            assertTrue(profiles.isNotEmpty(), "Expected bundled profiles in app/profiles")
            assertTrue(profiles.any { it.displayName == "zombies_deadend" })
            assertTrue(profiles.any { it.displayName == "zombies_deadend_original" })
        } finally {
            if (previous == null) {
                System.clearProperty("rawaccel.profiles.dir")
            } else {
                System.setProperty("rawaccel.profiles.dir", previous)
            }
        }
    }
}
