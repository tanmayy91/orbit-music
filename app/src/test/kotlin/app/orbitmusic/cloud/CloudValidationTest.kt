package app.orbitmusic.cloud

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudValidationTest {

    @Test
    fun normalizeAndValidateHandles() {
        assertEquals("orbit_fan_99", CloudValidation.normalizeHandle("  @Orbit_Fan_99 "))
        assertTrue(CloudValidation.isValidHandle("orbit_fan_99"))
        assertFalse(CloudValidation.isValidHandle("ab"))
        assertFalse(CloudValidation.isValidHandle("bad-handle!"))
    }

    @Test
    fun normalizeAndValidateDiscordUsernames() {
        assertEquals("tanmay.music", CloudValidation.normalizeDiscordKey("@Tanmay.Music"))
        assertTrue(CloudValidation.isValidDiscordKey("tanmay.music"))
        assertFalse(CloudValidation.isValidDiscordKey("a"))
        assertFalse(CloudValidation.isValidDiscordKey("bad name"))
    }

    @Test
    fun inviteCodesAndPairDocIdsAreCanonical() {
        assertEquals("ABCD2345", CloudValidation.normalizeInviteCode(" abcd-2345 "))
        assertTrue(CloudValidation.isValidInviteCode("ABCD2345"))
        assertFalse(CloudValidation.isValidInviteCode("123"))

        assertEquals(
            CloudValidation.pairDocId("uidA", "uidB"),
            CloudValidation.pairDocId("uidB", "uidA"),
        )
        assertEquals("alpha_zeta", CloudValidation.pairDocId("zeta", "alpha"))
    }

    @Test
    fun sanitizeHandleSuggestionProducesValidHandle() {
        val fromBlank = CloudValidation.sanitizeHandleSuggestion("!!!")
        assertTrue(CloudValidation.isValidHandle(fromBlank))

        val fromDiscord = CloudValidation.sanitizeHandleSuggestion("Alex.Orbit#0001")
        assertTrue(CloudValidation.isValidHandle(fromDiscord))
    }
}
