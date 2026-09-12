package rawaccel.app.service

import rawaccel.app.model.Settings
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loads/saves Settings documents from a profiles directory. Each .json file is
 * one Raw Accel `settings.json`-format document (like your zombies_deadend.json).
 */
class ProfileManager(private val profilesDir: File) {

    private val mapper = jacksonObjectMapper()

    data class Entry(val file: File, val settings: Settings) {
        val displayName: String get() = file.nameWithoutExtension
        val profileNames: List<String> get() = settings.profiles.map { it.name }
    }

    data class LoadError(val file: File, val message: String)
    data class ScanResult(val entries: List<Entry>, val errors: List<LoadError>)

    /**
     * Loads every JSON profile and preserves parse failures for diagnostics.
     * Previously malformed files disappeared silently from the profile list.
     */
    fun scan(): ScanResult {
        if (!profilesDir.isDirectory) return ScanResult(emptyList(), emptyList())

        val entries = mutableListOf<Entry>()
        val errors = mutableListOf<LoadError>()
        profilesDir.listFiles { file -> file.isFile && file.extension.equals("json", true) }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { file ->
                runCatching {
                    val loaded = mapper.readValue<Settings>(file)
                    Entry(file, normalizeKnownProfile(file, loaded))
                }
                    .onSuccess(entries::add)
                    .onFailure { error -> errors += LoadError(file, error.message ?: error.javaClass.simpleName) }
            }
        return ScanResult(entries, errors)
    }

    /** Give an older AppData copy a distinct driver name without changing its curve. */
    private fun normalizeKnownProfile(file: File, settings: Settings): Settings {
        if (!file.name.equals("zombies_deadend_original.json", ignoreCase = true)) return settings
        val profile = settings.profiles.singleOrNull() ?: return settings
        val distinctName = "Dead End | Original Dual-Axis | 1200 DPI"
        val normalizedProfile = profile.copy(
            name = distinctName,
            speed = profile.speed.copy(whole = false)
        )
        if (profile == normalizedProfile) return settings
        return settings.copy(
            profiles = listOf(normalizedProfile),
            devices = settings.devices.map { device ->
                if (device.profile == profile.name) device.copy(profile = distinctName) else device
            }
        )
    }

    fun list(): List<Entry> = scan().entries

    fun load(file: File): Settings = normalizeKnownProfile(file, mapper.readValue(file))

    fun importProfile(source: File): Entry {
        require(source.isFile) { "Profile file does not exist: ${source.absolutePath}" }
        val settings = load(source)
        val destination = uniqueFile(source.nameWithoutExtension.ifBlank { "imported-profile" })
        save(settings, destination)
        return Entry(destination, settings)
    }

    fun duplicate(entry: Entry): Entry {
        val destination = uniqueFile("${entry.displayName}-copy")
        save(entry.settings, destination)
        return Entry(destination, entry.settings)
    }

    /** Renames the profile-bank file without changing internal driver profile names. */
    fun rename(entry: Entry, newDisplayName: String): Entry {
        require(newDisplayName.isNotBlank()) { "Profile name can not be blank" }
        val safeName = safeBaseName(newDisplayName)
        val requested = profilesDir.resolve("$safeName.json")
        if (requested.canonicalFile == entry.file.canonicalFile) return entry
        val destination = uniqueFile(safeName)
        moveReplacing(entry.file, destination)
        val oldBackup = entry.file.resolveSibling("${entry.file.name}.bak")
        if (oldBackup.isFile) moveReplacing(oldBackup, destination.resolveSibling("${destination.name}.bak"))
        return Entry(destination, entry.settings)
    }

    fun export(entry: Entry, destination: File): File {
        val target = if (destination.extension.equals("json", ignoreCase = true)) {
            destination
        } else {
            File(destination.parentFile, "${destination.name}.json")
        }
        save(entry.settings, target)
        return target
    }

    /** Moves a profile into a local trash directory so deletion is recoverable. */
    fun delete(entry: Entry): File {
        require(entry.file.isFile) { "Profile no longer exists: ${entry.file.name}" }
        val trashDir = profilesDir.resolve(".trash").apply { mkdirs() }
        val target = trashDir.resolve("${entry.displayName}-${System.currentTimeMillis()}.json")
        moveReplacing(entry.file, target)
        entry.file.resolveSibling("${entry.file.name}.bak").takeIf { it.isFile }?.let { backup ->
            moveReplacing(backup, trashDir.resolve("${target.name}.bak"))
        }
        return target
    }

    fun hasBackup(entry: Entry): Boolean = entry.file.resolveSibling("${entry.file.name}.bak").isFile

    /** Restores the previous save; the current version becomes the new backup. */
    fun restoreBackup(entry: Entry): Entry {
        val backup = entry.file.resolveSibling("${entry.file.name}.bak")
        require(backup.isFile) { "No backup exists for ${entry.displayName}" }
        val previous = load(backup)
        save(previous, entry.file)
        return Entry(entry.file, previous)
    }

