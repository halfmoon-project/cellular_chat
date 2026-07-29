package com.cellularchat.app.transport.aware

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5 fixes the Wi-Fi Aware service name at `_cellfind._udp`. NAN carries no
 * service name on air — only a 6-byte Service ID hashed from it, matched with a
 * fixed-width compare — so a spelling difference between the platforms is a
 * total discovery failure with no error on either side. This side published the
 * bare name `cellfind` until 2026-07 and could never have matched iOS.
 *
 * Pins the on-air artifact rather than the source string; the iOS mirror
 * (`WiFiAwareServiceNameTests`) asserts the same six bytes.
 */
class WifiAwareServiceNameTest {

    /** sha256("_cellfind._udp")[0..6] — the bytes that actually go on air. */
    private val expectedServiceId = "58bd1bc27102"

    private fun serviceId(name: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun serviceNameHashesToThePinnedServiceId() {
        assertEquals("_cellfind._udp", WifiAwareTransport.SERVICE_NAME)
        assertEquals(expectedServiceId, serviceId(WifiAwareTransport.SERVICE_NAME))
    }

    @Test
    fun theBareNameDoesNotCollideWithThePinnedId() {
        assertNotEquals(expectedServiceId, serviceId("cellfind"))
        assertEquals("1b68dd9f32c5", serviceId("cellfind"))
    }

    /**
     * Android rejects a service name outside 1–255 UTF-8 bytes of
     * `[A-Za-z0-9._-]` (WifiAwareUtils.validateServiceName), and the check runs
     * at publish()/subscribe() — not at Builder.build() — so a bad name would
     * surface as a runtime session failure, not a fail-fast.
     */
    @Test
    fun serviceNameSatisfiesAndroidsValidator() {
        val bytes = WifiAwareTransport.SERVICE_NAME.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size in 1..255)
        assertTrue(
            bytes.all {
                val c = it.toInt().toChar()
                c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '-' || c == '.' || c == '_'
            },
        )
    }
}
