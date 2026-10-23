package app.discoredact.core.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInGraceTest {
    @Test fun theGraceIsTwoMinutes() {
        assertEquals(120_000L, SignInGrace.GRACE_MS)
    }

    @Test fun inFrontOrWithinTheGraceThePageStays() {
        assertFalse(SignInGrace.expired(null, 10_000_000L))
        assertFalse(SignInGrace.expired(1_000L, 1_000L))
        assertFalse(SignInGrace.expired(1_000L, 1_000L + SignInGrace.GRACE_MS - 1))
    }

    @Test fun atOrAfterTheGraceOrWithAClockGoingBackItEnds() {
        assertTrue(SignInGrace.expired(1_000L, 1_000L + SignInGrace.GRACE_MS))
        assertTrue(SignInGrace.expired(1_000L, 1_000L + 60 * 60 * 1000)) // slept through it
        assertTrue(SignInGrace.expired(5_000L, 4_999L))
    }
}