    private fun uniqueFile(requestedBaseName: String): File {
        val safeBase = safeBaseName(requestedBaseName)
        var candidate = profilesDir.resolve("$safeBase.json")
        var suffix = 2
        while (candidate.exists()) {
            candidate = profilesDir.resolve("$safeBase-$suffix.json")
            suffix++
        }
        return candidate
    }

    private fun safeBaseName(value: String): String = value
        .trim()
        .removeSuffix(".json")
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .ifBlank { "profile" }

    /**
     * Saves through a verified temporary file and keeps the previous version as
     * `<name>.json.bak`. This avoids half-written profiles if Windows, OneDrive,
     * or the process interrupts a write.
     */
    fun save(settings: Settings, file: File) {
        val parent = file.absoluteFile.parentFile
            ?: throw IllegalArgumentException("Profile file must have a parent directory")
        parent.mkdirs()
        val temp = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        val backup = File(parent, "${file.name}.bak")

        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp, settings)
            mapper.readValue<Settings>(temp) // verify the complete file before replacing anything

            if (file.isFile) moveReplacing(file, backup)
            try {
                moveReplacing(temp, file)
            } catch (error: Throwable) {
                if (!file.exists() && backup.isFile) {
                    runCatching { Files.copy(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                }
                throw error
            }
        } finally {
            temp.delete()
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val PROFILE_DIR_PROPERTY = "ace.profiles.dir"
        private const val LEGACY_PROFILE_DIR_PROPERTY = "rawaccel.profiles.dir"
        private const val RESOURCE_PROFILE_DIR = "profiles"
        private const val RESOURCE_PROFILE_INDEX = "$RESOURCE_PROFILE_DIR/index.txt"

        fun matchingActiveDriverEntry(entries: List<Entry>, active: Settings?): Entry? {
            val activeSettings = active ?: return null
            return entries.firstOrNull { entry ->
                DriverStateComparator.equivalent(entry.settings, activeSettings)
            }
        }

        fun preferredStartupEntry(entries: List<Entry>, active: Settings?): Entry? =
            matchingActiveDriverEntry(entries, active)
                ?: entries.firstOrNull { it.file.name.equals("zombies_deadend.json", ignoreCase = true) }
                ?: entries.firstOrNull()

        fun isDeadEndZombiesEntry(entry: Entry): Boolean =
            entry.file.name.equals("zombies_deadend.json", ignoreCase = true)

        fun defaultDir(): File {
            val dir = configuredProfilesDir()
            seedBundledProfiles(dir)
            return dir
        }

        private fun configuredProfilesDir(): File {
            val configuredDir = System.getProperty(PROFILE_DIR_PROPERTY)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: System.getProperty(LEGACY_PROFILE_DIR_PROPERTY)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            if (configuredDir != null) return File(configuredDir)

            val appData = System.getenv("APPDATA")?.trim()?.takeIf { it.isNotEmpty() }
            if (appData != null) {
                val target = File(appData, "Ace/profiles")
                migrateLegacyProfiles(File(appData, "RawAccel Studio/profiles"), target)
                return target
            }

            val home = File(System.getProperty("user.home"))
            val target = home.resolve(".ace/profiles")
            migrateLegacyProfiles(home.resolve(".rawaccel-studio/profiles"), target)
            return target
        }

        /** Preserve existing user profiles when upgrading from the former app name. */
        private fun migrateLegacyProfiles(legacyDir: File, targetDir: File) {
            if (!legacyDir.isDirectory || targetDir.listFiles()?.isNotEmpty() == true) return
            runCatching {
                targetDir.mkdirs()
                legacyDir.copyRecursively(targetDir, overwrite = false)
            }
        }

        private fun seedBundledProfiles(targetDir: File) {
            targetDir.mkdirs()

            if (copyResourceProfiles(targetDir)) return
            copyDevelopmentProfiles(targetDir)
        }

        private fun copyResourceProfiles(targetDir: File): Boolean {
            val loader = ProfileManager::class.java.classLoader
            val names = loader.getResourceAsStream(RESOURCE_PROFILE_INDEX)
                ?.bufferedReader()
                ?.use { reader ->
                    reader.readLines()
                        .map { it.trim() }
                        .filter { it.endsWith(".json", ignoreCase = true) }
                }
                ?: return false

            names.forEach { name ->
                val target = targetDir.resolve(name)
                if (!target.isFile) {
                    loader.getResourceAsStream("$RESOURCE_PROFILE_DIR/$name")?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }

            return true
        }

        private fun copyDevelopmentProfiles(targetDir: File) {
            val devDir = File(System.getProperty("user.dir"), "profiles")
            if (!devDir.isDirectory || devDir.canonicalFile == targetDir.canonicalFile) return

            devDir.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
                ?.forEach { source ->
                    val target = targetDir.resolve(source.name)
                    if (!target.isFile) source.copyTo(target)
                }
        }
    }
}
