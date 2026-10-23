package app.discoredact.core.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DescribeTest {
    @Test
    fun noCredentialsPointsAtTheCredentialsScreenAndNamesNoMilestone() {
        val text = Executor.describe(Executor.STOP_NO_CREDENTIALS, null, 1L)
        assertEquals("No credentials stored; import them on the Credentials screen (Settings)", text)
        assertFalse(text.contains("milestone"))
    }
}
