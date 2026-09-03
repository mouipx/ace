package rawaccel.app.service

import rawaccel.app.model.DeviceSettings
import rawaccel.app.model.Settings
import java.util.concurrent.TimeUnit

object DeviceInspector {
    data class ConnectedMouse(
        val name: String,
        val instanceId: String
    )

    data class TargetStatus(
        val device: DeviceSettings,
        val connected: Boolean,
        val matchedInstanceId: String?
    )

    data class Report(
        val defaultDevicesDisabled: Boolean,
        val targets: List<TargetStatus>,
        val connectedMice: List<ConnectedMouse>,
        val error: String? = null
    )

    fun inspect(settings: Settings): Report {
        val miceResult = runCatching { connectedMice() }
        val mice = miceResult.getOrDefault(emptyList())
        val targets = settings.devices.map { target ->
            val match = mice.firstOrNull { matchesTarget(target.id, it.instanceId) }
            TargetStatus(
                device = target,
                connected = match != null,
                matchedInstanceId = match?.instanceId
            )
        }
        return Report(
            defaultDevicesDisabled = settings.defaultDeviceConfig.disable,
            targets = targets,
            connectedMice = mice,
            error = miceResult.exceptionOrNull()?.message
        )
    }

    fun matchesTarget(targetId: String, instanceId: String): Boolean {
        val normalizedTarget = normalize(targetId)
        return normalizedTarget.isNotEmpty() && normalize(instanceId).startsWith(normalizedTarget)
    }

    fun rawAccelDeviceId(instanceId: String): String {
        val parts = instanceId.split('\\')
        if (parts.size < 2) return instanceId
        return "${parts[0]}\\${parts[1]}".replace(
            Regex("&COL([0-9A-F]{2})", RegexOption.IGNORE_CASE)
        ) { match -> "&Col${match.groupValues[1]}" }
    }

    fun targetName(mouse: ConnectedMouse): String {
        val id = rawAccelDeviceId(mouse.instanceId)
        val vid = Regex("VID_([0-9A-F]{4})", RegexOption.IGNORE_CASE).find(id)?.groupValues?.get(1)?.uppercase()
        val pid = Regex("PID_([0-9A-F]{4})", RegexOption.IGNORE_CASE).find(id)?.groupValues?.get(1)?.uppercase()
        val vendor = when (vid) {
            "046D" -> if (pid == "C539") "Logitech USB Receiver" else "Logitech Mouse"
            "05AC" -> "Apple Mouse"
            "258A" -> "USB Gaming Mouse"
            "0C45" -> "USB Mouse"
            else -> null
        }
        return when {
            vendor != null -> vendor
            mouse.name.equals("HID-compliant mouse", ignoreCase = true) -> "HID Mouse"
            mouse.name.isNotBlank() -> mouse.name
            else -> "HID Mouse"
        }
    }

    private fun connectedMice(): List<ConnectedMouse> {
        val command = "Get-PnpDevice -Class Mouse -Status OK | " +
                "ForEach-Object { \$_.FriendlyName + ([char]9) + \$_.InstanceId }"
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            command
        ).redirectErrorStream(true).start()

        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Timed out while checking connected mouse devices")
        }

        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.exitValue() != 0) {
            error(output.ifBlank { "Get-PnpDevice failed with exit code ${process.exitValue()}" })
        }

        return output.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size == 2 && parts[1].isNotBlank()) {
                    ConnectedMouse(parts[0].ifBlank { "HID mouse" }, parts[1])
                } else {
                    null
                }
            }
            .toList()
    }

    private fun normalize(value: String): String =
        value.trim().replace('/', '\\').uppercase()
}
