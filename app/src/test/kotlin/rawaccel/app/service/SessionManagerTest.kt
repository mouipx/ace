package rawaccel.app.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.nio.file.Files

class SessionManagerTest {
    @Test
    fun findsBitsumPlanGuidByDisplayName() {
        val output = """
            Existing Power Schemes (* Active)
            -----------------------------------
            Power Scheme GUID: 381b4222-f694-41f0-9685-ff5bb260df2e  (Balanced)
            Power Scheme GUID: 11111111-2222-3333-4444-555555555555  (Bitsum Highest Performance) *
        """.trimIndent()

        val manager = SessionManager()

        assertEquals(
            "11111111-2222-3333-4444-555555555555",
            manager.powerPlanGuid(output, "Bitsum Highest Performance")
        )
        assertNull(manager.powerPlanGuid(output, "Missing Plan"))
    }

    @Test
    fun instanceGuardAllowsOnlyOneOwner() {
        val lockFile = Files.createTempDirectory("ace-instance").resolve("ace.lock").toFile()
        val first = InstanceGuard.acquire(lockFile)
        val second = InstanceGuard.acquire(lockFile)

        assertEquals(true, first != null)
        assertNull(second)
        first?.close()
    }
}
