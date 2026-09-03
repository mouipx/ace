package rawaccel.app.service

import java.util.concurrent.TimeUnit

/** Activates the preferred Windows power plan without allowing a command to hang the UI. */
class SessionManager {

    data class SessionReport(val powerPlan: String)

    private data class CommandResult(val exitCode: Int, val output: String) {
        val succeeded: Boolean get() = exitCode == 0
    }

    private fun run(cmd: List<String>, timeoutSeconds: Long = 10): CommandResult = runCatching {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            return@runCatching CommandResult(-1, "Command timed out after ${timeoutSeconds}s")
        }
        CommandResult(process.exitValue(), process.inputStream.bufferedReader().readText().trim())
    }.getOrElse { CommandResult(-1, it.message ?: it.javaClass.simpleName) }

    /** Extracts a scheme GUID by its display name from `powercfg /list` output. */
    fun powerPlanGuid(listOutput: String, displayName: String): String? {
        val guidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        return listOutput.lineSequence()
            .firstOrNull { line -> line.contains("($displayName)", ignoreCase = true) }
            ?.let { line -> guidPattern.find(line)?.value }
    }

    fun activatePowerPlan(displayName: String): Boolean {
        val plans = run(listOf("powercfg.exe", "/list"))
        if (!plans.succeeded) return false
        val guid = powerPlanGuid(plans.output, displayName) ?: return false
        return run(listOf("powercfg.exe", "/setactive", guid)).succeeded
    }

    fun runSession(): SessionReport {
        val planName = "Bitsum Highest Performance"
        val power = if (activatePowerPlan(planName)) planName else "$planName not found"
        return SessionReport(power)
    }
}
