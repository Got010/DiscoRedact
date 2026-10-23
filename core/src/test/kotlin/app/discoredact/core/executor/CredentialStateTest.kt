package app.discoredact.core.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStateTest {
    private val day = 24L * 60 * 60 * 1000
    private val fmt: (Long) -> String = { "T$it" }

    @Test
    fun noneAndUnreadableAreToldApart() {
        assertEquals("No credentials stored.", CredentialState.None.describe(fmt))
        assertEquals(
            "Stored credentials cannot be read (the phone's key changed or the file is damaged). Wipe credentials, then capture again.",
            CredentialState.Unreadable.describe(fmt),
        )
    }

    @Test
    fun aReadableSetShowsTheAgeLineAndTheStaleWarning() {
        assertEquals("Captured T5, 3 days ago.", CredentialState.Present(CredentialStatus(5, 3 * day)).describe(fmt))
        val stale = CredentialState.Present(CredentialStatus(5, 15 * day)).describe(fmt)
        assertTrue(stale.startsWith("Captured T5, 15 days ago: STALE"))
    }

    @Test
    fun wipeAppliesToAnythingStoredAndOnlyAReadableSetIsUsable() {
        assertFalse(CredentialState.None.wipeable)
        assertTrue(CredentialState.Unreadable.wipeable)
        assertTrue(CredentialState.Present(CredentialStatus(0, 0)).wipeable)
        assertFalse(CredentialState.None.usable)
        assertFalse(CredentialState.Unreadable.usable)
        assertTrue(CredentialState.Present(CredentialStatus(0, 0)).usable)
    }
}
