package rawaccel.app.service

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionControllerTest {
    @Test
    fun concurrentSessionOperationsRunInSubmissionOrder() = runBlocking {
        val controller = SessionController()
        val events = mutableListOf<String>()

        val first = async {
            controller.run {
                events += "first-start"
                delay(25)
                events += "first-end"
            }
        }
        val second = async {
            controller.run {
                events += "second-start"
                events += "second-end"
            }
        }

        first.await()
        second.await()

        assertEquals(
            listOf("first-start", "first-end", "second-start", "second-end"),
            events
        )
    }
}
