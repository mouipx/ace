package rawaccel.app.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
